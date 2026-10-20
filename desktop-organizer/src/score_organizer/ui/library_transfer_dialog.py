"""Export on the old computer, inspect and restore on the new computer."""
from datetime import datetime
from pathlib import Path
import threading

from PySide6.QtCore import QStandardPaths
from PySide6.QtWidgets import (QDialog, QFileDialog, QHBoxLayout, QLabel, QLineEdit,
    QMessageBox, QPushButton, QVBoxLayout, QPlainTextEdit)

from score_organizer.services.batch_library_transfer import export_library, inspect_package, restore_library
from score_organizer.ui.team_publish_dialog import PublishWorker


class LibraryTransferDialog(QDialog):
    def __init__(self, db, *, importing=False, parent=None):
        super().__init__(parent)
        self.db, self.importing = db, importing
        self.worker = None
        self.result_database = None
        self.preview = None
        self.report_text = ''
        self.cancelled = threading.Event()
        self.setWindowTitle('从整库迁移包恢复' if importing else '导出整库迁移包')
        self.resize(720, 380)
        layout = QVBoxLayout(self)
        label = QLabel('在新电脑选择迁移ZIP，校验并预览后恢复到全新目录。现有库保留。' if importing else
            '在旧电脑导出：携带已识别校对记录、原编号、分类/乐器配置、草稿和库内PDF。\n请保持原PDF和桌面上的整理目录可访问。')
        label.setWordWrap(True)
        layout.addWidget(label)
        current = QLabel(f'当前记录库：{db.path}\n当前PDF目录：{db.get_setting("output_root", "尚未设置")}')
        current.setWordWrap(True)
        layout.addWidget(current)
        row = QHBoxLayout()
        self.path = QLineEdit()
        self.browse = QPushButton('选择迁移包…' if importing else '选择保存位置…')
        row.addWidget(self.path, 1)
        row.addWidget(self.browse)
        layout.addLayout(row)
        self.preview_button = QPushButton('校验迁移包并预览')
        self.preview_button.setVisible(importing)
        layout.addWidget(self.preview_button)
        self.summary = QLabel('默认保存到桌面；请把导出的完整ZIP复制到新电脑。' if not importing else
                              '普通PDF文件夹不能代替整库迁移包，请先在旧电脑导出。')
        self.summary.setWordWrap(True)
        layout.addWidget(self.summary)
        self.action = QPushButton('选择新目录并确认恢复' if importing else '导出并校验整库迁移包')
        self.action.setEnabled(not importing)
        layout.addWidget(self.action)
        self.status = QLabel('就绪')
        self.status.setWordWrap(True)
        layout.addWidget(self.status)
        self.report_button = QPushButton('查看本次迁移报告')
        self.report_button.setEnabled(False)
        self.report_button.clicked.connect(self.show_report)
        layout.addWidget(self.report_button)
        self.close_button = QPushButton('返回')
        layout.addWidget(self.close_button)
        self.browse.clicked.connect(self.choose)
        self.preview_button.clicked.connect(self.inspect)
        self.action.clicked.connect(self.restore if importing else self.export)
        self.close_button.clicked.connect(self.reject)
        self.path.textChanged.connect(self.invalidate)
        if not importing:
            desktop = QStandardPaths.writableLocation(QStandardPaths.DesktopLocation) or str(Path.home() / 'Desktop')
            self.path.setText(str(Path(desktop) / ('曲谱库整库迁移-' + datetime.now().strftime('%Y%m%d-%H%M%S') + '.zip')))

    def invalidate(self):
        self.preview = None
        self.report_text = ''
        self.report_button.setEnabled(False)
        if self.importing:
            self.action.setEnabled(False)

    def choose(self):
        if self.importing:
            path, _ = QFileDialog.getOpenFileName(self, '选择旧电脑导出的完整迁移包', self.path.text(), '整库迁移包 (*.zip)')
        else:
            path, _ = QFileDialog.getSaveFileName(self, '保存整库迁移包（不覆盖已有文件）', self.path.text(), '整库迁移包 (*.zip)')
            if path and not path.lower().endswith('.zip'):
                path += '.zip'
        if path:
            self.path.setText(path)

    def busy(self):
        return self.worker is not None and self.worker.isRunning()

    def run(self, callback, success):
        if self.busy():
            return
        self.cancelled.clear()
        for control in (self.path, self.browse, self.preview_button, self.action):
            control.setEnabled(False)
        self.close_button.setText('取消操作并返回')
        def operation(emit):
            def progress(message):
                if self.cancelled.is_set():
                    raise InterruptedError('操作已取消，原曲库保留')
                emit(message)
            progress('正在准备…')
            return callback(progress)
        self.worker = PublishWorker(operation, self)
        self.worker.progress.connect(self.status.setText)
        self.worker.succeeded.connect(success)
        self.worker.failed.connect(lambda message: self.status.setText('未完成：' + message))
        self.worker.finished.connect(self.finished_operation)
        self.worker.start()

    def finished_operation(self):
        for control in (self.path, self.browse, self.preview_button):
            control.setEnabled(True)
        self.action.setEnabled(not self.importing or self.preview is not None)
        self.close_button.setText('返回')
        if self.result_database is not None:
            self.accept()
        elif self.cancelled.is_set():
            super().reject()

    def export(self):
        path = self.path.text().strip()
        if not path:
            self.status.setText('请先选择迁移包保存位置')
            return
        self.run(lambda progress: export_library(self.db, path, progress=progress), self.exported)

    def exported(self, result):
        self.status.setText(f'导出成功并已回读校验：{result["songs"]}首曲目、{result["archivedScores"]}份正式分谱、'
                            f'{result["draftItems"]}条导入记录。\n迁移包：{result["path"]}\n'
                            '请将完整ZIP复制到新电脑，使用“数据管理→从整库迁移包恢复”。')
        if result['recoveredSources']:
            self.summary.setText(f'其中{result["recoveredSources"]}个缺失来源已从哈希相同的库内副本完整带出。')
        if 'batch' in result:
            batch = result['batch']
            self.status.setText(f'导出成功并已回读校验：可用{batch["usableSongs"]}首／{batch["usableScores"]}份分谱；'
                f'跳过{batch["skippedSongs"]}首曲目、{batch["skippedDraftGroups"]}组草稿。\n迁移包：{result["path"]}\n'
                '编号和校对记录已保留；请查看本次迁移报告，再在新电脑恢复。')
            self.summary.setText(f'本次可用曲目{batch["usableSongs"]}首／{batch["usableScores"]}份分谱；'
                f'跳过待处理曲目{batch["skippedSongs"]}首、草稿{batch["skippedDraftGroups"]}组。\n'
                '问题曲目的编号和校对记录已保留，具体曲目、声部、路径和原因见迁移报告。')
            self.report_text = result['report']
            self.report_button.setEnabled(True)

    def show_report(self):
        show_transfer_report(self.report_text, self)

    def inspect(self):
        self.preview = None
        path = self.path.text().strip()
        if not path:
            self.status.setText('请先选择迁移ZIP')
            return
        self.run(lambda progress: inspect_package(path, progress=progress), self.inspected)

    def inspected(self, value):
        self.preview = value
        counts = value['counts']
        self.summary.setText(f'已核验：{counts["songs"]}首曲目、{counts["archivedScores"]}份正式分谱、'
            f'{counts["draftItems"]}条导入记录；编号格式v{value["namingVersion"]}。\n'
            '恢复保留原编号、校对及处理结果；需要重新登录团队服务。旧电脑的清空恢复快捷记录不会在新电脑启用。')
        self.status.setText('迁移包校验通过，可以选择全新恢复位置')
        if 'batch' in value:
            from score_organizer.services.transfer_batch import report_text
            batch = value['batch']
            self.summary.setText(f'可迁移使用：{batch["usableSongs"]}首／{batch["usableScores"]}份正式分谱；'
                f'跳过：{batch["skippedSongs"]}首曲目、{batch["skippedDraftGroups"]}组草稿。\n'
                '问题曲目的编号、校对记录与原始快照保留，不作为可用曲目入库。请查看迁移报告。')
            self.report_text = report_text(batch, value['fileIssues'], value['sourceIssues'])
            self.report_button.setEnabled(True)

    def restore(self):
        if self.preview is None:
            return
        parent = QFileDialog.getExistingDirectory(self, '选择新曲库的存放位置（将在其中新建独立目录）')
        if not parent:
            return
        target = Path(parent) / ('迁移曲谱库-' + datetime.now().strftime('%Y%m%d-%H%M%S'))
        if QMessageBox.question(self, '确认整库恢复', f'恢复到：\n{target}\n\n'
                '完整校验成功后切换到此库，原编号保留。当前曲库不会覆盖或合并，仍保留在原位置。\n是否继续？',
                QMessageBox.Yes | QMessageBox.No, QMessageBox.No) != QMessageBox.Yes:
            return
        expected, package = self.preview, self.path.text().strip()
        def restore(progress):
            if inspect_package(package, progress=progress) != expected:
                raise ValueError('迁移包与刚才预览不同，请重新校验')
            return restore_library(package, target, progress=progress)
        self.run(restore, lambda database: setattr(self, 'result_database', database))

    def reject(self):
        if self.busy():
            self.cancelled.set()
            self.status.setText('正在安全取消，校验中的当前文件完成后返回…')
            return
        super().reject()

    def closeEvent(self, event):
        event.ignore()
        self.reject()


def show_transfer_report(text, parent=None):
    dialog = QDialog(parent)
    dialog.setWindowTitle('迁移结果：成功与跳过明细')
    dialog.resize(860, 600)
    layout = QVBoxLayout(dialog)
    report = QPlainTextEdit()
    report.setReadOnly(True)
    report.setPlainText(text)
    layout.addWidget(report)
    close = QPushButton('返回')
    close.clicked.connect(dialog.accept)
    layout.addWidget(close)
    dialog.exec()
