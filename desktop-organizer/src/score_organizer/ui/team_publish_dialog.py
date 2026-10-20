from __future__ import annotations

import uuid
from pathlib import Path
from PySide6.QtCore import Qt, QThread, Signal
from PySide6.QtWidgets import (QAbstractItemView, QCheckBox, QComboBox, QDialog, QFormLayout, QHBoxLayout,
    QFileDialog, QLabel, QLineEdit, QListWidget, QListWidgetItem, QMessageBox, QPlainTextEdit, QPushButton, QSplitter,
    QTableWidgetItem, QVBoxLayout, QWidget)

from score_organizer.services.team_publish import TeamClient
from score_organizer.services.runtime_services import publish as PublishService
from score_organizer.domain.score_naming import format_score_number
from score_organizer.ui.readable_table import ReadableTableWidget
from score_organizer.services.pdf_upload_compat import upload_report


class PublishWorker(QThread):
    succeeded = Signal(object)
    failed = Signal(str)
    progress = Signal(str)

    def __init__(self, callback, parent):
        super().__init__(parent)
        self.callback = callback

    def run(self):
        try:
            self.succeeded.emit(self.callback(self.progress.emit))
        except Exception as exc:
            self.failed.emit(str(exc))
        finally:
            self.callback = None


class TeamPublishDialog(QDialog):
    def __init__(self, db, parent=None):
        super().__init__(parent)
        self.setWindowTitle('上传乐谱到管理员总库')
        self.resize(1240, 820)
        self.db, self.service = db, PublishService(db)
        self.client = self.worker = self.plan = None
        self.assignments, self.member_names = {}, {}
        self.report_text = ''
        self.publisher_id = db.get_setting('team_publisher_id', '') or str(uuid.uuid4())
        db.set_setting('team_publisher_id', self.publisher_id)
        layout = QVBoxLayout(self)
        self.controls = QWidget()
        content = QVBoxLayout(self.controls)
        form = QFormLayout()
        self.address = QLineEdit(db.get_setting('team_server_address', ''))
        self.address.setPlaceholderText('https://你的乐谱服务域名')
        self.username, self.password = QLineEdit(), QLineEdit()
        self.password.setEchoMode(QLineEdit.Password)
        self.login_button = QPushButton('管理员登录 / 刷新成员')
        form.addRow('服务地址', self.address)
        row = QHBoxLayout()
        row.addWidget(QLabel('账号'))
        row.addWidget(self.username)
        row.addWidget(QLabel('密码'))
        row.addWidget(self.password)
        row.addWidget(self.login_button)
        form.addRow(row)
        team_row=QHBoxLayout();self.teams=QComboBox();self.team_switch=QPushButton('切换上传团队')
        self.teams.setEnabled(False);self.team_switch.setEnabled(False)
        team_row.addWidget(self.teams,1);team_row.addWidget(self.team_switch);form.addRow('目标团队',team_row)
        content.addLayout(form)
        content.addWidget(QLabel('支持总谱、总谱+分谱及各声部分谱；按整份 PDF 上传和授权。先入管理员总库，再另行分发；不限总份数，自动分批上传。'))
        self.library_only = QCheckBox('仅上传管理员总库，稍后再分发给成员')
        self.library_only.setChecked(True)
        content.addWidget(self.library_only)
        self.search = QLineEdit()
        self.search.setPlaceholderText('按编号、曲名、分类或乐器搜索；筛选会清除当前行选择')
        content.addWidget(self.search)
        split = QSplitter(Qt.Horizontal)
        self.scores = ReadableTableWidget(0, 5)
        self.scores.setHorizontalHeaderLabels(['编号', '曲名', '分类', '乐器', '接收成员'])
        self.scores.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.scores.setSelectionMode(QAbstractItemView.ExtendedSelection)
        self.scores.setEditTriggers(QAbstractItemView.NoEditTriggers)
        split.addWidget(self.scores)
        member_panel = QWidget()
        member_layout = QVBoxLayout(member_panel)
        member_layout.addWidget(QLabel('勾选接收成员（不会自动全选）'))
        self.members = QListWidget()
        self.members.setEnabled(False)
        member_layout.addWidget(self.members)
        self.assign_button = QPushButton('添加所选分谱到上传清单')
        self.remove_button = QPushButton('取消所选分谱发布')
        member_layout.addWidget(self.assign_button)
        member_layout.addWidget(self.remove_button)
        split.addWidget(member_panel)
        split.setStretchFactor(0, 3)
        split.setStretchFactor(1, 1)
        content.addWidget(split, 1)
        self.preview_button = QPushButton('生成上传预览')
        content.addWidget(self.preview_button)
        self.preview = ReadableTableWidget(0, 7)
        self.preview.setHorizontalHeaderLabels(['编号 / 曲名', '分类', '乐器', '上传大小', '上传 SHA-256', '接收成员', 'PDF 检查'])
        self.preview.setEditTriggers(QAbstractItemView.NoEditTriggers)
        self.preview.setMaximumHeight(180)
        content.addWidget(self.preview)
        self.confirm = QCheckBox('我已逐份核对 PDF、编号、分类及谱种；入总库不会分发给普通成员')
        content.addWidget(self.confirm)
        self.publish_button = QPushButton('确认上传管理员总库')
        self.publish_button.setEnabled(False)
        content.addWidget(self.publish_button)
        layout.addWidget(self.controls, 1)
        self.report_button = QPushButton('查看本次上传报告…')
        self.report_button.setEnabled(False)
        self.report_button.clicked.connect(self.show_report)
        layout.addWidget(self.report_button)
        self.status = QLabel('请先连接团队服务。密码和登录凭据仅保存在本次窗口内存中。')
        self.status.setWordWrap(True)
        layout.addWidget(self.status)
        self.login_button.clicked.connect(self.login)
        self.team_switch.clicked.connect(self.switch_team)
        self.search.textChanged.connect(self.filter_scores)
        self.assign_button.clicked.connect(self.assign)
        self.remove_button.clicked.connect(self.remove)
        self.preview_button.clicked.connect(self.make_preview)
        self.library_only.toggled.connect(self.mode_changed)
        self.confirm.toggled.connect(lambda checked:self.publish_button.setEnabled(bool(checked and self.plan and self.plan.parts and self.client)))
        self.publish_button.clicked.connect(self.publish)
        categories = {r['code']:r['name'] for r in db.list_categories()}
        self.rows = list(db.list_archived_scores())
        self.scores.setRowCount(len(self.rows))
        for row, score in enumerate(self.rows):
            values = [format_score_number(score["instrument_code"],score["category_code"],score["seq"],naming_version=self.db._numbering_version), score['display_title'],
                      f"{score['category_code']} {categories[score['category_code']]}", f"{score['instrument_code']} {score['instrument_name']}", '未分配']
            for column, value in enumerate(values):
                self.scores.setItem(row, column, QTableWidgetItem(value))
        self.scores.fit_default_columns({0:95, 2:125, 3:150})

    def invalidate(self):
        self.plan = None
        self.confirm.setChecked(False)
        self.confirm.setEnabled(True)
        self.publish_button.setEnabled(False)
        self.preview.setRowCount(0)

    def mode_changed(self, library_only):
        self.invalidate()
        self.assignments.clear()
        self.members.setEnabled(not library_only)
        self.assign_button.setText('添加所选分谱到上传清单' if library_only else '分配接收成员 → 所选分谱')
        self.publish_button.setText('确认上传管理员总库' if library_only else '确认上传并分发')
        self.confirm.setText('我已逐份核对 PDF、编号、分类及谱种；入总库不会分发给普通成员' if library_only
                             else '我已核对 PDF 和接收成员，确认成员可阅读整份 PDF（含组合谱全部内容）')
        self.refresh_assignments()

    def run(self, callback, success):
        if self.worker and self.worker.isRunning():
            return
        self.controls.setEnabled(False)
        self.status.setText('正在处理，请稍候…')
        self.worker = PublishWorker(callback, self)
        self.worker.progress.connect(self.status.setText)
        self.worker.succeeded.connect(success)
        self.worker.failed.connect(self.failed)
        self.worker.finished.connect(lambda:self.controls.setEnabled(True))
        self.worker.start()

    def failed(self, message):
        self.status.setText(message + '。若上传中断，可重试相同发布；以服务器发布回执为准。')
        self.report_text = upload_report(self.plan, error=message) if self.plan else '本次检查未完成：' + message
        self.report_button.setEnabled(True)
        if self.client and not self.client.token:
            self.invalidate()

    def login(self):
        self.invalidate()
        self.assignments.clear()
        self.members.clear()
        try:
            client = TeamClient(self.address.text(), self.publisher_id)
        except ValueError as exc:
            self.status.setText(str(exc))
            return
        username, password, previous = self.username.text(), self.password.text(), self.client
        self.password.clear()
        self.client = None
        self.refresh_assignments()
        def connect(progress):
            progress('正在登录并读取团队成员…')
            if previous:
                try:
                    previous.logout()
                except ValueError:
                    pass
            try:
                client.login(username, password)
                teams=client.teams() if client.principal.get('multiTeamProtocol')==1 else []
                members=client.request('GET','/v1/admin/members')['members'] if client.principal['role']=='admin' else []
                return client,members,teams
            except Exception:
                try:
                    client.logout()
                except ValueError:
                    pass
                raise
        self.run(connect, self.connected)

    def connected(self, result):
        self.client,members=result[:2];teams=result[2] if len(result)>2 else []
        self.teams.clear()
        for team in teams:
            if team['active'] and team['membership']=='joined' and team['role']=='admin':
                self.teams.addItem(team['teamName'],team['teamId'])
        index=self.teams.findData(self.client.principal['teamId']);self.teams.setCurrentIndex(index)
        self.teams.setEnabled(bool(teams));self.team_switch.setEnabled(bool(teams) and self.teams.count()>0)
        self.address.setText(self.client.address)
        self.db.set_setting('team_server_address', self.client.address)
        self.member_names = {}
        for member in members:
            if not member['active']:
                continue
            name = f"{member['displayName']}（{member['username']}）"
            self.member_names[member['accountId']] = name
            item = QListWidgetItem(name)
            item.setData(Qt.UserRole, member['accountId'])
            item.setFlags(item.flags() | Qt.ItemIsUserCheckable)
            item.setCheckState(Qt.Unchecked)
            self.members.addItem(item)
        self.status.setText(f"已连接 {self.client.principal['teamName']} · {self.client.address}；选择分谱后可直接生成上传预览。")
        if self.client.principal['role']!='admin':self.status.setText('账号已登录。请先选择一个你担任管理员的团队，再上传；不会扩大当前成员权限。')

    def switch_team(self):
        client=self.client;target=self.teams.currentData()
        if self.worker and self.worker.isRunning() or not client or not target or target==client.principal['teamId']:return
        if QMessageBox.question(self,'切换上传团队',f'切换到「{self.teams.currentText()}」？\n当前上传预览和接收成员选择将清除，已上传的文件保持。')!=QMessageBox.Yes:return
        self.invalidate();self.assignments.clear();self.members.clear();self.member_names={};self.refresh_assignments()
        def change(progress):
            progress('正在核验目标团队…');client.switch_team(target)
            if client.principal['role']!='admin':raise ValueError('你在目标团队没有管理员权限')
            return client,client.request('GET','/v1/admin/members')['members'],client.teams()
        self.run(change,self.connected)

    def filter_scores(self, value):
        self.scores.clearSelection()
        for row in range(self.scores.rowCount()):
            self.scores.setRowHidden(row, value.casefold() not in ' '.join(self.scores.item(row,c).text() for c in range(4)).casefold())

    def selected(self):
        return sorted({index.row() for index in self.scores.selectionModel().selectedRows() if not self.scores.isRowHidden(index.row())})

    def assign(self):
        recipients = ((self.client.principal['accountId'],) if self.library_only.isChecked() and self.client else
                      tuple(self.members.item(i).data(Qt.UserRole) for i in range(self.members.count()) if self.members.item(i).checkState() == Qt.Checked))
        rows = self.selected()
        if not self.client or not rows or not recipients:
            self.status.setText('请先登录并选择分谱。' if self.library_only.isChecked() else '请先登录，并同时选择分谱和接收成员。')
            return
        self.invalidate()
        for row in rows:
            self.assignments[int(self.rows[row]['id'])] = recipients
        self.refresh_assignments()

    def remove(self):
        self.invalidate()
        for row in self.selected():
            self.assignments.pop(int(self.rows[row]['id']), None)
        self.refresh_assignments()

    def refresh_assignments(self):
        for row, score in enumerate(self.rows):
            text = ('管理员总库（稍后分发）' if self.library_only.isChecked() and int(score['id']) in self.assignments else
                    '、'.join(self.member_names.get(a, a) for a in self.assignments.get(int(score['id']), ())) or '未选择')
            self.scores.item(row, 4).setText(text)

    def make_preview(self):
        self.invalidate()
        if not self.client:
            self.status.setText('请先以团队管理员账号登录。')
            return
        if self.library_only.isChecked() and self.selected():
            self.assign()
        assignments = dict(self.assignments)
        self.report_text = ''
        self.report_button.setEnabled(False)
        self.run(lambda progress:self.service.prepare_plan(assignments, progress), self.show_preview)

    def show_preview(self, plan):
        self.plan = plan
        self.preview.setRowCount(len(plan.parts) + len(plan.skipped))
        for row, part in enumerate(plan.parts):
            values = [f'{part.number} {part.title}', part.category, part.part_name, f'{part.effective_size/1024:.1f} KiB',
                      part.effective_digest[:12] + '…', '管理员总库（稍后分发）' if self.library_only.isChecked() else '、'.join(self.member_names.get(a, a) for a in part.accounts),
                      '兼容副本：' + part.upload.explanation if part.upload and part.upload.explanation else '原件直传']
            for column, value in enumerate(values):
                self.preview.setItem(row, column, QTableWidgetItem(value))
            self.preview.item(row, 4).setToolTip(f'原文件：{part.digest}\n上传：{part.effective_digest}')
        for row, issue in enumerate(plan.skipped, len(plan.parts)):
            for column, value in enumerate((f'{issue.number} {issue.title}', '', '', '', '', '不上传', '跳过：' + issue.reason)):
                self.preview.setItem(row, column, QTableWidgetItem(value))
                self.preview.item(row, column).setToolTip(issue.reason)
        self.preview.fit_default_columns({1:115, 2:140, 3:90, 4:120, 6:180})
        self.report_text = upload_report(plan)
        self.report_button.setEnabled(True)
        self.confirm.setEnabled(bool(plan.parts))
        compatible = sum(bool(part.upload and part.upload.explanation) for part in plan.parts)
        self.status.setText(f'可上传 {len(plan.parts)} 份，兼容副本 {compatible} 份，跳过 {len(plan.skipped)} 份。请核对预览与报告后勾选确认。')

    def publish(self):
        if not self.plan or not self.plan.parts or not self.client or not self.confirm.isChecked():
            return
        if self.address.text().strip().rstrip('/') != self.client.address:
            self.status.setText('服务地址已修改，请重新登录。')
            return
        action = '上传到管理员总库，仅上传管理员可阅读，之后另行分发' if self.library_only.isChecked() else '上传并授权预览中的接收成员'
        compatible = sum(bool(part.upload and part.upload.explanation) for part in self.plan.parts)
        if QMessageBox.question(self, '确认上传', f'向「{self.client.principal["teamName"]}」\n{self.client.address}\n{action} {len(self.plan.parts)} 份？其中 {compatible} 份使用独立兼容副本，{len(self.plan.skipped)} 份按报告跳过。原件保持不变，将自动分批。',
                                QMessageBox.Yes | QMessageBox.No, QMessageBox.No) != QMessageBox.Yes:
            return
        plan, client = self.plan, self.client
        self.run(lambda progress:self.service.publish(plan, client, self.publisher_id, confirmed=True, progress=progress), self.published)

    def published(self, receipt):
        batches = len(receipt.get('publicationIds', [receipt['publicationId']]))
        skipped = len(receipt.get('skipped', ()))
        self.report_text = upload_report(self.plan, receipt)
        self.report_button.setEnabled(True)
        self.status.setText((f"上传完成：成功 {len(receipt['artifactIds'])} 份，跳过 {skipped} 份，{batches} 批。" if skipped
                             else f"上传成功：{len(receipt['artifactIds'])} 份，{batches} 批。") +
                            f"最后回执：{receipt['publicationId']}。本地原文件和编号保持不变，可查看上传报告。")
        self.invalidate()

    def show_report(self):
        dialog = QDialog(self)
        dialog.setWindowTitle('本次 PDF 检查与上传报告')
        dialog.resize(920, 640)
        layout = QVBoxLayout(dialog)
        text = QPlainTextEdit(self.report_text)
        text.setReadOnly(True)
        layout.addWidget(text)
        save = QPushButton('保存报告…')
        def save_report():
            path, _ = QFileDialog.getSaveFileName(dialog, '保存上传报告', '曲谱库上传报告.txt', '文本文件 (*.txt)')
            if path:
                try:
                    Path(path).write_text(self.report_text, encoding='utf-8')
                except OSError as error:
                    QMessageBox.warning(dialog, '报告保存失败', str(error))
        save.clicked.connect(save_report)
        layout.addWidget(save)
        dialog.exec()

    def finish_close(self):
        try:
            self.service.close()
        except OSError:
            self.status.setText('临时上传副本清理失败，请检查磁盘权限后再次关闭。原文件未改动。')
            return
        super().reject()

    def reject(self):
        if self.worker and self.worker.isRunning():
            self.status.setText('正在执行操作，请等待完成后关闭。')
            return
        if self.client:
            client, self.client = self.client, None
            self.run(lambda progress:client.logout(), lambda _:self.finish_close())
            self.worker.failed.disconnect(self.failed)
            self.worker.failed.connect(lambda _:self.finish_close())
            return
        self.finish_close()

    def closeEvent(self, event):
        event.ignore()
        self.reject()
