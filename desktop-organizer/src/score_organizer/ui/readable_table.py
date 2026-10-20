from PySide6.QtCore import Qt, QEvent
from PySide6.QtGui import QTextOption
from PySide6.QtWidgets import (
    QComboBox, QDialog, QDialogButtonBox, QMenu, QMessageBox,
    QPlainTextEdit, QTableWidget, QVBoxLayout,
)


class ReadableTableWidget(QTableWidget):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.setWordWrap(False)
        self.context_menu_builder = None
        self._fitted_columns = None
        self._fitting_columns = False
        self._manually_resized = False
        self.horizontalHeader().sectionResized.connect(self._remember_column_resize)
        self.setContextMenuPolicy(Qt.CustomContextMenu)
        self.customContextMenuRequested.connect(self._show_content_menu)

    def fit_default_columns(self, fixed_widths):
        self._fitted_columns = dict(fixed_widths)
        self._manually_resized = False
        self._fit_columns()

    def _remember_column_resize(self, *_):
        if self._fitted_columns is not None and not self._fitting_columns:
            self._manually_resized = True

    def resizeEvent(self, event):
        super().resizeEvent(event)
        if getattr(self, "_fitted_columns", None) is not None and not self._manually_resized and not self._fitting_columns:
            self._fit_columns()

    def viewportEvent(self, event):
        handled = super().viewportEvent(event)
        if event.type() == QEvent.Resize and getattr(self, "_fitted_columns", None) is not None:
            if not self._manually_resized and not self._fitting_columns:
                self._fit_columns()
        return handled

    def _fit_columns(self):
        fixed = self._fitted_columns
        flexible = [column for column in range(self.columnCount()) if column not in fixed]
        available = max(75 * len(flexible), self.viewport().width() - sum(fixed.values()))
        self._fitting_columns = True
        try:
            for column, width in fixed.items():
                self.setColumnWidth(column, width)
            for index, column in enumerate(flexible):
                width = available // len(flexible) + (index < available % len(flexible))
                self.setColumnWidth(column, width)
        finally:
            self._fitting_columns = False

    def setItem(self, row, column, item):
        if not item.toolTip():
            item.setToolTip(item.text())
        super().setItem(row, column, item)

    def full_information(self, rows):
        sections = []
        for row in sorted(set(rows)):
            if row < 0 or row >= self.rowCount() or self.isRowHidden(row):
                continue
            lines = [f"第 {row + 1} 行"]
            paths = []
            for column in range(self.columnCount()):
                header = self.horizontalHeaderItem(column)
                label = header.text() if header else str(column + 1)
                item = self.item(row, column)
                widget = self.cellWidget(row, column)
                value = widget.currentText() if isinstance(widget, QComboBox) else item.text() if item else ""
                lines.append(f"{label}：{value}")
                if item:
                    if item.toolTip() and item.toolTip() != value:
                        lines.append(item.toolTip())
                    associated = item.data(Qt.UserRole + 1)
                    if isinstance(associated, list):
                        paths.extend(str(path) for path in associated)
            if paths:
                lines.append("关联PDF：\n" + "\n".join(dict.fromkeys(paths)))
            sections.append("\n".join(lines))
        return "\n\n".join(sections)

    def show_full_information(self):
        rows = {item.row() for item in self.selectedItems()}
        content = self.full_information(rows)
        if not content:
            QMessageBox.information(self, "请选择记录", "请先选择要查看的记录")
            return
        dialog = QDialog(self.window())
        dialog.setWindowTitle("完整信息")
        dialog.resize(900, 520)
        layout = QVBoxLayout(dialog)
        text = QPlainTextEdit()
        text.setObjectName("full_information_text")
        text.setReadOnly(True)
        text.setWordWrapMode(QTextOption.WrapAtWordBoundaryOrAnywhere)
        text.setPlainText(content)
        layout.addWidget(text)
        close = QDialogButtonBox(QDialogButtonBox.Close)
        close.button(QDialogButtonBox.Close).setText("关闭")
        close.rejected.connect(dialog.reject)
        layout.addWidget(close)
        dialog.exec()
        dialog.deleteLater()

    def _show_content_menu(self, position):
        item = self.itemAt(position)
        if item is None:
            return
        if not item.isSelected():
            self.selectRow(item.row())
        menu = QMenu(self)
        menu.addAction("查看完整信息", self.show_full_information)
        if self.context_menu_builder is not None:
            self.context_menu_builder(menu)
        menu.exec(self.viewport().mapToGlobal(position))
