from __future__ import annotations

import json
from pathlib import Path

from PySide6.QtCore import Qt, QThread, Signal, QUrl
from PySide6.QtGui import QDesktopServices
from PySide6.QtWidgets import (
    QAbstractItemView, QCheckBox, QComboBox, QDialog, QDialogButtonBox, QFileDialog,
    QFormLayout, QHBoxLayout, QHeaderView, QLabel, QLineEdit, QMessageBox, QPushButton,
    QSpinBox, QSplitter, QTableWidgetItem, QToolButton, QStyle, QVBoxLayout, QWidget,
)

from score_organizer.services.program_service import safe_name
from score_organizer.services.runtime_services import program as ProgramService
from score_organizer.ui.readable_table import ReadableTableWidget


class ProgramWorker(QThread):
    succeeded = Signal(object)
    failed = Signal(str)

    def __init__(self, callback, parent):
        super().__init__(parent)
        self.callback = callback

    def run(self):
        try:
            self.succeeded.emit(self.callback())
        except Exception as exc:
            self.failed.emit(str(exc))


class ProgramDialog(QDialog):
    def __init__(self, db, parent=None):
        super().__init__(parent)
        self.setWindowTitle('节目单打包')
        self.resize(1100, 720)
        self.service = ProgramService(db, db.get_setting('output_root', ''))
        self.catalog = self.service.catalog()
        self.entries = []
        self.worker = None
        self.result_path = None
        layout = QVBoxLayout(self)
        form = QFormLayout()
        self.name = QLineEdit('节目乐谱')
        self.name.setObjectName('program_name')
        form.addRow('节目单名称', self.name)
        layout.addLayout(form)
        splitter = QSplitter(Qt.Horizontal)
        left, right = QWidget(), QWidget()
        a, b = QVBoxLayout(left), QVBoxLayout(right)
        a.setContentsMargins(0, 0, 6, 0)
        b.setContentsMargins(6, 0, 0, 0)
        self.search = QLineEdit()
        self.search.setObjectName('program_search')
        self.search.setPlaceholderText('搜索曲名、分类或库编号')
        self.category = QComboBox()
        self.category.setObjectName('program_category')
        self.category.addItem('全部分类', None)
        for row in db.list_categories():
            self.category.addItem(f"{row['code']} {row['name']}", row['code'])
        a.addWidget(self.search)
        a.addWidget(self.category)
        self.results = self.table(['库编号', '曲名', '分类', '分谱数'], 'program_results')
        a.addWidget(self.results)
        add = QPushButton('添加到节目单')
        add.setObjectName('program_add')
        add.clicked.connect(self.add_selected)
        a.addWidget(add)
        b.addWidget(QLabel('节目单'))
        self.program = self.table(['节目序号', '曲名', '分类', '库编号'], 'program_entries')
        b.addWidget(self.program)
        tools = QHBoxLayout()
        for label, icon, callback in (
            ('上移', QStyle.SP_ArrowUp, lambda: self.move(-1)),
            ('下移', QStyle.SP_ArrowDown, lambda: self.move(1)),
            ('移除选中节目', QStyle.SP_TrashIcon, self.remove_selected),
        ):
            button = QToolButton()
            button.setIcon(self.style().standardIcon(icon))
            button.setToolTip(label)
            button.clicked.connect(callback)
            tools.addWidget(button)
        tools.addStretch()
        for title, callback in [('保存节目单', self.save_program), ('载入节目单', self.load_program)]:
            button = QPushButton(title)
            button.clicked.connect(callback)
            tools.addWidget(button)
        b.addLayout(tools)
        splitter.addWidget(left)
        splitter.addWidget(right)
        splitter.setSizes([470, 600])
        layout.addWidget(splitter, 1)
        destination = QHBoxLayout()
        destination.addWidget(QLabel('打包位置'))
        self.output = QLineEdit()
        self.output.setObjectName('program_output')
        destination.addWidget(self.output)
        choose = QPushButton('选择…')
        choose.clicked.connect(self.choose_output)
        destination.addWidget(choose)
        self.zip = QCheckBox('同时生成ZIP')
        self.zip.setChecked(True)
        destination.addWidget(self.zip)
        layout.addLayout(destination)
        self.status = QLabel('')
        self.status.setWordWrap(True)
        self.status.setObjectName('program_status')
        layout.addWidget(self.status)
        bottom = QHBoxLayout()
        self.open = QPushButton('打开打包文件夹')
        self.open.setEnabled(False)
        self.open.clicked.connect(lambda: QDesktopServices.openUrl(QUrl.fromLocalFile(str(self.result_path))))
        bottom.addWidget(self.open)
        bottom.addStretch()
        self.export = QPushButton('预览并打包')
        self.export.setObjectName('program_export')
        self.export.clicked.connect(self.preview)
        bottom.addWidget(self.export)
        close = QPushButton('关闭')
        close.clicked.connect(self.reject)
        bottom.addWidget(close)
        layout.addLayout(bottom)
        self.search.textChanged.connect(self.filter_catalog)
        self.category.currentIndexChanged.connect(self.filter_catalog)
        self.results.cellDoubleClicked.connect(lambda *_: self.add_selected())
        self.populate_catalog()

    def table(self, headers, name):
        table = ReadableTableWidget(0, len(headers))
        table.setObjectName(name)
        table.setHorizontalHeaderLabels(headers)
        table.setSelectionBehavior(QAbstractItemView.SelectRows)
        table.setSelectionMode(QAbstractItemView.ExtendedSelection)
        table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        table.horizontalHeader().setSectionResizeMode(QHeaderView.Interactive)
        table.fit_default_columns({0: 90, 2: 110, 3: 85})
        return table

    def populate_catalog(self):
        self.results.setRowCount(len(self.catalog))
        for row, song in enumerate(self.catalog):
            self.fill(self.results, row, [song['seq'], song['title'],
                                         f"{song['category']} {song['category_name']}", len(song['instruments'])])
        self.filter_catalog()

    def fill(self, table, row, values):
        for col, value in enumerate(values):
            item = QTableWidgetItem(str(value))
            item.setToolTip(str(value))
            table.setItem(row, col, item)

    def filter_catalog(self, *_):
        query = ''.join(self.search.text().casefold().split())
        for row, song in enumerate(self.catalog):
            text = ''.join(f"{song['title']} {song['category']} {song['category_name']} {song['seq']}".casefold().split())
            visible = (self.category.currentData() in (None, song['category'])) and query in text
            self.results.setRowHidden(row, not visible)
            if not visible:
                for col in range(self.results.columnCount()):
                    self.results.item(row, col).setSelected(False)

    def selected_rows(self, table):
        return sorted({i.row() for i in table.selectedIndexes() if not table.isRowHidden(i.row())})

    def sync_orders(self):
        for row, entry in enumerate(self.entries):
            entry['order'] = self.program.cellWidget(row, 0).value()

    def populate_program(self):
        self.program.setRowCount(0)
        self.program.setRowCount(len(self.entries))
        for row, entry in enumerate(self.entries):
            self.fill(self.program, row, ['', entry['title'], entry['category'], entry['seq']])
            order = QSpinBox()
            order.setRange(1, 9999)
            order.setValue(entry['order'])
            order.editingFinished.connect(self.sort_orders)
            self.program.setCellWidget(row, 0, order)

    def sort_orders(self):
        self.sync_orders()
        self.entries.sort(key=lambda e: e['order'])
        self.populate_program()

    def add_selected(self):
        self.sync_orders()
        for row in self.selected_rows(self.results):
            song = self.catalog[row]
            if any(e['id'] == song['id'] for e in self.entries):
                continue
            order = max((e['order'] for e in self.entries), default=0) + 1
            if order > 9999:
                self.status.setText('节目序号已到9999，请先调整序号再添加')
                break
            self.entries.append({**song, 'order': order})
        self.populate_program()

    def remove_selected(self):
        self.sync_orders()
        selected = set(self.selected_rows(self.program))
        self.entries = [e for row, e in enumerate(self.entries) if row not in selected]
        self.populate_program()

    def move(self, direction):
        self.sync_orders()
        selected = self.selected_rows(self.program)
        if len(selected) != 1:
            self.status.setText('请只选择一个节目调整顺序')
            return
        row, target = selected[0], selected[0] + direction
        if not 0 <= target < len(self.entries):
            return
        self.entries[row]['order'], self.entries[target]['order'] = self.entries[target]['order'], self.entries[row]['order']
        self.entries[row], self.entries[target] = self.entries[target], self.entries[row]
        self.populate_program()
        self.program.selectRow(target)

    def choose_output(self):
        path = QFileDialog.getExistingDirectory(self, '选择打包位置', self.output.text())
        if path:
            self.output.setText(path)

    def save_program(self):
        self.sync_orders()
        path, _ = QFileDialog.getSaveFileName(self, '保存节目单', self.name.text() + '.json', '节目单 (*.json)')
        if path:
            try:
                data = {'format': 'score-program-v1', 'name': self.name.text(),
                        'entries': [{'song_id': e['id'], 'order': e['order'], 'title': e['title'],
                                     'category': e['category'], 'library_seq': e['seq']} for e in self.entries]}
                Path(path).write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')
            except OSError as exc:
                QMessageBox.warning(self, '保存失败', str(exc))

    def load_program(self):
        path, _ = QFileDialog.getOpenFileName(self, '载入节目单', '', '节目单 (*.json)')
        if path:
            try:
                data = json.loads(Path(path).read_text(encoding='utf-8'))
                if not isinstance(data, dict) or data.get('format') != 'score-program-v1':
                    raise ValueError('不是本软件的节目单')
                if not isinstance(data.get('name'), str) or not isinstance(data.get('entries'), list):
                    raise ValueError('节目单格式不完整')
                catalog = {e['id']: e for e in self.service.catalog()}
                entries = []
                orders = set()
                for item in data['entries']:
                    if not isinstance(item, dict):
                        raise ValueError('节目曲目格式无效')
                    song = catalog.get(int(item['song_id']))
                    if not song or (song['title'], song['category'], song['seq']) != (
                            item['title'], item['category'], item['library_seq']):
                        raise ValueError(f"曲目已变化或不属于当前库：{item.get('title', '')}")
                    order = int(item['order'])
                    if not 1 <= order <= 9999 or order in orders:
                        raise ValueError('节目序号无效或重复')
                    orders.add(order)
                    entries.append({**song, 'order': order})
                self.entries = sorted(entries, key=lambda e: e['order'])
                self.name.setText(data['name'])
                self.populate_program()
            except (OSError, ValueError, KeyError, TypeError) as exc:
                QMessageBox.warning(self, '载入失败', str(exc))

    def run_worker(self, callback, success):
        self.worker = ProgramWorker(callback, self)
        self.export.setEnabled(False)
        self.worker.succeeded.connect(success)
        self.worker.failed.connect(lambda error: self.status.setText('打包未完成：' + error))
        worker = self.worker
        worker.finished.connect(lambda: self.export.setEnabled(True) if self.worker is worker else None)
        self.worker.start()

    def preview(self):
        self.sync_orders()
        if not self.output.text().strip() or not self.name.text().strip():
            self.status.setText('请填写节目单名称并选择打包位置')
            return
        entries = [(e['order'], e['id']) for e in self.entries]
        name, output, zipped = self.name.text(), self.output.text(), self.zip.isChecked()
        self.status.setText('正在核对库内PDF…')
        def reviewed(plan):
            # Wait for the preview thread to finish before reusing its dialog-owned handle.
            self.worker.wait()
            dialog = QDialog(self)
            dialog.setWindowTitle('确认节目单打包')
            dialog.resize(950, 600)
            layout = QVBoxLayout(dialog)
            summary = QLabel(f'{len(plan.rows)} 个节目，{len(plan.files)} 份PDF；输出：{Path(output) / safe_name(name)}')
            summary.setWordWrap(True)
            layout.addWidget(summary)
            table = self.table(['节目序号', '曲名', '分类', '库编号'], 'program_preview')
            table.setRowCount(len(plan.rows))
            for row, item in enumerate(plan.rows):
                self.fill(table, row, [item[0], item[4], item[2], item[3]])
            layout.addWidget(table)
            files = ReadableTableWidget(len(plan.files), 2)
            files.setHorizontalHeaderLabels(['库内原文件', '打包相对路径'])
            files.setEditTriggers(QAbstractItemView.NoEditTriggers)
            files.horizontalHeader().setSectionResizeMode(QHeaderView.Stretch)
            for row, item in enumerate(plan.files):
                self.fill(files, row, [str(item.sources[0]), item.relative])
            layout.addWidget(files)
            warnings = QLabel('\n'.join(plan.missing) if plan.missing else '本节目所需乐器无缺谱')
            warnings.setWordWrap(True)
            # Large missing-part lists remain scrollable, never expand the dialog offscreen.
            from PySide6.QtWidgets import QPlainTextEdit
            missing = QPlainTextEdit(warnings.text())
            missing.setReadOnly(True)
            missing.setMaximumHeight(110)
            layout.addWidget(missing)
            buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
            buttons.button(QDialogButtonBox.Ok).setText('确认打包')
            buttons.button(QDialogButtonBox.Cancel).setText('取消')
            buttons.accepted.connect(dialog.accept)
            buttons.rejected.connect(dialog.reject)
            layout.addWidget(buttons)
            if dialog.exec() != QDialog.Accepted:
                self.status.setText('已取消，未生成打包文件')
                return
            self.status.setText('正在生成打包副本…')
            self.run_worker(lambda: self.service.export(plan, output, name, zipped), self.completed)
        self.run_worker(lambda: self.service.build_plan(entries), reviewed)

    def completed(self, path):
        self.result_path = path
        self.open.setEnabled(True)
        self.status.setText(f'打包完成：{path}')

    def reject(self):
        if self.worker and self.worker.isRunning():
            self.status.setText('正在处理，请等待完成后关闭')
            return
        super().reject()

    def closeEvent(self, event):
        if self.worker and self.worker.isRunning():
            event.ignore()
        else:
            super().closeEvent(event)
