from contextlib import closing
import sqlite3
from uuid import uuid4

from PySide6.QtWidgets import (
    QAbstractItemView, QComboBox, QDialog, QDialogButtonBox, QHeaderView, QMessageBox,
    QSpinBox, QTableWidgetItem, QTabWidget, QVBoxLayout,
)

from score_organizer.ui.readable_table import ReadableTableWidget


class ArchiveRulesDialog(QDialog):
    def __init__(self, db, parent=None):
        super().__init__(parent)
        self.db = db
        self.setObjectName("archive_rules_dialog")
        self.setWindowTitle("编号归档规则")
        self.resize(820, 540)
        layout = QVBoxLayout(self)
        tabs = QTabWidget()
        layout.addWidget(tabs)
        with db.session() as connection:
            instruments = list(connection.execute("SELECT code, name FROM instrument WHERE active = 1 ORDER BY code"))
        self.modes = {}
        self.minimums = {}
        instrument_table = ReadableTableWidget(len(instruments), 3)
        instrument_table.setHorizontalHeaderLabels(["乐器编号", "名称", "归档规则"])
        instrument_table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        instrument_table.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeToContents)
        instrument_table.horizontalHeader().setSectionResizeMode(2, QHeaderView.Stretch)
        current = db.instrument_archive_modes()
        for i, row in enumerate(instruments):
            instrument_table.setItem(i, 0, QTableWidgetItem(row["code"]))
            instrument_table.setItem(i, 1, QTableWidgetItem(row["name"]))
            combo = QComboBox()
            combo.setObjectName(f"archive_mode_{row['code']}")
            combo.addItem("按分类最少乐器数入库；双目录", "dual")
            combo.addItem("允许单份入库；仅按乐器分类", "instrument_only")
            combo.setCurrentIndex(combo.findData(current[row["code"]]))
            instrument_table.setCellWidget(i, 2, combo)
            self.modes[row["code"]] = combo
        instrument_table.setColumnWidth(2, 380)
        tabs.addTab(instrument_table, "乐器规则")
        categories = db.list_categories()
        category_table = ReadableTableWidget(len(categories), 3)
        category_table.setHorizontalHeaderLabels(["分类编号", "名称", "普通分谱最少不同乐器数"])
        category_table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        category_table.horizontalHeader().setSectionResizeMode(QHeaderView.Stretch)
        for i, row in enumerate(categories):
            category_table.setItem(i, 0, QTableWidgetItem(row["code"]))
            category_table.setItem(i, 1, QTableWidgetItem(row["name"]))
            minimum = QSpinBox()
            minimum.setObjectName(f"archive_minimum_{row['code']}")
            minimum.setRange(1, 99)
            minimum.setValue(db.minimum_instruments(row["code"]))
            category_table.setCellWidget(i, 2, minimum)
            self.minimums[row["code"]] = minimum
        tabs.addTab(category_table, "分类规则")
        buttons = QDialogButtonBox(QDialogButtonBox.Save | QDialogButtonBox.Cancel)
        buttons.button(QDialogButtonBox.Save).setText("保存规则")
        buttons.button(QDialogButtonBox.Cancel).setText("取消")
        buttons.button(QDialogButtonBox.Save).setObjectName("save_archive_rules")
        buttons.accepted.connect(self.save_rules)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)

    def save_rules(self):
        modes = {code: combo.currentData() for code, combo in self.modes.items()}
        minimums = {code: spin.value() for code, spin in self.minimums.items()}
        changes = []
        current = self.db.instrument_archive_modes()
        for code, mode in modes.items():
            if mode != current.get(code):
                changes.append(f"乐器{code}：{self.modes[code].currentText()}")
        for code, minimum in minimums.items():
            if minimum != self.db.minimum_instruments(code):
                changes.append(f"分类{code}：普通分谱至少{minimum}种不同乐器")
        if not changes:
            self.accept()
            return
        if QMessageBox.question(self, "确认规则变更", "\n".join(changes) + "\n\n仅用于后续入库；已入库编号、文件位置和副本数保持不变。重复与覆盖检查仍然生效。",
                                QMessageBox.Yes | QMessageBox.No, QMessageBox.No) != QMessageBox.Yes:
            return
        try:
            backup = self.db.path.parent / "规则备份" / f"{uuid4().hex}.sqlite3"
            backup.parent.mkdir(parents=True, exist_ok=True)
            with closing(self.db.connect()) as source, closing(sqlite3.connect(backup)) as destination:
                source.backup(destination)
            self.db.save_archive_rules(modes, minimums)
        except (ValueError, OSError, sqlite3.Error) as exc:
            QMessageBox.warning(self, "规则未保存", str(exc))
            return
        self.accept()
