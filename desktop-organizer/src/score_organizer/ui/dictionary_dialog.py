from contextlib import closing
import json
import sqlite3
from uuid import uuid4

from PySide6.QtWidgets import (
    QAbstractItemView, QCheckBox, QComboBox, QDialog, QDialogButtonBox,
    QFormLayout, QHeaderView, QLineEdit, QMessageBox, QPushButton,
    QTableWidgetItem, QVBoxLayout,
)

from score_organizer.ui.readable_table import ReadableTableWidget


class DictionaryDialog(QDialog):
    def __init__(self, db, kind, parent=None):
        super().__init__(parent)
        self.db, self.kind = db, kind
        self.setObjectName(f"{kind}_management")
        self.setWindowTitle("乐器管理" if kind == "instrument" else "分类管理")
        self.resize(700, 540)
        layout = QVBoxLayout(self)
        self.table = ReadableTableWidget(0, 3)
        self.table.setObjectName("dictionary_table")
        self.table.setHorizontalHeaderLabels(["编号", "名称", "启用"])
        self.table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        self.table.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.table.setSelectionMode(QAbstractItemView.SingleSelection)
        self.table.horizontalHeader().setSectionResizeMode(QHeaderView.Stretch)
        self.table.itemSelectionChanged.connect(self.load_selected)
        layout.addWidget(self.table)
        new = QPushButton("新增乐器" if kind == "instrument" else "新增分类")
        new.setObjectName("dictionary_new")
        new.clicked.connect(self.new_entry)
        layout.addWidget(new)
        form = QFormLayout()
        self.code = QLineEdit()
        self.code.setObjectName("dictionary_code")
        self.code.setMaxLength(2)
        self.name = QLineEdit()
        self.name.setObjectName("dictionary_name")
        self.active = QCheckBox()
        self.active.setChecked(True)
        self.active.setObjectName("dictionary_active")
        form.addRow("编号", self.code)
        form.addRow("名称", self.name)
        form.addRow("启用", self.active)
        self.mode = QComboBox()
        self.mode.setObjectName("dictionary_archive_mode")
        self.mode.addItem("按分类最少乐器数入库；双目录", "dual")
        self.mode.addItem("允许单份入库；仅按乐器分类", "instrument_only")
        if kind == "instrument":
            form.addRow("归档方式", self.mode)
        else:
            self.mode.hide()
        layout.addLayout(form)
        buttons = QDialogButtonBox(QDialogButtonBox.Save | QDialogButtonBox.Close)
        buttons.button(QDialogButtonBox.Save).setText("保存")
        buttons.button(QDialogButtonBox.Save).setObjectName("dictionary_save")
        buttons.button(QDialogButtonBox.Close).setText("关闭")
        buttons.accepted.connect(self.save_entry)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)
        self.refresh()

    def refresh(self):
        rows = self.db.list_instruments() if self.kind == "instrument" else self.db.list_categories()
        self.table.setRowCount(len(rows))
        for i, row in enumerate(rows):
            for column, value in enumerate((row["code"], row["name"], "是" if row["active"] else "否")):
                self.table.setItem(i, column, QTableWidgetItem(str(value)))

    def new_entry(self):
        self.table.clearSelection()
        self.code.setReadOnly(False)
        self.code.clear()
        self.name.clear()
        self.active.setChecked(True)
        self.mode.setCurrentIndex(0)
        self.code.setFocus()

    def load_selected(self):
        cells = self.table.selectedItems()
        if not cells:
            return
        row = cells[0].row()
        self.code.setText(self.table.item(row, 0).text())
        self.code.setReadOnly(True)
        self.name.setText(self.table.item(row, 1).text())
        self.active.setChecked(self.table.item(row, 2).text() == "是")
        modes = json.loads(self.db.get_setting("instrument_archive_modes") or "{}")
        mode = modes.get(self.code.text(), "instrument_only" if self.code.text() in {"08", "09"} else "dual")
        self.mode.setCurrentIndex(self.mode.findData(mode))

    def save_entry(self):
        code, name = self.code.text().strip(), self.name.text().strip()
        existing = next((row for row in (self.db.list_instruments() if self.kind == "instrument" else self.db.list_categories()) if row["code"] == code), None)
        if not self.code.isReadOnly() and existing:
            QMessageBox.warning(self, "编号已存在", "该编号已存在，请选择原记录修改，不能用新增覆盖")
            return
        description = f"编号{code}：{name}\n启用：{'是' if self.active.isChecked() else '否'}"
        if self.kind == "instrument":
            description += "\n归档方式：" + self.mode.currentText()
        if QMessageBox.question(self, "确认保存", description + "\n\n现有分谱编号及文件位置保持不变。",
                                QMessageBox.Yes | QMessageBox.No, QMessageBox.No) != QMessageBox.Yes:
            return
        try:
            backup = self.db.path.parent / "规则备份" / f"{uuid4().hex}.sqlite3"
            backup.parent.mkdir(parents=True, exist_ok=True)
            with closing(self.db.connect()) as source, closing(sqlite3.connect(backup)) as destination:
                source.backup(destination)
            if self.kind == "instrument":
                self.db.upsert_instrument(code, name, self.active.isChecked(), self.mode.currentData())
            else:
                self.db.upsert_category(code, name, self.active.isChecked())
        except (ValueError, OSError, sqlite3.Error) as exc:
            QMessageBox.warning(self, "未保存", str(exc))
            return
        self.refresh()
        self.new_entry()
