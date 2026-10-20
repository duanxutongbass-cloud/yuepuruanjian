from __future__ import annotations

import json
import re
import sqlite3
from pathlib import Path

from PySide6.QtGui import QAction, QDesktopServices
from PySide6.QtCore import Qt, QUrl, QItemSelectionModel
from PySide6.QtWidgets import (
    QCheckBox,
    QComboBox,
    QFileDialog,
    QDialog,
    QDialogButtonBox,
    QGridLayout,
    QFormLayout,
    QHBoxLayout,
    QHeaderView,
    QLabel,
    QLineEdit,
    QPlainTextEdit,
    QMainWindow,
    QMessageBox,
    QAbstractItemView,
    QPushButton,
    QTableWidget,
    QTableWidgetItem,
    QVBoxLayout,
    QSplitter,
    QScrollArea,
    QToolButton,
    QMenu,
    QStyle,
    QWidget,
)

from score_organizer.services.runtime_services import archive as ArchiveService
from score_organizer.services.android_rules import export_rules as export_naming_rules
from score_organizer.ui.archive_rules_dialog import ArchiveRulesDialog
from score_organizer.ui.dictionary_dialog import DictionaryDialog
from score_organizer.ui.program_dialog import ProgramDialog
from score_organizer.ui.team_publish_dialog import TeamPublishDialog
from score_organizer.domain.filename_parser import suggest_title_without_instrument
from score_organizer.domain.score_naming import format_score_filename, format_score_number
from score_organizer.domain.numbering import next_global_sequence, SequenceExhaustedError
from score_organizer.services.runtime_services import catalog as CatalogService
from score_organizer.services.runtime_services import merge as CatalogMergeService
from score_organizer.services.correction_service import CorrectionService, RelatedDraftPartsError
from score_organizer.services.grouping_service import GroupingService
from score_organizer.services.runtime_services import importer as ImportService
from score_organizer.services.runtime_services import report as ReportService
from score_organizer.services.review_service import ReviewService
from score_organizer.services.unused_service import UnusedScoreService
from score_organizer.services.runtime_services import reset as ResetService
from score_organizer.services.duplicate_service import ConfirmedDuplicateService, DuplicateService, DUPLICATE_CODES
from score_organizer.storage.database import Database
from score_organizer.ui.readable_table import ReadableTableWidget
from score_organizer.services.duplicate_layout import DuplicateLayoutStore
from score_organizer.services.replacement_service import ReplacementService


class MainWindow(QMainWindow):
    def __init__(self, db: Database) -> None:
        super().__init__()
        self.db = db
        self.category_table: QTableWidget | None = None
        self.category_code_input: QLineEdit | None = None
        self.category_name_input: QLineEdit | None = None
        self.category_active_input: QCheckBox | None = None
        self.manual_instrument_input: QLineEdit | None = None
        self.manual_title_input: QLineEdit | None = None
        self.manual_category_input: QComboBox | None = None
        self.output_path_input: QLineEdit | None = None
        self.score_table: QTableWidget | None = None
        self.report_table: QTableWidget | None = None
        self.import_summary_label: QLabel | None = None
        self.guidance_label: QLabel | None = None
        self.warning_table: QTableWidget | None = None
        self.current_batch_id: int | None = None
        self.warning_scan_completeness = False
        self.last_archive_failure = ""
        self.setWindowTitle("曲谱库重奏乐谱整理工具 0.1.7" + (" · 扩位编号" if db._numbering_version == 2 else ""))
        self.resize(1100, 720)
        self.setCentralWidget(self._build_main_widget())
        self.refresh_categories()
        self.restore_current_batch()
        self._build_menus()
        self.score_table.context_menu_builder = self._draft_context_menu
        self.warning_table.context_menu_builder = self._warning_context_menu

    def export_whole_library(self):
        from score_organizer.ui.library_transfer_dialog import LibraryTransferDialog
        LibraryTransferDialog(self.db, parent=self).exec()

    def show_latest_migration_report(self):
        from score_organizer.ui.library_transfer_dialog import show_transfer_report
        path = self.db.path.parent / 'migration-report.txt'
        try:
            if not path.is_file():
                QMessageBox.information(self, '暂无迁移报告', '当前库尚无批量迁移报告。旧版整库包没有逐曲跳过记录。')
                return
            show_transfer_report(path.read_text('utf-8'), self)
        except (OSError, ValueError) as exc:
            QMessageBox.warning(self, '报告读取失败', str(exc))

    def import_whole_library(self):
        from score_organizer.ui.library_transfer_dialog import LibraryTransferDialog
        from score_organizer.storage.expanded_database import RuntimeLock
        from score_organizer.main import remember_expanded, remember_portable
        dialog = LibraryTransferDialog(self.db, importing=True, parent=self)
        if dialog.exec() != QDialog.DialogCode.Accepted or dialog.result_database is None:
            return
        database, lease, window = dialog.result_database, None, None
        try:
            lease = RuntimeLock(database)
            window = MainWindow(database)
            (remember_expanded if database._numbering_version == 2 else remember_portable)(database.path)
            window._runtime_lease = lease
            window.destroyed.connect(lambda: lease.close())
            self._transferred_window = window
            window.show()
            if (database.path.parent / 'migration-report.txt').is_file():
                window.guidance_label.setText('迁移完成，请在“数据管理→查看最近迁移报告”核对成功与跳过明细。')
            self.close()
        except (ValueError, OSError, sqlite3.Error) as exc:
            if window is not None:
                window.close()
            if lease is not None:
                lease.close()
            QMessageBox.warning(self, '迁移库未切换', f'{exc}\n当前库仍保留；完整恢复副本也保留，请记录提示。')

    def upgrade_number_capacity(self):
        from score_organizer.services.library_relocation import prepare_relocated_library
        from score_organizer.storage.expanded_database import activate_candidate,RuntimeLock
        from score_organizer.main import remember_expanded
        if self.db._numbering_version != 1:return
        parent=QFileDialog.getExistingDirectory(self,"选择保存独立扩位曲库的父目录")
        if not parent:return
        from datetime import datetime
        target=Path(parent)/('扩位曲库-'+datetime.now().strftime('%Y%m%d-%H%M%S'))
        reply=QMessageBox.question(self,"核对编号容量升级",
            f"将当前数据库、受管PDF、历史清单及来源副本复制到：\n{target}\n\n"
            "请先关闭其他版本整理器。升级保留已有编号与原PDF，新曲优先补真正空号；"
            "支持如01-01-1000的完整编号。原库及原输出保留，旧程序继续使用原库。\n\n"
            "复制和完整核验成功后切换到独立扩位曲库，是否继续？")
        if reply != QMessageBox.Yes:return
        lease=None
        try:
            from score_organizer.storage.expanded_database import require_old_organizers_closed
            require_old_organizers_closed()
            manifest=prepare_relocated_library(self.db.path,target)
            database=activate_candidate(manifest)
            lease=RuntimeLock(database)
            window=MainWindow(database)
            remember_expanded(database.path)
            window._runtime_lease=lease
            window.destroyed.connect(lambda:lease.close())
            self._expanded_window=window
            window.show()
            self.close()
        except (ValueError,OSError,sqlite3.Error) as exc:
            if lease is not None:lease.close()
            QMessageBox.warning(self,"升级未完成",f"{exc}\n原曲库与原PDF保留；未切换启动记录时仍使用原库。失败副本请保留用于核对。")

    def _build_main_widget(self) -> QWidget:
        root = QWidget()
        layout = QHBoxLayout(root)

        self.score_table = ReadableTableWidget(0, 6)
        self.score_table.setHorizontalHeaderLabels(["乐器", "原始文件名", "标准曲名", "新文件名", "分谱编号", "状态"])
        self.score_table.horizontalHeader().setSectionResizeMode(QHeaderView.Interactive)
        for column, width in enumerate((60, 245, 230, 245, 135, 90)):
            self.score_table.setColumnWidth(column, width)
        self.score_table.fit_default_columns({0: 50, 4: 105, 5: 90})
        self.score_table.verticalHeader().setDefaultSectionSize(24)
        self.score_table.setSelectionBehavior(QTableWidget.SelectRows)
        self.score_table.setSelectionMode(QAbstractItemView.ExtendedSelection)
        self.score_table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        self.score_table.cellDoubleClicked.connect(lambda *_: self.open_selected_pdf())
        self.report_table = ReadableTableWidget(0, 5)
        self.report_table.setHorizontalHeaderLabels(["分类", "曲名", "分谱类型数", "阻断预警", "目标文件夹预览"])
        self.report_table.horizontalHeader().setSectionResizeMode(QHeaderView.Interactive)
        for column, width in enumerate((60, 230, 75, 90, 260)):
            self.report_table.setColumnWidth(column, width)
        self.report_table.setMaximumHeight(150)
        self.report_table.verticalHeader().setDefaultSectionSize(24)
        self.import_summary_label = QLabel("尚未导入本次批次")
        self.guidance_label = QLabel("就绪" if self.db.get_setting("output_root") else "尚未选择输出文件夹")
        self.guidance_label.setWordWrap(True)

        right = self._build_right_panel()
        right.setMinimumWidth(300)

        archive_button = QPushButton("确认入库")
        archive_button.clicked.connect(self.commit_current_archive_plan)
        import_button = QToolButton()
        import_button.setText("导入乐谱")
        import_button.setPopupMode(QToolButton.InstantPopup)
        import_menu = QMenu(import_button)
        import_menu.addAction("导入乐谱文件", self.choose_import_files)
        import_menu.addAction("导入整个文件夹", self.choose_import_folder)
        import_button.setMenu(import_menu)
        open_pdf_button = QPushButton("打开选中PDF")
        open_pdf_button.clicked.connect(self.open_selected_pdf)
        correction_button = QPushButton("校对选中分谱")
        correction_button.setObjectName("correct_selected_scores")
        correction_button.clicked.connect(self.correct_selected_scores)
        catalog_button = QPushButton("查看曲谱库")
        catalog_button.clicked.connect(self.show_archived_catalog)
        commands = QHBoxLayout()
        for button in (import_button, open_pdf_button, correction_button, archive_button, catalog_button):
            commands.addWidget(button)

        center = QWidget()
        center_layout = QVBoxLayout(center)
        center_layout.addWidget(self.guidance_label)
        summary_bar = QHBoxLayout()
        summary_bar.addWidget(self.import_summary_label, 1)
        full_information = QPushButton("查看完整信息")
        full_information.setObjectName("score_full_information")
        full_information.clicked.connect(lambda: self.score_table.show_full_information())
        summary_bar.addWidget(full_information)
        center_layout.addLayout(summary_bar)
        center_layout.addWidget(self.score_table, 1)
        center_layout.addWidget(self.report_table)
        center_layout.addLayout(commands)

        splitter = QSplitter(Qt.Horizontal)
        splitter.addWidget(center)
        splitter.addWidget(right)
        splitter.setSizes([760, 340])
        layout.addWidget(splitter)
        for table in (self.report_table, self.warning_table):
            table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        return root

    def _build_right_panel(self) -> QWidget:
        right = QWidget()
        right_layout = QVBoxLayout(right)
        right_layout.addWidget(QLabel("预警与人工校对中心"))
        self.warning_table = ReadableTableWidget(0, 4)
        self.warning_table.setHorizontalHeaderLabels(["预警", "级别", "状态", "详情"])
        self.warning_table.horizontalHeader().setSectionResizeMode(QHeaderView.Interactive)
        for column, width in enumerate((185, 55, 95, 320)):
            self.warning_table.setColumnWidth(column, width)
        self.warning_table.setSelectionBehavior(QTableWidget.SelectRows)
        self.warning_table.setSelectionMode(QAbstractItemView.ExtendedSelection)
        self.warning_table.setMinimumHeight(130)
        self.warning_table.itemSelectionChanged.connect(self.focus_scores_for_selected_warning)
        right_layout.addWidget(self.warning_table, 1)
        warning_buttons = QWidget()
        warning_button_layout = QGridLayout(warning_buttons)
        warning_button_layout.setContentsMargins(0, 0, 0, 0)
        handled_button = QPushButton("处理选中预警")
        handled_button.setObjectName("process_selected_warning")
        handled_button.clicked.connect(self.correct_selected_warning)
        open_warning_button = QPushButton("打开相关PDF")
        open_warning_button.clicked.connect(self.open_selected_warning_pdf)
        warning_button_layout.addWidget(handled_button, 0, 0)
        warning_button_layout.addWidget(open_warning_button, 0, 1)
        right_layout.addWidget(warning_buttons)
        duplicate_button = QPushButton("查看重复文件并处理")
        duplicate_button.clicked.connect(lambda: self.show_duplicate_files())
        right_layout.addWidget(duplicate_button)
        self.warning_table.cellDoubleClicked.connect(self.open_warning_details)

        self.category_dialog = DictionaryDialog(self.db, "category", self)
        self.category_table = self.category_dialog.table
        self.category_code_input = self.category_dialog.code
        self.category_name_input = self.category_dialog.name
        self.category_active_input = self.category_dialog.active

        self.quick_correction_dialog = QDialog(self)
        self.quick_correction_dialog.setWindowTitle("分谱快速校对")
        quick_layout = QVBoxLayout(self.quick_correction_dialog)
        manual_form = QFormLayout()
        self.manual_instrument_input = QLineEdit()
        self.manual_instrument_input.setMaxLength(2)
        self.manual_title_input = QLineEdit()
        self.manual_category_input = QComboBox()
        manual_form.addRow("乐器编号", self.manual_instrument_input)
        manual_form.addRow("曲目分类", self.manual_category_input)
        manual_form.addRow("曲名", self.manual_title_input)
        quick_layout.addLayout(manual_form)
        manual_button = QPushButton("保存校对到草稿")
        manual_button.clicked.connect(self.apply_manual_entry_to_selected)
        quick_layout.addWidget(manual_button)

        right_layout.addWidget(QLabel("输出设置"))
        output_row = QWidget()
        output_layout = QHBoxLayout(output_row)
        output_layout.setContentsMargins(0, 0, 0, 0)
        self.output_path_input = QLineEdit()
        self.output_path_input.setReadOnly(True)
        self.output_path_input.setPlaceholderText("选择乐谱库输出文件夹")
        browse_button = QToolButton()
        browse_button.setIcon(self.style().standardIcon(QStyle.SP_DirOpenIcon))
        browse_button.setToolTip("选择输出文件夹")
        browse_button.clicked.connect(self.choose_output_path)
        output_layout.addWidget(self.output_path_input)
        output_layout.addWidget(browse_button)
        right_layout.addWidget(output_row)
        self.load_output_path()
        return right

    def refresh_categories(self) -> None:
        if self.category_table is None:
            return
        categories = self.db.list_categories()
        current_category = self.manual_category_input.currentData()
        self.manual_category_input.clear()
        self.manual_category_input.addItem("保持原分类", None)
        for category in categories:
            if category["active"]:
                self.manual_category_input.addItem(f"{category['code']} {category['name']}", category["code"])
        if current_category:
            self.manual_category_input.setCurrentIndex(max(0, self.manual_category_input.findData(current_category)))
        self.category_table.setRowCount(len(categories))
        for row_index, category in enumerate(categories):
            self.category_table.setItem(row_index, 0, QTableWidgetItem(category["code"]))
            self.category_table.setItem(row_index, 1, QTableWidgetItem(category["name"]))
            self.category_table.setItem(
                row_index,
                2,
                QTableWidgetItem("是" if category["active"] else "否"),
            )

    def _build_menus(self):
        self.menu_actions = {}
        def add(menu, key, title, callback):
            action = QAction(title, self)
            action.setObjectName(key)
            action.triggered.connect(lambda checked=False: callback())
            menu.addAction(action)
            self.menu_actions[key] = action
            return action
        files = self.menuBar().addMenu("文件")
        add(files, "import_files", "导入乐谱文件", self.choose_import_files)
        add(files, "import_folder", "导入整个文件夹", self.choose_import_folder)
        files.addSeparator()
        add(files, "output_location", "选择输出文件夹", self.choose_output_path)
        add(files, "open_output", "打开乐谱库文件夹", self.open_output_folder)
        program = self.menuBar().addMenu("节目单打包")
        add(program, "program_pack", "新建或载入节目单…", lambda: ProgramDialog(self.db, self).exec())
        team = self.menuBar().addMenu("团队发布")
        add(team, "team_publish", "选择分谱并发布给成员…", lambda: TeamPublishDialog(self.db, self).exec())
        parts = self.menuBar().addMenu("分谱处理")
        add(parts, "correct_parts", "校对选中分谱", self.correct_selected_scores)
        add(parts, "force_assign_library", "强制归入指定曲目并入库…", self.assign_selected_to_library)
        add(parts, "force_new_song", "强制新建独立曲目入库…", self.create_selected_song)
        add(parts, "quick_correction", "快速校对字段…", self.quick_correction_dialog.exec)
        add(parts, "open_selected_pdf", "打开选中PDF", self.open_selected_pdf)
        add(parts, "remove_selected", "移除选中分谱", self.remove_selected_import_items)
        parts.addSeparator()
        add(parts, "duplicate_compare", "查看重复文件并处理", self.show_duplicate_files)
        add(parts, "scan_completeness", "检查哪些曲子还缺乐器", self.scan_completeness)
        add(parts, "refresh_report", "查看曲目报告", self.refresh_song_reports)
        parts.addSeparator()
        add(parts, "archive_review", "选中分谱转入待审查", self.archive_to_review)
        add(parts, "store_unused_scores", "暂时无用：转存选中分谱", self.store_unused_scores)
        library = self.menuBar().addMenu("乐谱库")
        add(library, "archived_catalog", "查看已入库目录", self.show_archived_catalog)
        add(library, "archive_preview", "查看归档预览", self.preview_archive_plan)
        add(library, "archive_commit", "确认归档到乐谱库", self.commit_current_archive_plan)
        library.addSeparator()
        add(library, "pending_review", "查看待审查文件", self.show_pending_review)
        add(library, "show_unused_scores", "查看暂时无用乐谱", lambda: self.show_pending_review(unused=True))
        add(library, "open_duplicates", "打开已确认重复文件夹", self.open_confirmed_duplicate_folder)
        add(library, "reorganize_duplicates", "整理旧重复目录", self.reorganize_old_duplicates)
        settings = self.menuBar().addMenu("设置")
        add(settings, "category_management", "分类管理…", self.manage_categories)
        add(settings, "instrument_management", "乐器管理…", self.manage_instruments)
        add(settings, "archive_rules", "编号归档规则…", self.edit_archive_rules)
        add(settings, "android_rules_export", "导出 Android 识别字典…", self.export_android_rules)
        data = self.menuBar().addMenu("数据管理")
        add(data, "export_whole_library", "导出整库迁移包…", self.export_whole_library)
        add(data, "import_whole_library", "从整库迁移包恢复…", self.import_whole_library)
        add(data, "latest_migration_report", "查看最近迁移报告…", self.show_latest_migration_report)
        data.addSeparator()
        if self.db._numbering_version == 1:
            add(data,"expand_number_capacity","升级编号容量（独立副本）…",self.upgrade_number_capacity)
        add(data, "clear_draft", "清空当前草稿", self.clear_current_draft)
        data.addSeparator()
        add(data, "clear_all", "清空全部整理数据", self.clear_all_library)
        add(data, "restore_clear", "恢复上次清空", self.restore_last_clear)
        add(data, "open_clear_backup", "打开最近清空备份", self.open_last_clear_backup)
        self.menu_actions["warning_accept"] = QAction("仅确认提示无须修改", self)
        self.menu_actions["warning_accept"].triggered.connect(self.mark_selected_warning_accepted)
        self.menu_actions["warning_accept"].setToolTip("仅记录核对结论，不修改文件或入库")
        self.menu_actions["warning_full_information"] = QAction("查看预警完整详情", self)
        self.menu_actions["warning_full_information"].setObjectName("warning_full_information")
        self.menu_actions["warning_full_information"].triggered.connect(self.warning_table.show_full_information)
        warnings_menu = parts.addMenu("预警操作")
        warnings_menu.addAction(self.menu_actions["warning_accept"])
        warnings_menu.addAction(self.menu_actions["warning_full_information"])

    def _draft_context_menu(self, menu):
        menu.addSeparator()
        for key in ("open_selected_pdf", "correct_parts", "force_assign_library", "force_new_song", "duplicate_compare"):
            menu.addAction(self.menu_actions[key])
        menu.addSeparator()
        for key in ("store_unused_scores", "archive_review", "remove_selected"):
            menu.addAction(self.menu_actions[key])

    def _warning_context_menu(self, menu):
        menu.addSeparator()
        menu.addAction("处理选中预警", self.correct_selected_warning)
        menu.addAction("打开相关PDF", self.open_selected_warning_pdf)
        menu.addAction(self.menu_actions["duplicate_compare"])
        menu.addAction(self.menu_actions["warning_accept"])

    def export_android_rules(self):
        filename, _ = QFileDialog.getSaveFileName(self, "导出 Android 识别字典", "android-score-rules.json", "JSON (*.json)")
        if not filename:
            return
        try:
            export_naming_rules(self.db.path, Path(filename))
        except Exception as exc:
            QMessageBox.warning(self, "字典未导出", str(exc))
            return
        self.statusBar().showMessage("Android 识别字典已导出，原库不变")
        QMessageBox.information(self, "字典已导出", "将这个 JSON 文件传到平板，在导入曲谱页选择“加载电脑端字典 JSON”。只包含启用的乐器和分类，不包含 PDF 或个人批注。")

    def manage_categories(self):
        self.category_dialog.refresh()
        self.category_dialog.exec()
        self.refresh_categories()
        self._refresh_after_settings()

    def manage_instruments(self):
        dialog = DictionaryDialog(self.db, "instrument", self)
        dialog.exec()
        dialog.deleteLater()
        self._refresh_after_settings()

    def _refresh_after_settings(self):
        if self.current_batch_id is not None:
            self.refresh_warnings(self.current_batch_id)
            self.refresh_import_items()
            self.refresh_song_reports()

    def assign_selected_to_library(self):
        ids = {int(self.score_table.item(row, 1).data(Qt.UserRole))
               for row in {item.row() for item in self.score_table.selectedItems()}}
        if not ids or self.current_batch_id is None:
            QMessageBox.information(self, "请选择分谱", "请先选择要归入曲目的草稿分谱")
            return
        batch_id = self.current_batch_id
        service = CorrectionService(self.db)
        try:
            candidates = [row for row in service.matching_candidates(batch_id, ids) if row["kind"] == "archived"]
            if not candidates:
                QMessageBox.information(self, "暂无标准曲目", "请先校对并入库一首标准曲目，再将其他分谱归入该曲目")
                return
            chosen = self.choose_correction_candidate(candidates, self, library_only=True)
            if chosen is None:
                return
            plan = service.build_plan(batch_id, ids, target_song_id=chosen["id"])
            accepted, approved = self.review_corrected_archive(plan, force=True)
            if not accepted:
                return
            service.apply(plan)
            self._refresh_after_settings()
            if not self._commit_archive(ids, inline_failure=True, force=True, approved_plans=approved) and self.last_archive_failure:
                self.guidance_label.setText("归属已保存到草稿，尚未入库。")
                self.show_archive_failure_details(self.last_archive_failure, self)
        except (ValueError, OSError, sqlite3.Error) as exc:
            QMessageBox.warning(self, "未能归入曲目", str(exc))

    def create_selected_song(self):
        ids = {int(self.score_table.item(row, 1).data(Qt.UserRole))
               for row in {item.row() for item in self.score_table.selectedItems()}}
        self.show_correction_dialog(ids, new_song=True, force=True)

    def edit_archive_rules(self) -> None:
        if ArchiveRulesDialog(self.db, self).exec() == QDialog.Accepted:
            if self.current_batch_id is not None:
                GroupingService(self.db).refresh_warnings(self.current_batch_id)
                self.refresh_import_items()
                self.refresh_warnings(self.current_batch_id)
                self.refresh_song_reports()

    def save_category(self) -> None:
        assert self.category_code_input is not None
        assert self.category_name_input is not None
        assert self.category_active_input is not None
        try:
            self.db.upsert_category(
                self.category_code_input.text(),
                self.category_name_input.text(),
                self.category_active_input.isChecked(),
            )
        except ValueError as exc:
            QMessageBox.warning(self, "分类无法保存", str(exc))
            return
        self.refresh_categories()

    def _load_selected_category(self) -> None:
        if self.category_table is None or self.category_code_input is None:
            return
        selected = self.category_table.selectedItems()
        if not selected:
            return
        row = selected[0].row()
        code = self.category_table.item(row, 0).text()
        name = self.category_table.item(row, 1).text()
        active = self.category_table.item(row, 2).text() == "是"
        self.category_code_input.setText(code)
        assert self.category_name_input is not None
        assert self.category_active_input is not None
        self.category_name_input.setText(name)
        self.category_active_input.setChecked(active)

    def load_output_path(self) -> None:
        if self.output_path_input is None:
            return
        self.output_path_input.setText(self.db.get_setting("output_root", "") or "")
        self.output_path_input.setToolTip(self.output_path_input.text())

    def save_output_path(self) -> None:
        assert self.output_path_input is not None
        value = self.output_path_input.text().strip()
        if not value:
            QMessageBox.warning(self, "输出路径无法保存", "输出路径不能为空")
            return
        self.db.set_setting("output_root", value)
        if self.guidance_label is not None:
            self.guidance_label.setText("输出文件夹已保存。")

    def choose_output_path(self) -> None:
        if self.db._numbering_version == 2:
            QMessageBox.information(self,"独立曲库目录","扩位曲库使用已核验的独立输出目录；迁移目录请先备份并关闭程序。")
            return
        assert self.output_path_input is not None
        path = QFileDialog.getExistingDirectory(self, "选择乐谱库输出文件夹")
        if path:
            self.output_path_input.setText(path)
            self.output_path_input.setToolTip(path)
            self.save_output_path()

    def open_output_folder(self) -> None:
        path = Path(self.db.get_setting("output_root", "") or "")
        if not self.db.get_setting("output_root") or not path.is_dir():
            QMessageBox.warning(self, "无法打开", "请先选择一个存在的乐谱库文件夹")
            return
        QDesktopServices.openUrl(QUrl.fromLocalFile(str(path)))

    def open_confirmed_duplicate_folder(self) -> None:
        output = self.db.get_setting("output_root", "")
        path = Path(output or "") / "已确认重复"
        if not output or not path.is_dir():
            QMessageBox.information(self, "暂无已确认重复文件", "确认重复并转存后，文件会保存在这里")
            return
        QDesktopServices.openUrl(QUrl.fromLocalFile(str(path.resolve())))

    def reorganize_old_duplicates(self):
        output = self.db.get_setting("output_root", "")
        if not output:
            QMessageBox.warning(self, "无法整理", "请先选择输出文件夹")
            return
        try:
            service = DuplicateLayoutStore(self.db, output)
            plan = service.legacy_plan()
            if not plan["entries"]:
                QMessageBox.information(self, "无需整理", "没有需要转换的旧重复批次目录；新重复文件已按曲名存放。")
                return
            dialog = QDialog(self)
            dialog.setWindowTitle("预览旧重复目录整理")
            dialog.resize(1100, 520)
            layout = QVBoxLayout(dialog)
            label = QLabel(f"将 {len(plan['packages'])} 个旧批次、{len(plan['entries'])} 份PDF改为按曲名直接存放。\n先备份数据库及旧目录到“修改备份”，原始PDF和正式乐谱库不变。")
            label.setWordWrap(True)
            layout.addWidget(label)
            table = ReadableTableWidget(len(plan["entries"]), 4)
            table.setObjectName("duplicate_layout_preview")
            table.setHorizontalHeaderLabels(["曲目文件夹", "旧文件名", "新文件名", "新位置"])
            table.setEditTriggers(QAbstractItemView.NoEditTriggers)
            for column, width in enumerate((180, 240, 300, 330)):
                table.setColumnWidth(column, width)
            for row, item in enumerate(plan["entries"]):
                relative = Path(item["relative_path"])
                for column, value in enumerate((relative.parent.name, Path(item["copy_source"]).name,
                                                 relative.name, str(service.root / relative))):
                    table.setItem(row, column, QTableWidgetItem(value))
            table.cellDoubleClicked.connect(lambda row, _: self.open_pdf_path(Path(plan["entries"][row]["copy_source"])))
            layout.addWidget(table)
            commands = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
            commands.button(QDialogButtonBox.Ok).setText("备份并确认整理")
            commands.button(QDialogButtonBox.Cancel).setText("取消")
            commands.accepted.connect(dialog.accept)
            commands.rejected.connect(dialog.reject)
            layout.addWidget(commands)
            accepted = dialog.exec() == QDialog.Accepted
            dialog.deleteLater()
            if not accepted:
                return
            backup = service.migrate(plan)
            QMessageBox.information(self, "重复目录整理完成", f"已按曲名整理 {len(plan['entries'])} 份PDF，直接存放并标注重复序号。\n旧目录与数据库备份：{backup}")
        except (ValueError, OSError, sqlite3.Error, KeyError, TypeError) as exc:
            QMessageBox.warning(self, "重复目录未完成整理", str(exc))

    def choose_import_files(self) -> None:
        files, _ = QFileDialog.getOpenFileNames(
            self,
            "选择要导入的乐谱文件",
            "",
            "PDF 文件 (*.pdf);;所有文件 (*.*)",
        )
        if not files:
            return
        self.import_score_paths([Path(file) for file in files])

    def import_score_paths(self, paths):
        try:
            batch_id = self.active_draft_batch_id()
            previous_ids = {int(item["id"]) for item in self.db.list_import_items(batch_id)}
            ImportService(self.db).import_paths(paths, batch_id=batch_id)
        except OSError as exc:
            QMessageBox.warning(self, "导入失败", str(exc))
            return
        self.show_import_summary(batch_id)
        self.show_guidance_after_import()
        self.current_batch_id = batch_id
        self.refresh_draft_view()
        new_ids = {int(item["id"]) for item in self.db.list_import_items(batch_id)} - previous_ids
        self.resolve_archived_duplicates(new_ids)

    def choose_import_folder(self) -> None:
        folder = QFileDialog.getExistingDirectory(self, "选择要导入的乐谱文件夹")
        if not folder:
            return
        self.import_score_paths([Path(folder)])

    def restore_current_batch(self) -> None:
        batch_id = self.db.latest_draft_batch_id()
        if batch_id is None:
            return
        self.current_batch_id = batch_id
        self.show_import_summary(batch_id)
        if self.guidance_label is not None:
            self.guidance_label.setText(
                "已载入最近的草稿。"
            )
        self.refresh_draft_view()

    def active_draft_batch_id(self) -> int:
        draft_batch_id = self.db.latest_draft_batch_id()
        if self.current_batch_id is not None and self.current_batch_id == draft_batch_id:
            return self.current_batch_id
        batch_id = draft_batch_id
        if batch_id is None:
            batch_id = self.db.create_import_batch()
        self.current_batch_id = batch_id
        return batch_id

    def clear_current_draft(self) -> None:
        batch_id = self.db.latest_draft_batch_id()
        if batch_id is None:
            QMessageBox.information(self, "没有草稿", "当前没有需要清空的草稿")
            return
        summary = self.db.import_batch_summary(batch_id)
        answer = QMessageBox.question(
            self,
            "确认清空草稿",
            f"将清空当前草稿中的 {summary['total']} 份导入记录和相关预警。原始 PDF 文件不会删除，已正式归档的乐谱库也不会受影响。确定清空吗？",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
            QMessageBox.StandardButton.No,
        )
        if answer != QMessageBox.StandardButton.Yes:
            return
        try:
            removed_count = self.db.clear_draft_batch(batch_id)
        except ValueError as exc:
            QMessageBox.warning(self, "无法清空草稿", str(exc))
            return
        self.current_batch_id = None
        self.refresh_import_items()
        if self.warning_table is not None:
            self.warning_table.setRowCount(0)
        if self.report_table is not None:
            self.report_table.setRowCount(0)
        if self.import_summary_label is not None:
            self.import_summary_label.setText("草稿箱已清空")
        if self.guidance_label is not None:
            self.guidance_label.setText("草稿已清空。可以重新导入乐谱文件。")
        QMessageBox.information(self, "草稿已清空", f"已清空 {removed_count} 份草稿记录")

    def show_clear_all_confirmation(self, plan) -> bool:
        dialog = QDialog(self)
        dialog.setWindowTitle("清空全部整理数据")
        dialog.resize(680, 420)
        layout = QVBoxLayout(dialog)
        counts = dict(plan.counts)
        label = QLabel(f"已入库 {counts['song']} 首 / {counts['score']} 份分谱；导入记录 {counts['import_item']} 份；预警 {counts['warning']} 条")
        label.setWordWrap(True)
        layout.addWidget(label)
        scope = QLabel("清空草稿、已入库目录、预警、导入历史和整理结果。原始PDF、分类与乐器设置保留。")
        scope.setWordWrap(True)
        layout.addWidget(scope)
        paths = ReadableTableWidget(len(plan.folders), 2)
        paths.setHorizontalHeaderLabels(["转入清空备份的目录", "原位置"])
        paths.setEditTriggers(QAbstractItemView.NoEditTriggers)
        paths.horizontalHeader().setSectionResizeMode(QHeaderView.Stretch)
        for row, folder in enumerate(plan.folders):
            paths.setItem(row, 0, QTableWidgetItem(folder.name))
            item = QTableWidgetItem(str(folder))
            item.setToolTip(str(folder))
            paths.setItem(row, 1, item)
        layout.addWidget(paths)
        backup = QLineEdit(str(plan.backup_parent))
        backup.setReadOnly(True)
        backup.setToolTip(backup.text())
        layout.addWidget(QLabel("备份位置"))
        layout.addWidget(backup)
        consent = QCheckBox("我确认清空上述整理数据，新库从001重新开始")
        layout.addWidget(consent)
        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        confirm = buttons.button(QDialogButtonBox.Ok)
        confirm.setText("备份并清空全部")
        confirm.setEnabled(False)
        consent.toggled.connect(confirm.setEnabled)
        buttons.button(QDialogButtonBox.Cancel).setText("取消")
        buttons.accepted.connect(dialog.accept)
        buttons.rejected.connect(dialog.reject)
        layout.addWidget(buttons)
        accepted = dialog.exec() == QDialog.Accepted
        dialog.deleteLater()
        return accepted

    def clear_all_library(self) -> None:
        try:
            service = ResetService(self.db)
            plan = service.build_plan()
            if not any(count for _, count in plan.counts) and not plan.folders:
                QMessageBox.information(self, "数据已经为空", "当前没有需要清空的整理数据")
                return
            if not self.show_clear_all_confirmation(plan):
                return
            backup = service.clear(plan)
        except (ValueError, OSError, sqlite3.Error) as exc:
            QMessageBox.warning(self, "全部清空未完成", str(exc))
            return
        self.current_batch_id = None
        self.warning_scan_completeness = False
        self.score_table.setRowCount(0)
        self.report_table.setRowCount(0)
        self.warning_table.setRowCount(0)
        self.manual_title_input.clear()
        self.manual_instrument_input.clear()
        self.manual_category_input.setCurrentIndex(0)
        self.import_summary_label.setText("全部整理数据已清空")
        self.guidance_label.setText("旧库已保存到清空备份。可以重新导入乐谱，新库编号从001开始。")
        QMessageBox.information(self, "全部清空完成", f"旧记录和整理结果已备份，原始PDF保留。\n备份：{backup}")

    def restore_last_clear(self) -> None:
        backup = self.db.get_setting("last_reset_backup", "")
        if not backup:
            QMessageBox.information(self, "没有清空备份", "还没有可以恢复的全部清空记录")
            return
        answer = QMessageBox.question(self, "恢复上次清空", "恢复清空前的记录、整理目录和设置。当前不能有新整理数据，不能覆盖新目录。确定恢复吗？",
                                      QMessageBox.Yes | QMessageBox.No, QMessageBox.No)
        if answer != QMessageBox.Yes:
            return
        try:
            ResetService(self.db).restore(backup)
        except (ValueError, OSError, sqlite3.Error) as exc:
            QMessageBox.warning(self, "恢复未完成", str(exc))
            return
        self.current_batch_id = None
        self.warning_scan_completeness = False
        self.refresh_categories()
        self.load_output_path()
        self.restore_current_batch()
        self.refresh_import_items()
        if self.current_batch_id is None:
            self.guidance_label.setText("已恢复清空前的乐谱库和整理目录。")
            self.import_summary_label.setText("已恢复原乐谱库")
        QMessageBox.information(self, "恢复完成", "清空前的记录与整理目录已恢复")

    def open_last_clear_backup(self) -> None:
        backup = self.db.get_setting("last_reset_backup", "")
        if not backup or not Path(backup).is_dir():
            QMessageBox.information(self, "没有清空备份", "当前没有可打开的清空备份")
            return
        QDesktopServices.openUrl(QUrl.fromLocalFile(str(Path(backup).resolve())))

    def remove_selected_import_items(self) -> None:
        if self.current_batch_id is None:
            QMessageBox.warning(self, "无法移除", "当前没有草稿")
            return
        assert self.score_table is not None
        rows = sorted({item.row() for item in self.score_table.selectedItems()})
        if not rows:
            QMessageBox.warning(self, "无法移除", "请先在上方分谱清单里选择要移除的分谱")
            return
        item_ids = [int(self.score_table.item(row, 1).data(Qt.UserRole)) for row in rows]
        answer = QMessageBox.question(
            self,
            "确认移除分谱",
            f"将从草稿中移除选中的 {len(item_ids)} 份分谱记录。原始 PDF 文件不会删除。确定移除吗？",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
            QMessageBox.StandardButton.No,
        )
        if answer != QMessageBox.StandardButton.Yes:
            return
        try:
            remaining_count = self.db.remove_import_items_from_draft(self.current_batch_id, item_ids)
        except ValueError as exc:
            QMessageBox.warning(self, "无法移除", str(exc))
            return
        if remaining_count == 0:
            self.current_batch_id = None
        self.refresh_import_items()
        if self.current_batch_id is None:
            if self.warning_table is not None:
                self.warning_table.setRowCount(0)
            if self.report_table is not None:
                self.report_table.setRowCount(0)
            if self.import_summary_label is not None:
                self.import_summary_label.setText("草稿箱已清空")
            if self.guidance_label is not None:
                self.guidance_label.setText("选中分谱已移除，草稿已清空。")
            return
        self.refresh_warnings(self.current_batch_id)
        self.refresh_song_reports()
        self.show_import_summary(self.current_batch_id)
        if self.guidance_label is not None:
            self.guidance_label.setText("选中分谱已从草稿移除。原始 PDF 文件没有删除。")

    def refresh_import_items(self) -> None:
        if self.score_table is None:
            return
        if self.current_batch_id is None:
            self.score_table.setRowCount(0)
            return
        items = self.db.list_import_items(self.current_batch_id)
        view_context = self._draft_view_context(items)
        self.score_table.setRowCount(len(items))
        for row_index, item in enumerate(items):
            fields = json.loads(item["parsed_fields"] or "{}")
            self.score_table.setItem(row_index, 0, QTableWidgetItem(str(fields.get("instrument_code") or "")))
            self.score_table.setItem(
                row_index,
                1,
                QTableWidgetItem(Path(item["source_path"]).name),
            )
            self.score_table.setItem(row_index, 2, QTableWidgetItem(str(fields.get("title") or "")))
            self.score_table.setItem(row_index, 3, QTableWidgetItem(self._preview_name(fields, view_context)))
            self.score_table.setItem(row_index, 4, QTableWidgetItem(self._score_number(fields, view_context)))
            self.score_table.setItem(row_index, 5, QTableWidgetItem(self._draft_status_label(item, fields, view_context)))
            id_item = self.score_table.item(row_index, 1)
            id_item.setData(Qt.UserRole, int(item["id"]))
            id_item.setToolTip(str(item["source_path"]))

    def _draft_view_context(self, items) -> dict[str, object]:
        with self.db.transaction(reuse=True):
            return self._build_draft_view_context(items)

    def _build_draft_view_context(self, items) -> dict[str, object]:
        group_instruments: dict[tuple[str, str], set[str]] = {}
        blocking_groups: set[tuple[str, str]] = set()
        blocked_paths: set[str] = set()
        if self.current_batch_id is not None:
            for warning in self.db.list_warnings(self.current_batch_id):
                if warning["severity"] != "blocking" or warning["state"] != "待处理":
                    continue
                if warning["code"] == "insufficient_instruments":
                    continue
                details = json.loads(warning["details"] or "{}")
                if warning["code"] != "insufficient_instruments":
                    blocked_paths.update(details.get("source_paths") or [])
                category_code = details.get("category_code")
                title = details.get("title")
                if category_code and title and not details.get("source_paths"):
                    blocking_groups.add((str(category_code), str(title)))
        for item in items:
            if item["status"] != "recognized":
                continue
            fields = json.loads(item["parsed_fields"] or "{}")
            category_code = fields.get("category_code")
            title = fields.get("title")
            instrument_code = fields.get("instrument_code")
            if not category_code or not title or not instrument_code:
                continue
            key = (str(category_code), str(title))
            group_instruments.setdefault(key, set()).add(str(instrument_code))
        eligible_by_group = {}
        existing_by_group = {}
        for key in sorted(group_instruments):
            existing = self.db.find_archived_song(*key)
            existing_by_group[key] = existing
            eligible_by_group[key] = self.db.archive_eligible_codes(key[0], group_instruments[key], int(existing["id"]) if existing else None)
            if existing:
                group_instruments[key] |= self.db.archived_instrument_codes(int(existing["id"]))
        for item in items:
            fields = json.loads(item["parsed_fields"] or "{}")
            key = (str(fields.get("category_code") or ""), str(fields.get("title") or ""))
            if str(item["source_path"]) in blocked_paths and fields.get("instrument_code") in eligible_by_group.get(key, set()):
                blocking_groups.add(key)
        sequences = {}
        try:
            numbering_service = ArchiveService(self.db, ".")
            requested = numbering_service.requested_sequences(self.current_batch_id)
            reserved = numbering_service.reserved_sequences(requested, batch_id=self.current_batch_id)
            for key in sorted(group_instruments):
                if key in blocking_groups or not eligible_by_group[key] or existing_by_group[key]:
                    continue
                if key in requested:
                    sequences[key] = requested[key]
                else:
                    try:
                        sequences[key] = next_global_sequence(list(reserved), naming_version=self.db._numbering_version).value
                        reserved.add(sequences[key])
                    except SequenceExhaustedError:
                        blocking_groups.add(key)
        except ValueError:
            blocking_groups.update(group_instruments)
        return {"group_instruments": group_instruments, "blocking_groups": blocking_groups, "seq_by_group": sequences,
                "eligible_by_group": eligible_by_group, "blocked_paths": blocked_paths, "existing_by_group": existing_by_group}

    def _preview_name(self, fields: dict[str, object], view_context: dict[str, object]) -> str:
        category_code = fields.get("category_code")
        instrument_code = fields.get("instrument_code")
        title = fields.get("title")
        if not category_code or not instrument_code or not title:
            return ""
        seq = self._display_sequence(fields, view_context)
        if seq.isascii() and seq.isdecimal():
            try:
                return format_score_filename(str(instrument_code), str(category_code), seq, str(title), naming_version=self.db._numbering_version)
            except ValueError:
                return "编号无效，待人工核对"
        return f"{instrument_code}{category_code}{seq}{title}.pdf"

    def _score_number(self, fields: dict[str, object], view_context: dict[str, object]) -> str:
        category_code = fields.get("category_code")
        instrument_code = fields.get("instrument_code")
        if not category_code or not instrument_code:
            return ""
        seq = self._display_sequence(fields, view_context)
        if seq.isascii() and seq.isdecimal():
            try:
                return format_score_number(str(instrument_code), str(category_code), seq, naming_version=self.db._numbering_version)
            except ValueError:
                return "编号无效，待人工核对"
        return f"{instrument_code}{category_code}{seq}"

    def _display_sequence(self, fields: dict[str, object], view_context: dict[str, object]) -> str:
        category_code = fields.get("category_code")
        title = fields.get("title")
        if not category_code or not title:
            return "待分配"
        key = (str(category_code), str(title))
        existing = (view_context["existing_by_group"].get(key) if "existing_by_group" in view_context
                    else self.db.find_archived_song(str(category_code), str(title)))
        if existing is not None:
            return str(existing["seq"])
        group_instruments = view_context["group_instruments"]
        blocking_groups = view_context["blocking_groups"]
        if key in blocking_groups:
            return "待处理"
        if fields.get("seq"):
            return str(fields["seq"])
        if fields.get("instrument_code") not in view_context["eligible_by_group"].get(key, set()):
            return "待补齐"
        return self._candidate_sequence_for_group(key, view_context)

    def _candidate_sequence_for_group(
        self,
        key: tuple[str, str],
        view_context: dict[str, object],
    ) -> str:
        seq_by_group = view_context.setdefault("seq_by_group", {})
        if key in seq_by_group:
            return str(seq_by_group[key])
        service = ArchiveService(self.db, ".")
        requested = service.requested_sequences(self.current_batch_id) if self.current_batch_id is not None else {}
        reserved = service.reserved_sequences(requested, batch_id=self.current_batch_id)
        reserved.update(seq_by_group.values())
        try:
            candidate = next_global_sequence(list(reserved), naming_version=self.db._numbering_version).value
        except SequenceExhaustedError:
            return "编号已满"
        seq_by_group[key] = candidate
        return candidate

    def _draft_status_label(self, item, fields: dict[str, object], view_context: dict[str, object]) -> str:
        if item["status"] == "migration_pending":
            return "迁移待处理（见迁移报告）"
        if item["status"] == "needs_manual_entry":
            return "待补录"
        if item["status"] == "skipped":
            return "已跳过"
        if str(item["source_path"]) in view_context["blocked_paths"]:
            return "待处理"
        category_code = fields.get("category_code")
        title = fields.get("title")
        if not category_code or not title:
            return "待补录"
        key = (str(category_code), str(title))
        blocking_groups = view_context["blocking_groups"]
        if key in blocking_groups:
            return "待处理"
        group_instruments = view_context["group_instruments"]
        if fields.get("instrument_code") not in view_context["eligible_by_group"].get(key, set()):
            return "待补齐"
        return "可单份归档" if self.db.instrument_archive_modes().get(fields.get("instrument_code")) == "instrument_only" else "可归档"

    def apply_manual_entry_to_selected(self) -> None:
        assert self.score_table is not None
        assert self.manual_instrument_input is not None
        assert self.category_code_input is not None
        assert self.manual_title_input is not None
        rows = sorted({item.row() for item in self.score_table.selectedItems()})
        if not rows:
            QMessageBox.warning(self, "无法补录", "请先选择要补录的分谱")
            return
        instrument_code = self.manual_instrument_input.text().strip() or None
        category_code = self.manual_category_input.currentData()
        title = self.manual_title_input.text().strip() or None
        try:
            with self.db.transaction():
                for row in rows:
                    id_item = self.score_table.item(row, 1)
                    item_id = int(id_item.data(Qt.UserRole))
                    self.db.update_import_item_manual_fields(
                        item_id,
                        instrument_code=instrument_code,
                        category_code=category_code,
                        title=title,
                    )
        except (ValueError, sqlite3.Error) as exc:
            QMessageBox.warning(self, "补录失败", str(exc))
            return
        self.refresh_import_items()
        if self.current_batch_id is not None:
            self.refresh_warnings(self.current_batch_id)
            self.refresh_song_reports()
            self.guidance_label.setText("校对已保存到草稿，尚未入库；可继续选择目标曲目并预览入库。")

    def correct_selected_scores(self):
        ids = {int(self.score_table.item(row, 1).data(Qt.UserRole))
               for row in {item.row() for item in self.score_table.selectedItems()}}
        self.show_correction_dialog(ids)

    def correct_selected_warning(self):
        selected = {item.row() for item in self.warning_table.selectedItems()}
        paths = {str(path) for row in selected
                 for path in (self.warning_table.item(row, 0).data(Qt.UserRole + 1) or [])}
        ids = {int(item["id"]) for item in self.db.list_import_items(self.current_batch_id)
               if str(item["source_path"]) in paths} if self.current_batch_id else set()
        warning_ids = {int(self.warning_table.item(row, 0).data(Qt.UserRole)) for row in selected}
        suggestions = {json.loads(warning["details"]).get("suggested_title")
                       for warning in self.db.list_warnings(self.current_batch_id) if int(warning["id"]) in warning_ids}
        self.show_correction_dialog(ids, suggested_title=next(iter(suggestions)) if len(suggestions) == 1 else None)

    def show_correction_dialog(self, item_ids, suggested_title=None, new_song=False, force=False):
        if self.current_batch_id is None or not item_ids:
            QMessageBox.information(self, "请选择分谱", "请先选择草稿分谱或有关联分谱的预警")
            return
        items = [item for item in self.db.list_import_items(self.current_batch_id) if item["id"] in item_ids]
        if len(items) != len(item_ids):
            QMessageBox.warning(self, "草稿已变化", "请重新选择分谱")
            return
        dialog = QDialog(self)
        dialog.setWindowTitle("新建独立曲目与入库" if new_song else "分谱校对与入库")
        dialog.resize(1100, 650)
        layout = QVBoxLayout(dialog)
        count_label = QLabel(f"本次校对 {len(items)} 份草稿分谱")
        count_label.setObjectName("correction_count")
        layout.addWidget(count_label)
        form = QFormLayout()
        destination = QComboBox()
        destination.setObjectName("correction_destination")
        destination.addItem("按校对后的曲名和分类归组", None)
        destination.addItem("新建独立曲目（分配新编号）", "new_song")
        seen = set()
        for score in self.db.list_archived_scores():
            if score["song_id"] not in seen:
                destination.addItem(f"补入 {score['seq']} / 分类{score['category_code']} / {score['display_title']}", int(score["song_id"]))
                seen.add(score["song_id"])
        destination.setEditable(True)
        destination.setInsertPolicy(QComboBox.NoInsert)
        destination.completer().setFilterMode(Qt.MatchContains)
        category = QComboBox()
        category.setObjectName("correction_category")
        category.addItem("保持各自分类", None)
        for row in self.db.list_categories():
            if row["active"]:
                category.addItem(f"{row['code']} {row['name']}", row["code"])
        fields = [json.loads(item["parsed_fields"] or "{}") for item in items]
        with self.db.session() as connection:
            instruments = [dict(row) for row in connection.execute("SELECT code, name FROM instrument ORDER BY code")]
        instrument_names = {row["code"]: row["name"] for row in instruments}
        titles = {field.get("title") for field in fields}
        proposed = {suggest_title_without_instrument(field.get("title") or "", instrument_names.get(field.get("instrument_code"), "")) or field.get("title") for field in fields}
        if suggested_title is None and len(proposed) == 1 and None not in proposed and proposed != titles:
            suggested_title = next(iter(proposed))
        title = QLineEdit(suggested_title or (str(next(iter(titles))) if len(titles) == 1 and None not in titles else ""))
        title.setObjectName("correction_title")
        title.setPlaceholderText("保持各自曲名")
        form.addRow("归入曲目", destination)
        form.addRow("选中分谱分类", category)
        form.addRow("选中分谱曲名", title)
        layout.addLayout(form)
        previous = [title.text(), category.currentData()]
        target_active = [False]
        def update_destination():
            data = destination.currentData()
            if data == "new_song":
                target = None
            elif isinstance(data, dict):
                draft = self.db.get_import_item(data["draft"])
                field = json.loads(draft["parsed_fields"] or "{}") if draft else {}
                target = {"display_title": field.get("title", ""), "category_code": field.get("category_code")} if draft else None
            else:
                target = self.db.get_archived_song(data) if data is not None else None
            if target is not None:
                if not target_active[0]:
                    previous[:] = [title.text(), category.currentData()]
                title.setText(target["display_title"])
                category.setCurrentIndex(category.findData(target["category_code"]))
            elif target_active[0]:
                title.setText(previous[0])
                category.setCurrentIndex(category.findData(previous[1]))
            target_active[0] = target is not None
            title.setReadOnly(target is not None)
            category.setEnabled(target is None)
        destination.currentIndexChanged.connect(update_destination)
        table = ReadableTableWidget(len(items), 4)
        table.setObjectName("correction_parts")
        table.setHorizontalHeaderLabels(["乐器", "源文件名", "当前曲名 / 分类 / 序号", "源文件路径"])
        table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        table.setSelectionBehavior(QAbstractItemView.SelectRows)
        table.setSelectionMode(QAbstractItemView.ExtendedSelection)
        table.setWordWrap(False)
        table.setTextElideMode(Qt.ElideMiddle)
        for col, width in enumerate((155, 290, 270, 240)):
            table.setColumnWidth(col, width)
        instrument_inputs = {}
        def select_instrument_row(row):
            if row not in {cell.row() for cell in table.selectedItems()}:
                table.selectRow(row)
        def add_item_row(index, item):
            field = json.loads(item["parsed_fields"] or "{}")
            instrument = QComboBox()
            instrument.setObjectName(f"correction_instrument_{item['id']}")
            instrument.addItem("请选择乐器", None)
            for row in instruments:
                instrument.addItem(f"{row['code']} {row['name']}", row["code"])
            instrument.setCurrentIndex(max(0, instrument.findData(field.get("instrument_code"))))
            instrument.currentIndexChanged.connect(lambda _, row=index: select_instrument_row(row))
            instrument_inputs[int(item["id"])] = instrument
            table.setCellWidget(index, 0, instrument)
            for col, value in enumerate((Path(item["source_path"]).name,
                                         f"{field.get('title') or '待命名'} / {field.get('category_code') or '待分类'} / {field.get('seq') or '待分配'}",
                                         str(item["source_path"])), 1):
                cell = QTableWidgetItem(value)
                cell.setToolTip(value)
                table.setItem(index, col, cell)
        for index, item in enumerate(items):
            add_item_row(index, item)
        layout.addWidget(table)
        scope_label = QLabel()
        scope_label.setObjectName("correction_scope")
        scope_label.setWordWrap(True)
        layout.addWidget(scope_label)
        selection_commands = QHBoxLayout()
        select_all = QPushButton("选择全部分谱")
        select_all.setObjectName("correction_select_all")
        select_all.clicked.connect(table.selectAll)
        selection_commands.addWidget(select_all)
        candidate_button = QPushButton("查找疑似对应曲目")
        candidate_button.setObjectName("correction_find_candidate")
        selection_commands.addWidget(candidate_button)
        library_name_button = QPushButton("统一为库内名称")
        library_name_button.setObjectName("correction_use_library_name")
        selection_commands.addWidget(library_name_button)
        layout.addLayout(selection_commands)
        def selected_ids():
            return {int(items[row]["id"]) for row in {cell.row() for cell in table.selectedItems()}}
        active_scope = [frozenset()]
        pending_edits = {}
        def load_selection():
            chosen = selected_ids()
            if active_scope[0]:
                pending_edits[active_scope[0]] = (title.text(), category.currentData(), destination.currentData(), destination.currentText(),
                                                destination.itemText(destination.currentIndex()))
            active_scope[0] = frozenset(chosen)
            destination.blockSignals(True)
            for index in range(destination.count() - 1, 0, -1):
                if isinstance(destination.itemData(index), dict):
                    destination.removeItem(index)
            destination.setCurrentIndex(0)
            destination.setEditText(destination.itemText(0))
            destination.blockSignals(False)
            current = [json.loads(self.db.get_import_item(item_id)["parsed_fields"] or "{}") for item_id in sorted(chosen)]
            names = {field.get("title") for field in current}
            codes = {field.get("category_code") for field in current}
            value = str(next(iter(names))) if len(names) == 1 and None not in names else ""
            if suggested_title and chosen == set(item_ids) and len(chosen) > 1:
                value = suggested_title
            title.setReadOnly(False)
            title.setText(value)
            category.setEnabled(True)
            code = next(iter(codes)) if len(codes) == 1 else None
            category.setCurrentIndex(max(0, category.findData(code)))
            previous[:] = [title.text(), category.currentData()]
            target_active[0] = False
            cached = pending_edits.get(active_scope[0])
            if cached is not None:
                text, code, data, target_text, target_label = cached
                title.setText(text)
                category.setCurrentIndex(max(0, category.findData(code)))
                previous[:] = [text, category.currentData()]
                if isinstance(data, dict):
                    destination.addItem(target_label, data)
                    destination.setCurrentIndex(destination.count() - 1)
                elif data is not None:
                    destination.setCurrentIndex(destination.findData(data))
                destination.setEditText(target_text)
            else:
                target_ids = {field.get("target_song_id") for field in current}
                if new_song or (current and all(field.get("new_song_confirmation") == {"category_code": field.get("category_code"), "title": field.get("title")} for field in current)):
                    destination.setCurrentIndex(destination.findData("new_song"))
                elif len(target_ids) == 1 and None not in target_ids:
                    target_id = next(iter(target_ids))
                    target = self.db.get_archived_song(target_id)
                    if target and all(field.get("title") == target["display_title"] and field.get("category_code") == target["category_code"]
                                      and str(field.get("seq") or "") == str(target["seq"]) for field in current):
                        destination.setCurrentIndex(destination.findData(target_id))
            scope_label.setText(f"本次操作：选中 {len(chosen)} / {len(items)} 份分谱")
        table.itemSelectionChanged.connect(load_selection)
        table.selectRow(0)
        if new_song:
            table.selectAll()
        def find_candidate(library_only=False):
            try:
                candidates = CorrectionService(self.db).matching_candidates(self.current_batch_id, selected_ids(), title.text().strip() or None)
                if library_only:
                    candidates = [candidate for candidate in candidates if candidate["kind"] == "archived"]
                    if not candidates:
                        QMessageBox.information(dialog, "库内暂无曲目", "当前乐谱库还没有可选的正式曲目")
                        return
                chosen = self.choose_correction_candidate(candidates, dialog, library_only=library_only)
                if chosen is None:
                    return
                if chosen["kind"] == "archived":
                    destination.setCurrentIndex(destination.findData(chosen["id"]))
                else:
                    destination.addItem(f"归入草稿 / 分类{chosen['category']} / {chosen['title']}", {"draft": chosen["id"]})
                    destination.setCurrentIndex(destination.count() - 1)
                return chosen
            except (ValueError, OSError, sqlite3.Error) as exc:
                QMessageBox.warning(dialog, "无法查找对应曲目", str(exc))
        candidate_button.clicked.connect(find_candidate)
        library_name_button.clicked.connect(lambda: find_candidate(True))
        def open_parts():
            for row in sorted({cell.row() for cell in table.selectedItems()}):
                self.open_pdf_path(Path(items[row]["source_path"]))
        table.cellDoubleClicked.connect(lambda *_: open_parts())
        open_button = QPushButton("打开选中PDF")
        open_button.clicked.connect(open_parts)
        layout.addWidget(open_button)
        state = QLabel("草稿尚未入库")
        state.setWordWrap(True)
        state.setObjectName("correction_state")
        layout.addWidget(state)
        def set_state(text):
            state.setText(text)
            state.setMinimumHeight(max(24, state.heightForWidth(max(300, dialog.width() - 40))))
        failure_text = [""]
        failure_detail = QPushButton("查看未入库原因")
        failure_detail.setObjectName("correction_failure_details")
        failure_detail.hide()
        failure_detail.clicked.connect(lambda: self.show_archive_failure_details(failure_text[0], dialog))
        layout.addWidget(failure_detail)
        saved_ids = set()
        approved_plans = None
        def include_related_parts(related_ids):
            # Preserve unsaved edits while adding rows and expanding the selection.
            rows = {int(item["id"]): index for index, item in enumerate(items)}
            selected = selected_ids() | set(related_ids)
            edits = (title.text(), category.currentData(), destination.currentData(), destination.currentText(),
                     destination.itemText(destination.currentIndex()))
            pending_edits[active_scope[0]] = edits
            related = []
            for item_id in related_ids:
                current = self.db.get_import_item(item_id)
                if current is None or current["batch_id"] != self.current_batch_id or current["status"] != "recognized":
                    raise ValueError("关联草稿已变化，请重新校对")
                related.append(current)
            table.blockSignals(True)
            try:
                for item in related:
                    item_id = int(item["id"])
                    if item_id not in rows:
                        row = len(items)
                        items.append(item)
                        rows[item_id] = row
                        table.insertRow(row)
                        add_item_row(row, item)
                    table.item(rows[item_id], 1).setToolTip(str(item["source_path"]))
                for item_id in selected:
                    table.selectionModel().select(table.model().index(rows[item_id], 0),
                                                  QItemSelectionModel.Select | QItemSelectionModel.Rows)
            finally:
                table.blockSignals(False)
            active_scope[0] = frozenset(selected)
            pending_edits[active_scope[0]] = edits
            count_label.setText(f"本次校对 {len(items)} 份草稿分谱")
            scope_label.setText(f"本次操作：选中 {len(selected)} / {len(items)} 份分谱（已带入 {len(related_ids)} 份同名关联分谱）")
            table.scrollToItem(table.item(rows[related_ids[0]], 1))
        def save_corrections(archive_force=None):
            nonlocal saved_ids, approved_plans
            approved_plans = None
            try:
                chosen = selected_ids()
                if not chosen:
                    raise ValueError("请先在分谱表中选中要校对的文件")
                if destination.currentIndex() < 0 or destination.currentText() != destination.itemText(destination.currentIndex()):
                    raise ValueError("请从下拉列表选定目标曲目，不接受未选定的搜索文字")
                service = CorrectionService(self.db)
                target_data = destination.currentData()
                for attempt in range(2):
                    try:
                        plan = service.build_plan(self.current_batch_id, chosen, title.text().strip() or None,
                            category.currentData(), {key: widget.currentData() for key, widget in instrument_inputs.items() if key in chosen},
                            None if isinstance(target_data, dict) or target_data == "new_song" else target_data,
                            target_data["draft"] if isinstance(target_data, dict) else None,
                            new_song=target_data == "new_song")
                        break
                    except RelatedDraftPartsError as exc:
                        if attempt:
                            raise
                        include_related_parts(exc.item_ids)
                        chosen = selected_ids()
                if archive_force is not None:
                    accepted, approved_plans = self.review_corrected_archive(plan, force=archive_force)
                    if not accepted:
                        return False
                elif not self.show_correction_preview(plan):
                    return False
                service.apply(plan)
                saved_ids = chosen
                for index, item in enumerate(items):
                    current = self.db.get_import_item(int(item["id"]))
                    current_fields = json.loads(current["parsed_fields"] or "{}")
                    value = f"{current_fields.get('title')} / {current_fields.get('category_code')} / {current_fields.get('seq') or '待分配'}"
                    table.item(index, 2).setText(value)
                    table.item(index, 2).setToolTip(value)
                self.refresh_draft_view()
                self.show_import_summary(self.current_batch_id)
                target = self.db.get_archived_song(plan.target_song_id) if plan.target_song_id else None
                canonical = f"已采用库内名称：{target['seq']} / 分类{target['category_code']} / {target['display_title']}\n" if target else ""
                set_state(canonical + f"选中的 {len(chosen)} 份校对已保存到草稿，尚未入库")
                failure_detail.hide()
                self.guidance_label.setText(state.text())
                return True
            except RelatedDraftPartsError as exc:
                try:
                    include_related_parts(exc.item_ids)
                    set_state("预览期间草稿发生变化，已带入同名分谱；请核对后重新保存或入库。")
                except (ValueError, OSError, sqlite3.Error) as changed:
                    QMessageBox.warning(dialog, "校对未完成", str(changed))
                return False
            except (ValueError, OSError, sqlite3.Error) as exc:
                QMessageBox.warning(dialog, "校对未完成", str(exc))
                return False
        def save_only():
            save_corrections()
        def archive_corrected(force_archive=False):
            if save_corrections(archive_force=force_archive):
                if self._commit_archive(saved_ids, inline_failure=True, force=force_archive, approved_plans=approved_plans):
                    dialog.accept()
                else:
                    canonical = state.text().splitlines()[0] + "\n" if state.text().startswith("已采用库内名称：") else ""
                    details = self.last_archive_failure or self.archive_blocking_details(saved_ids)
                    message = "校对已保存到草稿，未入库。\n" + (details or "入库已取消，文件仍在草稿中。")
                    failure_text[0] = canonical + message
                    lines = failure_text[0].splitlines()
                    compact = "\n".join(lines[:5])
                    if len(compact) > 300 or len(lines) > 5:
                        compact = compact[:300] + "\n…"
                    set_state(compact)
                    state.setToolTip(failure_text[0])
                    failure_detail.show()
                    self.guidance_label.setText(compact)
        def force_new_corrected():
            destination.setCurrentIndex(destination.findData("new_song"))
            archive_corrected(True)
        def force_existing_corrected():
            target = destination.currentData()
            valid_choice = (isinstance(target, int) and destination.currentIndex() >= 0
                            and destination.currentText() == destination.itemText(destination.currentIndex())
                            and self.db.get_archived_song(target) is not None)
            if not valid_choice and find_candidate(True) is None:
                return
            archive_corrected(True)
        def process_duplicates():
            answer = QMessageBox.question(dialog, "打开重复处理", "当前尚未保存的校对字段不会应用。打开重复对比清单继续处理？", QMessageBox.Yes | QMessageBox.No, QMessageBox.No)
            if answer == QMessageBox.Yes:
                self.show_duplicate_files(item_ids=selected_ids())
                dialog.reject()
        commands = QHBoxLayout()
        for text, name, callback in (("保存校对到草稿", "correction_save", save_only),
                                     ("对比并处理重复", "correction_duplicates", process_duplicates)):
            button = QPushButton(text)
            button.setObjectName(name)
            button.clicked.connect(callback)
            commands.addWidget(button, 1)
        archive_commands = QHBoxLayout()
        archive_commands.setSpacing(0)
        archive_button = QPushButton("强制入库" if force else "校对并入库")
        archive_button.setObjectName("correction_archive")
        archive_button.clicked.connect(lambda: archive_corrected(force))
        archive_commands.addWidget(archive_button, 1)
        archive_options = QToolButton()
        archive_options.setObjectName("correction_archive_options")
        archive_options.setArrowType(Qt.DownArrow)
        archive_options.setFixedWidth(36)
        archive_options.setToolTip("入库方式")
        archive_options.setAccessibleName("入库方式")
        archive_options.setPopupMode(QToolButton.InstantPopup)
        archive_options.setStyleSheet("QToolButton::menu-indicator { image: none; width: 0px; }")
        archive_menu = QMenu(archive_options)
        archive_menu.setObjectName("correction_archive_menu")
        for text, name, callback in (("普通校对并入库", "correction_archive_normal", lambda: archive_corrected(False)),
                                     ("强制新建独立曲目入库…", "correction_force_new_song", force_new_corrected),
                                     ("强制归入已有曲目并入库…", "correction_force_existing_song", force_existing_corrected)):
            action = archive_menu.addAction(text)
            action.setObjectName(name)
            action.triggered.connect(callback)
        archive_options.setMenu(archive_menu)
        archive_commands.addWidget(archive_options)
        commands.addLayout(archive_commands, 1)
        layout.addLayout(commands)
        close = QDialogButtonBox(QDialogButtonBox.Cancel)
        close.button(QDialogButtonBox.Cancel).setText("关闭")
        close.rejected.connect(dialog.reject)
        layout.addWidget(close)
        dialog.exec()
        dialog.deleteLater()

    def show_archive_failure_details(self, text, parent):
        dialog = QDialog(parent)
        dialog.setWindowTitle("未入库原因")
        dialog.resize(950, 520)
        layout = QVBoxLayout(dialog)
        details = QPlainTextEdit()
        details.setObjectName("archive_failure_text")
        details.setReadOnly(True)
        details.setPlainText(text)
        layout.addWidget(details)
        buttons = QDialogButtonBox(QDialogButtonBox.Close)
        buttons.button(QDialogButtonBox.Close).setText("关闭")
        buttons.rejected.connect(dialog.reject)
        layout.addWidget(buttons)
        dialog.exec()
        dialog.deleteLater()

    def choose_correction_candidate(self, candidates, parent, library_only=False):
        dialog = QDialog(parent)
        dialog.setWindowTitle("统一为库内名称" if library_only else "查找疑似对应曲目")
        dialog.resize(1000, 560)
        layout = QVBoxLayout(dialog)
        search = QLineEdit()
        search.setObjectName("candidate_search")
        search.setPlaceholderText("搜索曲名、分类或已有编号")
        layout.addWidget(search)
        suggested_only = QCheckBox("仅显示疑似对应")
        suggested_only.setChecked(any(candidate["suggested"] for candidate in candidates))
        layout.addWidget(suggested_only)
        table = ReadableTableWidget(len(candidates), 5)
        table.setObjectName("correction_candidates")
        table.setHorizontalHeaderLabels(["位置", "曲名", "分类", "已有编号", "名称相似度"])
        table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        table.setSelectionBehavior(QAbstractItemView.SelectRows)
        table.setSelectionMode(QAbstractItemView.SingleSelection)
        table.horizontalHeader().setSectionResizeMode(QHeaderView.Interactive)
        for col, width in enumerate((100, 410, 100, 100, 120)):
            table.setColumnWidth(col, width)
        for row, candidate in enumerate(candidates):
            for col, value in enumerate(("已入库" if candidate["kind"] == "archived" else "草稿", candidate["title"],
                                         candidate["category"], candidate["seq"] or "待分配", f"{candidate['similarity']}%")):
                table.setItem(row, col, QTableWidgetItem(str(value)))
        def filter_rows():
            query = search.text().strip().casefold()
            for row, candidate in enumerate(candidates):
                match = query in " ".join(str(candidate[key] or "") for key in ("title", "category", "seq")).casefold()
                hidden = not match or (not query and suggested_only.isChecked() and not candidate["suggested"])
                table.setRowHidden(row, hidden)
                if hidden:
                    table.selectionModel().select(table.model().index(row, 0), QItemSelectionModel.Deselect | QItemSelectionModel.Rows)
        search.textChanged.connect(filter_rows)
        suggested_only.toggled.connect(filter_rows)
        filter_rows()
        layout.addWidget(table)
        def open_candidate():
            row = table.currentRow()
            if row < 0 or table.isRowHidden(row):
                return
            candidate = candidates[row]
            if candidate["kind"] == "archived":
                scores = self.db.list_archived_scores(candidate["id"])
                if not scores:
                    QMessageBox.warning(dialog, "候选已变化", "候选曲目已不存在，请重新查找")
                    return
                self.show_archived_parts(scores)
            else:
                for path in candidate["pdf_paths"]:
                    self.open_pdf_path(Path(path))
        table.cellDoubleClicked.connect(lambda *_: open_candidate())
        open_button = QPushButton("打开候选分谱对比")
        open_button.setObjectName("candidate_open_pdf")
        open_button.clicked.connect(open_candidate)
        layout.addWidget(open_button)
        result = []
        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.button(QDialogButtonBox.Ok).setText("采用库内名称" if library_only else "归入选中曲目")
        buttons.button(QDialogButtonBox.Cancel).setText("取消")
        def accept():
            rows = {cell.row() for cell in table.selectedItems() if not table.isRowHidden(cell.row())}
            if len(rows) != 1:
                QMessageBox.information(dialog, "请选择曲目", "请选中要归入的候选曲目")
                return
            result.append(candidates[next(iter(rows))])
            dialog.accept()
        buttons.accepted.connect(accept)
        buttons.rejected.connect(dialog.reject)
        layout.addWidget(buttons)
        dialog.exec()
        dialog.deleteLater()
        return result[0] if result else None

    def review_corrected_archive(self, plan, force=False):
        output = self.db.get_setting("output_root", "")
        if output:
            plans = CorrectionService(self.db).preview_archive(plan, output, force=force)
            if plans:
                return self.show_archive_review(plans, confirm=True, correction_plan=plan), plans
        return self.show_correction_preview(plan), None

    def show_correction_preview(self, plan):
        dialog = QDialog(self)
        dialog.setWindowTitle("确认校对修改")
        dialog.resize(1000, 440)
        layout = QVBoxLayout(dialog)
        target = self.db.get_archived_song(plan.target_song_id) if plan.target_song_id else None
        if plan.target_draft_item_id is not None:
            field = json.loads(json.loads(plan.target_draft_snapshot)["parsed_fields"])
            summary = f"归入草稿曲目：分类{field['category_code']} / {field['title']} / {field.get('seq') or '待分配'}"
        elif plan.new_song:
            summary = "确认是独立新曲目，不补入已有曲目；正式入库时分配新的全库编号，原文件编号仅保留为记录。重复和文件安全仍检查；强制入库单独确认声部数豁免。"
        else:
            summary = f"补入已有曲目：{target['seq']} {target['display_title']}" if target else "按修改后的曲名和分类归组；现有曲目流水不改动"
        label = QLabel(f"本次只修改选中的 {len(plan.rows)} 份分谱。\n{summary}")
        label.setWordWrap(True)
        layout.addWidget(label)
        table = ReadableTableWidget(len(plan.rows), 5)
        table.setHorizontalHeaderLabels(["源文件名", "乐器 原→新", "分类 原→新", "曲名 原→新", "序号 原→新"])
        table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        for col, width in enumerate((260, 125, 125, 290, 140)):
            table.setColumnWidth(col, width)
        for index, row in enumerate(plan.rows):
            old, new = json.loads(row.old_fields), json.loads(row.new_fields)
            values = [Path(row.source_path).name] + [f"{old.get(key) or '未识别'} → {new.get(key) or '待分配'}" for key in ("instrument_code", "category_code", "title", "seq")]
            for col, value in enumerate(values):
                cell = QTableWidgetItem(value)
                cell.setToolTip(value)
                table.setItem(index, col, cell)
        layout.addWidget(table)
        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.button(QDialogButtonBox.Ok).setText("确认保存校对")
        buttons.button(QDialogButtonBox.Cancel).setText("取消")
        buttons.accepted.connect(dialog.accept)
        buttons.rejected.connect(dialog.reject)
        layout.addWidget(buttons)
        accepted = dialog.exec() == QDialog.Accepted
        dialog.deleteLater()
        return accepted

    def show_import_summary(self, batch_id: int) -> None:
        if self.import_summary_label is None:
            return
        summary = self.db.import_batch_summary(batch_id)
        self.import_summary_label.setText(
            "已存入草稿："
            f"共 {summary['total']} 份，"
            f"已识别 {summary['recognized']}，"
            f"待补录 {summary['needs_manual_entry']}，"
            f"已跳过 {summary['skipped']}"
        )

    def show_guidance_after_import(self) -> None:
        if self.guidance_label is not None:
            self.guidance_label.setText(
                "已存入草稿，还没写入正式乐谱库。能识别的会先列在表格里；待补录的选中后在右侧填写乐器编号、分类编号和曲名。"
            )

    def refresh_warnings(self, batch_id: int) -> None:
        if self.warning_table is None:
            return
        GroupingService(self.db).refresh_warnings(batch_id, scan_completeness=self.warning_scan_completeness)
        warnings = self.db.list_warnings(batch_id)
        self.warning_table.setRowCount(len(warnings))
        for row_index, warning in enumerate(warnings):
            details = json.loads(warning["details"] or "{}")
            code_item = QTableWidgetItem(self._warning_label(warning["code"]))
            code_item.setToolTip(code_item.text())
            code_item.setData(Qt.UserRole, int(warning["id"]))
            code_item.setData(Qt.UserRole + 1, self._comparison_paths(details))
            self.warning_table.setItem(row_index, 0, code_item)
            self.warning_table.setItem(row_index, 1, QTableWidgetItem(self._severity_label(warning["severity"])))
            self.warning_table.setItem(row_index, 2, QTableWidgetItem(str(warning["state"])))
            detail_item = QTableWidgetItem(self._warning_details(details))
            detail_item.setToolTip(detail_item.text())
            self.warning_table.setItem(row_index, 3, detail_item)

    def scan_completeness(self) -> None:
        if self.current_batch_id is None:
            QMessageBox.warning(self, "无法扫描", "请先导入一批分谱")
            return
        self.warning_scan_completeness = True
        GroupingService(self.db).refresh_warnings(self.current_batch_id, scan_completeness=True)
        if self.guidance_label is not None:
            self.guidance_label.setText("完整度扫描完成：按分类规则检查普通分谱；单份入库类型不受乐器数限制。")
        warnings = self.db.list_warnings(self.current_batch_id)
        assert self.warning_table is not None
        self.warning_table.setRowCount(len(warnings))
        for row_index, warning in enumerate(warnings):
            details = json.loads(warning["details"] or "{}")
            code_item = QTableWidgetItem(self._warning_label(warning["code"]))
            code_item.setToolTip(code_item.text())
            code_item.setData(Qt.UserRole, int(warning["id"]))
            code_item.setData(Qt.UserRole + 1, self._comparison_paths(details))
            self.warning_table.setItem(row_index, 0, code_item)
            self.warning_table.setItem(row_index, 1, QTableWidgetItem(self._severity_label(warning["severity"])))
            self.warning_table.setItem(row_index, 2, QTableWidgetItem(str(warning["state"])))
            detail_item = QTableWidgetItem(self._warning_details(details))
            detail_item.setToolTip(detail_item.text())
            self.warning_table.setItem(row_index, 3, detail_item)

    def mark_selected_warning_handled(self) -> None:
        self._mark_selected_warning("已处理")

    def mark_selected_warning_accepted(self) -> None:
        self._mark_selected_warning("已确认无须修改")

    def _mark_selected_warning(self, state: str) -> None:
        assert self.warning_table is not None
        rows = sorted({item.row() for item in self.warning_table.selectedItems()})
        if not rows:
            QMessageBox.warning(self, "无法更新预警", "请先选择预警")
            return
        try:
            for row in rows:
                warning_id = int(self.warning_table.item(row, 0).data(Qt.UserRole))
                self.db.update_warning_state(warning_id, state)
        except ValueError as exc:
            QMessageBox.warning(self, "无法更新预警", str(exc))
            return
        if self.current_batch_id is not None:
            if self.warning_scan_completeness:
                self.scan_completeness()
            else:
                self.refresh_warnings(self.current_batch_id)
            self.refresh_song_reports()

    def focus_scores_for_selected_warning(self) -> None:
        if self.score_table is None or self.warning_table is None:
            return
        rows = sorted({item.row() for item in self.warning_table.selectedItems()})
        if not rows:
            return
        source_paths = {
            str(path)
            for warning_row in rows
            for path in (self.warning_table.item(warning_row, 0).data(Qt.UserRole + 1) or [])
        }
        if not source_paths:
            return
        self.score_table.clearSelection()
        first_match: int | None = None
        for row in range(self.score_table.rowCount()):
            id_item = self.score_table.item(row, 1)
            if id_item is None:
                continue
            item_id = int(id_item.data(Qt.UserRole))
            item = self.db.get_import_item(item_id)
            if item is not None and str(item["source_path"]) in source_paths:
                self.score_table.selectionModel().select(
                    self.score_table.model().index(row, 0),
                    QItemSelectionModel.Select | QItemSelectionModel.Rows,
                )
                if first_match is None:
                    first_match = row
        if first_match is not None:
            self.score_table.scrollToItem(self.score_table.item(first_match, 1))

    def open_selected_pdf(self) -> None:
        assert self.score_table is not None
        rows = sorted({item.row() for item in self.score_table.selectedItems()})
        if not rows:
            QMessageBox.warning(self, "无法打开", "请先在上方分谱清单里选择一份 PDF")
            return
        id_item = self.score_table.item(rows[0], 1)
        item_id = int(id_item.data(Qt.UserRole))
        item = self.db.get_import_item(item_id)
        if item is None:
            QMessageBox.warning(self, "无法打开", "这条分谱记录不存在")
            return
        self.open_pdf_path(Path(item["source_path"]))

    def open_selected_warning_pdf(self) -> None:
        assert self.warning_table is not None
        rows = sorted({item.row() for item in self.warning_table.selectedItems()})
        if not rows:
            QMessageBox.warning(self, "无法打开", "请先在右侧预警表里选择一条预警")
            return
        source_paths = self.warning_table.item(rows[0], 0).data(Qt.UserRole + 1) or []
        if not source_paths:
            QMessageBox.warning(self, "无法打开", "这条预警没有关联到具体 PDF，请在上方分谱清单中选择对应文件打开")
            return
        for source_path in source_paths:
            self.open_pdf_path(Path(str(source_path)))

    def open_pdf_path(self, path: Path) -> None:
        if not path.exists():
            QMessageBox.warning(self, "无法打开", f"找不到原始 PDF：{path}")
            return
        if not QDesktopServices.openUrl(QUrl.fromLocalFile(str(path))):
            QMessageBox.warning(self, "无法打开", f"系统没有成功打开这个 PDF：{path}")

    def _comparison_paths(self, details) -> list[str]:
        paths = list(details.get("source_paths") or [])
        for score in details.get("existing") or []:
            path = self._archived_pdf_path(score)
            if str(path) not in paths:
                paths.append(str(path))
        return paths

    def _archived_pdf_path(self, score) -> Path:
        root = self.db.get_setting("output_root", "") or ""
        folder = ArchiveService(self.db, root)._instrument_folder(score["instrument_code"])
        archived = Path(root) / "按乐器分类" / folder / score["output_name"]
        return archived if root and archived.is_file() else Path(score["source_path"])

    def show_archived_catalog(self) -> None:
        songs = []
        dialog = QDialog(self)
        dialog.setWindowTitle("已入库目录")
        dialog.resize(900, 520)
        layout = QVBoxLayout(dialog)
        category_filter = QComboBox()
        category_filter.setObjectName("catalog_category_filter")
        category_filter.addItem("全部分类", None)
        for category in self.db.list_categories():
            category_filter.addItem(f"{category['code']} {category['name']}", category["code"])
        category_filter.setMinimumWidth(220)
        count_label = QLabel()
        count_label.setObjectName("catalog_count")
        category_row = QHBoxLayout()
        category_row.addWidget(QLabel("曲目分类"))
        category_row.addWidget(category_filter)
        category_row.addStretch()
        category_row.addWidget(count_label)
        layout.addLayout(category_row)
        search = QLineEdit()
        search.setPlaceholderText("搜索曲名或编号")
        layout.addWidget(search)
        table = ReadableTableWidget(len(songs), 6)
        table.setHorizontalHeaderLabels(["分类", "曲目序号", "曲名", "分谱类型数", "分谱数", "核对状态"])
        table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        table.setSelectionBehavior(QAbstractItemView.SelectRows)
        def populate(selected_song_id=None):
            nonlocal songs
            by_song = {}
            for score in self.db.list_archived_scores():
                by_song.setdefault(int(score["song_id"]), []).append(score)
            songs = list(by_song.values())
            table.setRowCount(len(songs))
            selected_row = None
            for row, song_scores in enumerate(songs):
                score = song_scores[0]
                instruments = {part["instrument_code"] for part in song_scores}
                state = "同乐器多份，需核对" if len(instruments) < len(song_scores) else "已入库"
                if all(part["storage_layout"] == "instrument_only" for part in song_scores) and state == "已入库":
                    state = "已入库，仅按乐器分类"
                song = self.db.get_archived_song(int(score["song_id"]))
                if song and song["minimum_waived"]:
                    state = (state + "；强制收录" if len(instruments) < len(song_scores) else
                             "强制入库，声部待补齐" if len(self.db.song_copy_codes(int(song["id"]))) < self.db.minimum_instruments(score["category_code"])
                             else "已入库（曾强制收录）")
                for col, value in enumerate((score["category_code"], score["seq"], score["display_title"], len(instruments), len(song_scores), state)):
                    item = QTableWidgetItem(str(value))
                    item.setToolTip(str(value))
                    table.setItem(row, col, item)
                if int(score["song_id"]) == selected_song_id:
                    selected_row = row
            if selected_row is not None and category_filter.currentData() is not None:
                category_filter.setCurrentIndex(category_filter.findData(songs[selected_row][0]["category_code"]))
            filter_rows(search.text())
            if selected_row is not None and not table.isRowHidden(selected_row):
                table.selectRow(selected_row)
        table.horizontalHeader().setSectionResizeMode(QHeaderView.Stretch)
        def filter_rows(value):
            visible = 0
            for row in range(table.rowCount()):
                matches_category = category_filter.currentData() is None or songs[row][0]["category_code"] == category_filter.currentData()
                matches_search = value.casefold() in " ".join(table.item(row, col).text() for col in range(6)).casefold()
                hidden = not (matches_category and matches_search)
                table.setRowHidden(row, hidden)
                visible += not hidden
            if table.currentRow() >= 0 and table.isRowHidden(table.currentRow()):
                table.clearSelection()
                table.setCurrentCell(-1, -1)
            count_label.setText(f"当前 {visible} 首 / 全库 {len(songs)} 首")
        search.textChanged.connect(filter_rows)
        category_filter.currentIndexChanged.connect(lambda: filter_rows(search.text()))
        populate()
        def open_song(row):
            if len(songs[row]) == 1:
                self.open_pdf_path(self._archived_pdf_path(songs[row][0]))
            else:
                self.show_archived_parts(songs[row])
        table.cellDoubleClicked.connect(lambda row, _: open_song(row))
        layout.addWidget(table)
        open_button = QPushButton("查看选中曲目的分谱")
        open_button.clicked.connect(lambda: open_song(table.currentRow()) if table.currentRow() >= 0 else None)
        commands = QHBoxLayout()
        commands.addWidget(open_button)
        edit_button = QPushButton("修改选中曲目的曲名和分类")
        def edit_selected():
            if table.currentRow() < 0:
                QMessageBox.information(dialog, "请选择曲目", "请先选择要修改的已入库曲目")
                return
            song_id = int(songs[table.currentRow()][0]["song_id"])
            if self.edit_archived_song(song_id):
                search.clear()
                populate(song_id)
                if self.current_batch_id is not None:
                    self.refresh_warnings(self.current_batch_id)
                    self.refresh_song_reports()
        edit_button.clicked.connect(edit_selected)
        commands.addWidget(edit_button)
        layout.addLayout(commands)
        merge_button = QPushButton("合并误拆曲目")
        merge_button.setObjectName("catalog_merge")
        def merge_selected():
            if table.currentRow() < 0:
                QMessageBox.information(dialog, "请选择曲目", "请先选择合并后要保留编号的曲目")
                return
            song_id = int(songs[table.currentRow()][0]["song_id"])
            if self.merge_archived_song(song_id):
                search.clear()
                populate(song_id)
                if self.current_batch_id is not None:
                    self.refresh_warnings(self.current_batch_id)
                    self.refresh_import_items()
                    self.refresh_song_reports()
        merge_button.clicked.connect(merge_selected)
        layout.addWidget(merge_button)
        buttons = QDialogButtonBox(QDialogButtonBox.Close)
        buttons.button(QDialogButtonBox.Close).setText("关闭")
        buttons.rejected.connect(dialog.reject)
        layout.addWidget(buttons)
        dialog.exec()
        dialog.deleteLater()

    def merge_archived_song(self, target_id: int) -> bool:
        target = self.db.get_archived_song(target_id)
        output = self.db.get_setting("output_root", "")
        if target is None or not output:
            QMessageBox.warning(self, "无法合并", "请选择已入库曲目并设置输出目录")
            return False
        candidates = {}
        for score in self.db.list_archived_scores():
            if (score["song_id"] != target_id and score["category_code"] == target["category_code"]
                    and re.sub(r"\s+", "", score["display_title"]) == re.sub(r"\s+", "", target["display_title"])):
                song = self.db.get_archived_song(int(score["song_id"]))
                if song["version_no"] == target["version_no"]:
                    candidates[int(song["id"])] = song
        if not candidates:
            QMessageBox.information(self, "没有误拆候选", "没有同分类、同版本且同名或仅空格不同的曲目；不同版本不能在这里合并")
            return False
        dialog = QDialog(self)
        dialog.setWindowTitle("合并误拆曲目")
        dialog.resize(1000, 560)
        layout = QVBoxLayout(dialog)
        layout.addWidget(QLabel(f"保留编号：{target['seq']}；分类：{target['category_code']}；曲名：{target['display_title']}"))
        form = QFormLayout()
        source_input = QComboBox()
        source_input.setObjectName("merge_source")
        for song in candidates.values():
            source_input.addItem(f"{song['seq']} {song['display_title']}", int(song["id"]))
        title_input = QLineEdit(target["display_title"])
        title_input.setObjectName("merge_title")
        form.addRow("合并另一条记录", source_input)
        form.addRow("统一曲名", title_input)
        layout.addLayout(form)
        table = ReadableTableWidget(0, 5)
        table.setObjectName("merge_parts")
        table.setHorizontalHeaderLabels(["原编号", "乐器", "分谱文件", "对比结果", "处理"])
        table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        table.setSelectionBehavior(QAbstractItemView.SelectRows)
        table.setSelectionMode(QAbstractItemView.ExtendedSelection)
        for col, width in enumerate((70, 140, 340, 190, 200)):
            table.setColumnWidth(col, width)
        layout.addWidget(table)
        rows = []
        decisions = []
        def populate():
            nonlocal rows, decisions
            rows = self.db.list_archived_scores(target_id) + self.db.list_archived_scores(source_input.currentData())
            rows.sort(key=lambda r: (r["instrument_code"], int(r["seq"]), r["id"]))
            by_instrument = {}
            for row in rows:
                by_instrument.setdefault(row["instrument_code"], []).append(row)
            decisions = []
            table.setRowCount(len(rows))
            for index, row in enumerate(rows):
                peers = by_instrument[row["instrument_code"]]
                status = "独立乐器" if len(peers) == 1 else ("内容完全相同" if len({r['source_sha256'] for r in peers}) == 1 else "同乐器内容不同，需对比")
                for col, value in enumerate((row["seq"], row["instrument_code"] + " " + row["instrument_name"], row["output_name"], status)):
                    item = QTableWidgetItem(str(value))
                    item.setToolTip(str(self._archived_pdf_path(row)) if col == 2 else str(value))
                    table.setItem(index, col, item)
                decision = QComboBox()
                decision.setObjectName(f"merge_decision_{row['id']}")
                if len(peers) > 1:
                    decision.addItem("待确认", None)
                    decision.addItem("保留", "keep")
                    decision.addItem("确认重复并转存", "duplicate")
                else:
                    decision.addItem("保留", "keep")
                    decision.setEnabled(False)
                table.setCellWidget(index, 4, decision)
                decisions.append(decision)
        source_input.currentIndexChanged.connect(populate)
        populate()
        def open_selected():
            selected = sorted({item.row() for item in table.selectedItems()})
            for index in selected:
                self.open_pdf_path(self._archived_pdf_path(rows[index]))
        table.cellDoubleClicked.connect(lambda *_: open_selected())
        open_button = QPushButton("打开选中PDF")
        open_button.clicked.connect(open_selected)
        layout.addWidget(open_button)
        consent = QCheckBox("我确认两条记录是同一版本；未保留分谱已对比并确认重复，转存至已确认重复")
        consent.setObjectName("merge_consent")
        source_input.currentIndexChanged.connect(lambda: consent.setChecked(False))
        layout.addWidget(consent)
        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.button(QDialogButtonBox.Ok).setText("预览合并")
        buttons.button(QDialogButtonBox.Ok).setEnabled(False)
        consent.toggled.connect(buttons.button(QDialogButtonBox.Ok).setEnabled)
        buttons.button(QDialogButtonBox.Cancel).setText("取消")
        buttons.rejected.connect(dialog.reject)
        changed = False
        def apply():
            nonlocal changed
            if any(decision.currentData() is None for decision in decisions):
                QMessageBox.warning(dialog, "请先对比分谱", "同乐器多份分谱必须逐份选择保留或确认重复；不同版本请取消合并")
                return
            try:
                selected = [int(row["id"]) for row, decision in zip(rows, decisions) if decision.currentData() == "keep"]
                service = CatalogMergeService(self.db, output)
                plan = service.build_plan(target_id, source_input.currentData(), selected, title_input.text())
                if not self.show_catalog_merge_preview(plan):
                    return
                backup = service.commit_plan(plan)
            except (ValueError, OSError, sqlite3.Error) as exc:
                QMessageBox.warning(dialog, "合并未完成", str(exc))
                return
            changed = True
            self.guidance_label.setText(f"已合并到{plan.seq}；{plan.source_seq}保留占号不再显示，备份：{backup}")
            dialog.accept()
        buttons.accepted.connect(apply)
        layout.addWidget(buttons)
        dialog.exec()
        dialog.deleteLater()
        return changed

    def show_catalog_merge_preview(self, plan) -> bool:
        dialog = QDialog(self)
        dialog.setWindowTitle("确认误拆合并方案")
        dialog.resize(1050, 500)
        layout = QVBoxLayout(dialog)
        backup_parent = Path(self.db.get_setting("output_root", "")) / "修改备份"
        label = QLabel(f"合并为：{plan.seq} {plan.title}；原编号{plan.source_seq}保留占号，不重新分配；原始PDF不变。备份位置：{backup_parent}")
        label.setWordWrap(True)
        layout.addWidget(label)
        table = ReadableTableWidget(len(plan.files), 6)
        table.setHorizontalHeaderLabels(["乐器", "原文件名", "新文件名", "处理", "按乐器分类目标", "按曲目分类目标"])
        table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        for col, width in enumerate((50, 220, 220, 130, 160, 190)):
            table.setColumnWidth(col, width)
        for index, file in enumerate(plan.files):
            action = "保留正式分谱" if file.score_id in plan.selected_score_ids else "确认重复并转存"
            instrument_target = file.new_instrument.parent.name if file.score_id in plan.selected_score_ids else f"已确认重复 / {file.new_instrument.parent.name}"
            song_target = (file.new_song.parent.name if file.score_id in plan.selected_score_ids else f"已确认重复 / {file.new_song.parent.name}") if file.new_song else "不生成曲目副本"
            for col, value in enumerate((file.instrument_code, file.old_instrument.name, file.new_instrument.name, action, instrument_target, song_target)):
                item = QTableWidgetItem(str(value))
                item.setToolTip(str(file.new_instrument) if col == 4 else str(file.new_song) if col == 5 else str(value))
                table.setItem(index, col, item)
        layout.addWidget(table)
        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.button(QDialogButtonBox.Ok).setText("备份并确认合并")
        buttons.button(QDialogButtonBox.Cancel).setText("取消")
        buttons.accepted.connect(dialog.accept)
        buttons.rejected.connect(dialog.reject)
        layout.addWidget(buttons)
        accepted = dialog.exec() == QDialog.Accepted
        dialog.deleteLater()
        return accepted

    def edit_archived_song(self, song_id: int) -> bool:
        output = self.db.get_setting("output_root", "")
        song = self.db.get_archived_song(song_id)
        if not output or song is None:
            QMessageBox.warning(self, "无法修改", "请先设置输出文件夹，并选择已入库曲目")
            return False
        dialog = QDialog(self)
        dialog.setWindowTitle("修改已入库曲目")
        dialog.resize(560, 240)
        layout = QVBoxLayout(dialog)
        layout.addWidget(QLabel(f"曲目序号：{song['seq']}"))
        form = QFormLayout()
        title_input = QLineEdit(song["display_title"])
        title_input.setObjectName("catalog_title")
        category_input = QComboBox()
        category_input.setObjectName("catalog_category")
        for category in self.db.list_categories():
            if category["active"] or category["code"] == song["category_code"]:
                category_input.addItem(f"{category['code']} {category['name']}", category["code"])
        category_input.setCurrentIndex(category_input.findData(song["category_code"]))
        form.addRow("曲名", title_input)
        form.addRow("分类", category_input)
        layout.addLayout(form)
        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.button(QDialogButtonBox.Ok).setText("预览修改")
        buttons.button(QDialogButtonBox.Cancel).setText("取消")
        buttons.rejected.connect(dialog.reject)
        changed = False
        def apply():
            nonlocal changed
            try:
                service = CatalogService(self.db, output)
                plan = service.build_plan(song_id, category_input.currentData(), title_input.text())
                if not self.show_catalog_edit_preview(plan):
                    return
                backup = service.commit_plan(plan)
            except (ValueError, OSError, sqlite3.Error) as exc:
                QMessageBox.warning(dialog, "修改未完成", str(exc))
                return
            changed = True
            self.guidance_label.setText(f"已更新曲名和分类，原序号 {plan.seq} 保留；修改前文件已保存到修改备份。")
            dialog.accept()
        buttons.accepted.connect(apply)
        layout.addWidget(buttons)
        dialog.exec()
        dialog.deleteLater()
        return changed

    def show_catalog_edit_preview(self, plan) -> bool:
        dialog = QDialog(self)
        dialog.setWindowTitle("确认修改已入库目录")
        dialog.resize(1100, 550)
        layout = QVBoxLayout(dialog)
        label = QLabel(f"序号 {plan.seq} 保留；曲名：{plan.old_title} → {plan.title}；分类：{plan.old_category} → {plan.category}")
        label.setWordWrap(True)
        layout.addWidget(label)
        table = ReadableTableWidget(sum(len(file.path_changes) for file in plan.files), 6)
        table.setHorizontalHeaderLabels(["乐器", "目录", "原文件名", "新文件名", "原路径", "新路径"])
        table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        table.setSelectionBehavior(QAbstractItemView.SelectRows)
        table.horizontalHeader().setSectionResizeMode(QHeaderView.Interactive)
        for col, width in enumerate((55, 85, 200, 200, 300, 300)):
            table.setColumnWidth(col, width)
        row = 0
        for file in plan.files:
            for directory, old, new in (("按乐器分类", file.old_instrument, file.new_instrument), ("按曲目分类", file.old_song, file.new_song)):
                if old is None or new is None:
                    continue
                for col, value in enumerate((file.instrument_code, directory, old.name, new.name, str(old), str(new))):
                    item = QTableWidgetItem(value)
                    item.setToolTip(value)
                    table.setItem(row, col, item)
                row += 1
        table.cellDoubleClicked.connect(lambda row, _: self.open_pdf_path(Path(table.item(row, 4).text())))
        layout.addWidget(table)
        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.button(QDialogButtonBox.Ok).setText("确认修改两套目录" if all(file.new_song for file in plan.files) else "确认修改实际存档")
        buttons.button(QDialogButtonBox.Cancel).setText("取消")
        buttons.accepted.connect(dialog.accept)
        buttons.rejected.connect(dialog.reject)
        layout.addWidget(buttons)
        accepted = dialog.exec() == QDialog.Accepted
        dialog.deleteLater()
        return accepted

    def show_archived_parts(self, scores) -> None:
        dialog = QDialog(self)
        dialog.setWindowTitle(f"{scores[0]['seq']} {scores[0]['display_title']} · 已入库分谱")
        dialog.resize(950, 500)
        layout = QVBoxLayout(dialog)
        table = ReadableTableWidget(len(scores), 4)
        table.setHorizontalHeaderLabels(["乐器", "文件名", "完整路径", "核对状态"])
        table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        table.setSelectionBehavior(QAbstractItemView.SelectRows)
        table.horizontalHeader().setSectionResizeMode(QHeaderView.Stretch)
        for row, score in enumerate(scores):
            same_instrument = sum(part["instrument_code"] == score["instrument_code"] for part in scores)
            status = "同乐器多份" if same_instrument > 1 else "已入库"
            for col, value in enumerate((score["instrument_name"], score["output_name"], str(self._archived_pdf_path(score).resolve()), status)):
                item = QTableWidgetItem(str(value))
                item.setToolTip(str(value))
                table.setItem(row, col, item)
        table.cellDoubleClicked.connect(lambda row, _: self.open_pdf_path(self._archived_pdf_path(scores[row])))
        layout.addWidget(table)
        open_button = QPushButton("打开选中PDF")
        open_button.clicked.connect(lambda: self.open_pdf_path(self._archived_pdf_path(scores[table.currentRow()])) if table.currentRow() >= 0 else None)
        layout.addWidget(open_button)
        close = QDialogButtonBox(QDialogButtonBox.Close)
        close.button(QDialogButtonBox.Close).setText("关闭")
        close.rejected.connect(dialog.reject)
        layout.addWidget(close)
        dialog.exec()
        dialog.deleteLater()

    def _warning_label(self, code: str) -> str:
        labels = {
            "insufficient_instruments": "分谱不足",
            "same_instrument_conflict": "同曲同乐器冲突",
            "duplicate_file": "本次完全重复",
            "duplicate_existing_file": "已入库重复",
            "existing_instrument_conflict": "已存在同乐器",
            "similar_title_candidate": "疑似同曲不同名",
            "similar_existing_title_candidate": "库内有相似曲名，待确认归属",
            "whitespace_title_conflict": "曲名仅空格不同，需校对",
            "instrument_title_suffix": "乐器后缀需统一曲名",
            "version_candidate": "版本候选",
        }
        return labels.get(code, code)

    def _severity_label(self, severity: str) -> str:
        labels = {"blocking": "阻断", "warning": "提示"}
        return labels.get(severity, severity)

    def _warning_details(self, details: dict[str, object]) -> str:
        title = details.get("title")
        instrument_count = details.get("instrument_count")
        files = details.get("files")
        existing = details.get("existing")
        left_title = details.get("left_title")
        right_title = details.get("right_title")
        if details.get("suggested_title"):
            return f"建议统一为：{details['suggested_title']}；原曲名：{'；'.join(details.get('original_titles') or [])}"
        if left_title and right_title and re.sub(r"\s+", "", str(left_title)) == re.sub(r"\s+", "", str(right_title)):
            visible_left = re.sub(r"\s", "[空格]", str(left_title))
            visible_right = re.sub(r"\s", "[空格]", str(right_title))
            seqs = sorted({str(score.get("seq", "")) for score in existing or []},
                          key=lambda value: (int(value) if value.isascii() and value.isdecimal() else -1, value))
            suffix = f"；对照已入库编号：{'、'.join(seqs)}" if seqs else ""
            return f"{visible_left} / {visible_right}{suffix}"
        if isinstance(files, list) and files:
            names = "；".join(str(file) for file in files)
            if isinstance(existing, list) and existing:
                return f"草稿：{names}；已入库：" + "；".join(str(score.get("output_name", "")) for score in existing)
            if details.get("instrument_code"):
                return f"{title or ''} / 乐器{details['instrument_code']}：{names}"
            return names
        if isinstance(existing, list) and existing:
            first = existing[0]
            return (
                f"已存在：{first.get('category_code', '')}{first.get('seq', '')}"
                f"{first.get('title', '')} / 乐器 {first.get('instrument_code', '')}"
            )
        if title and instrument_count is not None:
            return f"{title}：{instrument_count} 种乐器" + ("（已强制收录，可继续补谱）" if details.get("minimum_waived") else "")
        if title and details.get("instrument_code"):
            return f"{title}：乐器 {details['instrument_code']}"
        if left_title and right_title:
            return f"{left_title} / {right_title}"
        if title:
            return str(title)
        return json.dumps(details, ensure_ascii=False)

    def refresh_draft_view(self) -> None:
        if self.current_batch_id is None:
            self.refresh_import_items()
            return
        # One fresh scan and one consistent snapshot for all three tables.
        with self.db.transaction(reuse=True):
            self.refresh_warnings(self.current_batch_id)
            self.refresh_song_reports(refresh_warnings=False)

    def refresh_song_reports(self, *, refresh_warnings: bool = True) -> None:
        if self.report_table is None or self.current_batch_id is None:
            return
        reports = ReportService(self.db).build_song_reports(self.current_batch_id, refresh_warnings=refresh_warnings)
        self.report_table.setRowCount(len(reports))
        for row_index, report in enumerate(reports):
            self.report_table.setItem(row_index, 0, QTableWidgetItem(report.category_code))
            self.report_table.setItem(row_index, 1, QTableWidgetItem(report.title))
            self.report_table.setItem(row_index, 2, QTableWidgetItem(str(report.instrument_count)))
            self.report_table.setItem(row_index, 3, QTableWidgetItem(str(report.blocking_warning_count)))
            self.report_table.setItem(row_index, 4, QTableWidgetItem(report.target_folder_preview))
        self.refresh_import_items()

    def preview_archive_plan(self) -> None:
        if self.current_batch_id is None:
            QMessageBox.warning(self, "无法预览", "请先导入一批分谱")
            return
        output_root = self.db.get_setting("output_root", "")
        if not output_root:
            QMessageBox.warning(self, "无法预览", "请先设置输出文件夹")
            return
        try:
            plans = ArchiveService(self.db, output_root).build_plan(self.current_batch_id)
        except ValueError as exc:
            QMessageBox.warning(self, "无法预览", str(exc))
            return
        if not plans:
            self.show_no_archivable_songs_message("无法预览")
            return
        self.show_archive_review(plans, confirm=False)

    def show_archive_review(self, plans, confirm: bool, correction_plan=None) -> bool:
        dialog = QDialog(self)
        dialog.setWindowTitle("确认归档" if confirm else "归档预览")
        dialog.resize(1000, 540)
        layout = QVBoxLayout(dialog)
        count = sum(len(plan.files) for plan in plans)
        layout.addWidget(QLabel(f"本次归档 {len(plans)} 首曲目、{count} 份分谱"))
        if correction_plan is not None:
            summary = "确认后保存校对并入库；取消不保存本次修改。"
            if correction_plan.new_song:
                summary += " 新建独立曲目，使用新全库编号。"
            elif correction_plan.target_song_id:
                summary += " 补入选定的已有曲目，沿用其编号。"
            notice = QLabel(summary)
            notice.setWordWrap(True)
            layout.addWidget(notice)
        output = QLineEdit(self.db.get_setting('output_root', ''))
        output.setReadOnly(True)
        output.setToolTip(output.text())
        layout.addWidget(output)
        table = ReadableTableWidget(count, 5)
        table.setHorizontalHeaderLabels(["曲目目录", "乐器", "原始文件名", "新文件名", "源文件路径"])
        table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        table.setSelectionBehavior(QAbstractItemView.SelectRows)
        row = 0
        for plan in plans:
            for file in plan.files:
                values = (plan.folder_name if file.by_song_path else f"仅按乐器分类 / {file.by_instrument_path.parent.name}", file.instrument_code, file.source_path.name, file.output_name, str(file.source_path))
                for col, value in enumerate(values):
                    item = QTableWidgetItem(value)
                    item.setToolTip(value)
                    table.setItem(row, col, item)
                row += 1
        table.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeToContents)
        table.horizontalHeader().setSectionResizeMode(4, QHeaderView.Stretch)
        table.cellDoubleClicked.connect(lambda row, _: self.open_pdf_path(Path(table.item(row, 4).text())))
        layout.addWidget(table)
        if correction_plan is not None:
            changes = ReadableTableWidget(len(correction_plan.rows), 5)
            changes.setObjectName("archive_correction_changes")
            changes.setHorizontalHeaderLabels(["源文件名", "乐器 原→新", "分类 原→新", "曲名 原→新", "序号 原→新"])
            changes.setEditTriggers(QAbstractItemView.NoEditTriggers)
            changes.setMinimumHeight(110)
            for index, change in enumerate(correction_plan.rows):
                old, new = json.loads(change.old_fields), json.loads(change.new_fields)
                values = [Path(change.source_path).name] + [f"{old.get(key) or '未识别'} → {new.get(key) or '待分配'}" for key in ("instrument_code", "category_code", "title", "seq")]
                for col, value in enumerate(values):
                    cell = QTableWidgetItem(value)
                    cell.setToolTip(value)
                    changes.setItem(index, col, cell)
            changes.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeToContents)
            changes.horizontalHeader().setSectionResizeMode(3, QHeaderView.Stretch)
            layout.addWidget(changes)
            dialog.resize(1100, 680)
        force_confirmation = None
        if confirm and any(plan.force for plan in plans):
            notice = QLabel("强制收录本次选中分谱，即使只有一个声部也进入正式曲谱库；仅豁免本曲目的最少声部数，重复及文件安全仍检查。")
            notice.setWordWrap(True)
            layout.addWidget(notice)
            force_confirmation = QCheckBox("确认强制正式入库（允许仅一个声部）")
            force_confirmation.setObjectName("force_archive_confirm")
            layout.addWidget(force_confirmation)
        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel if confirm else QDialogButtonBox.Close)
        if confirm:
            buttons.button(QDialogButtonBox.Ok).setText("确认归档")
            buttons.button(QDialogButtonBox.Cancel).setText("取消")
            if force_confirmation is not None:
                buttons.button(QDialogButtonBox.Ok).setEnabled(False)
                force_confirmation.toggled.connect(buttons.button(QDialogButtonBox.Ok).setEnabled)
            buttons.accepted.connect(dialog.accept)
        else:
            buttons.button(QDialogButtonBox.Close).setText("关闭")
        buttons.rejected.connect(dialog.reject)
        layout.addWidget(buttons)
        accepted = dialog.exec() == QDialog.Accepted and (force_confirmation is None or force_confirmation.isChecked())
        dialog.deleteLater()
        return accepted

    def commit_current_archive_plan(self) -> None:
        self._commit_archive()

    def _commit_archive(self, item_ids=None, inline_failure=False, force=False, approved_plans=None) -> bool:
        self.last_archive_failure = ""
        if self.current_batch_id is None:
            self.last_archive_failure = "当前没有可归档的草稿分谱"
            if not inline_failure:
                QMessageBox.warning(self, "无法归档", "请先导入一批分谱")
            return False
        output_root = self.db.get_setting("output_root", "")
        if not output_root:
            self.last_archive_failure = "尚未选择输出文件夹"
            if not inline_failure:
                QMessageBox.warning(self, "无法归档", "请先设置输出文件夹")
            return False
        try:
            self.refresh_warnings(self.current_batch_id)
            if item_ids is not None and not force:
                items = self.db.list_import_items(self.current_batch_id)
                keys = {(fields.get("category_code"), fields.get("title")) for item in items
                        if item["id"] in item_ids and item["status"] == "recognized"
                        for fields in [json.loads(item["parsed_fields"] or "{}")]}
                item_ids = set(item_ids) | {int(item["id"]) for item in items
                           if item["status"] == "recognized"
                           and (json.loads(item["parsed_fields"] or "{}").get("category_code"),
                                json.loads(item["parsed_fields"] or "{}").get("title")) in keys}
            if not self.resolve_archived_duplicates(item_ids, for_archive=True):
                return False
            if self.current_batch_id is None:
                return True
            if item_ids is not None:
                item_ids &= {int(item["id"]) for item in self.db.list_import_items(self.current_batch_id)}
                if not item_ids:
                    return True
            service = ArchiveService(self.db, output_root)
            plans = service.build_plan(self.current_batch_id, item_ids, force=force)
            if not plans:
                if item_ids is None:
                    self.show_no_archivable_songs_message("无法归档")
                else:
                    reason = self.archive_blocking_details(item_ids) or "所选曲目未通过归档检查，请检查编号和PDF文件。"
                    self.last_archive_failure = reason
                    if not inline_failure:
                        QMessageBox.warning(self, "校对已保存，未入库", reason)
                return False
            if approved_plans is not None and plans != approved_plans:
                raise ValueError("最终预览后入库方案已变化，请重新核对并确认")
            if approved_plans is None and not self.show_archive_review(plans, confirm=True):
                self.last_archive_failure = "入库预览已取消，文件仍在草稿中。"
                if item_ids is not None:
                    self.guidance_label.setText("校对已保存到草稿；入库预览已取消，没有文件入库。")
                return False
            remaining_count = service.commit_plan(self.current_batch_id, plans)
        except (ValueError, OSError, sqlite3.Error) as exc:
            self.last_archive_failure = "归档失败：" + str(exc)
            if not inline_failure:
                QMessageBox.warning(self, "归档失败", str(exc))
            return False
        if remaining_count > 0:
            self.refresh_draft_view()
            if self.guidance_label is not None:
                self.guidance_label.setText("已入库本次确认的分谱；未入库的分谱仍保留在草稿中。")
            if self.import_summary_label is not None:
                self.show_import_summary(self.current_batch_id)
        else:
            self.current_batch_id = None
            self.refresh_import_items()
            if self.warning_table is not None:
                self.warning_table.setRowCount(0)
            if self.report_table is not None:
                self.report_table.setRowCount(0)
            if self.import_summary_label is not None:
                self.import_summary_label.setText("草稿箱已清空")
            if self.guidance_label is not None:
                self.guidance_label.setText("归档完成。已按各乐器的归档规则保存；单份入库类型仅在按乐器分类中存档。")
        summary = f"已入库 {len(plans)} 首、{sum(len(plan.files) for plan in plans)} 份分谱。" + ("未入库的分谱继续保留在草稿中。" if remaining_count else "本次草稿已全部入库。")
        if any(file.by_song_path is None for plan in plans for file in plan.files):
            summary += "单份入库类型仅保存在按乐器分类中。"
        self.guidance_label.setText(summary)
        self.statusBar().showMessage(summary, 15000)
        return True

    def archive_blocking_details(self, item_ids):
        if self.current_batch_id is None:
            return ""
        items = self.db.list_import_items(self.current_batch_id)
        fields = {int(item["id"]): json.loads(item["parsed_fields"] or "{}") for item in items}
        selected = set(item_ids)
        keys = {(fields[item_id].get("category_code"), fields[item_id].get("title")) for item_id in selected if item_id in fields}
        scoped = [item for item in items if int(item["id"]) in selected or
                  (fields[int(item["id"])].get("category_code"), fields[int(item["id"])].get("title")) in keys]
        paths = {str(item["source_path"]) for item in scoped}
        reports = ReportService(self.db).build_song_reports(self.current_batch_id)
        issues = []
        for warning in self.db.list_warnings(self.current_batch_id):
            info = json.loads(warning["details"] or "{}")
            affected = paths.intersection(info.get("source_paths") or [])
            if warning["severity"] != "blocking" or warning["state"] != "待处理" or not affected:
                continue
            issues.append(self._warning_label(warning["code"]) + "：" + self._warning_details(info) +
                          "\n涉及文件：" + "；".join(sorted(Path(path).name for path in affected)))
            if warning["code"] in {"duplicate_file", "duplicate_existing_file"}:
                issues[-1] += "\n文件内容重复，改名字不会消除内容重复。"
            elif warning["code"] in {"same_instrument_conflict", "existing_instrument_conflict"}:
                issues[-1] += "\n同一曲目已有此乐器分谱，名称统一后仍需确认两份分谱的取舍。"
        for report in reports:
            existing = self.db.find_archived_song(report.category_code, report.title)
            codes = {row.instrument_code for row in report.rows}
            eligible = self.db.archive_eligible_codes(report.category_code, codes, int(existing["id"]) if existing else None)
            if (report.category_code, report.title) in keys and not eligible:
                names = [Path(item["source_path"]).name for item in scoped
                         if (fields[int(item["id"])].get("category_code"), fields[int(item["id"])].get("title")) == (report.category_code, report.title)]
                minimum = self.db.minimum_instruments(report.category_code)
                modes = self.db.instrument_archive_modes()
                regular = {code for code in codes if modes.get(code) == "dual"}
                if existing:
                    regular |= self.db.song_copy_codes(int(existing["id"]))
                issues.append(f"{report.title}：普通分谱需要至少{minimum}种不同乐器，目前只有{len(regular)}种。可补齐或转入待审查。\n涉及文件：" + "；".join(names))
                candidates = {f"{score['seq']} {score.get('display_title') or score.get('title')}" for warning in self.db.list_warnings(self.current_batch_id)
                              if warning["code"] == "similar_existing_title_candidate"
                              for info in [json.loads(warning["details"])] if info.get("category_code") == report.category_code and info.get("left_title") == report.title
                              for score in info.get("existing") or []}
                if candidates:
                    issues[-1] += "\n库内候选标准名称：" + "；".join(sorted(candidates))
        return "\n\n".join(dict.fromkeys(issues))

    def show_no_archivable_songs_message(self, title: str) -> None:
        duplicates = DuplicateService(self.db).list_rows(self.current_batch_id) if self.current_batch_id else []
        if duplicates:
            self.guidance_label.setText("当前草稿已经保存；重复或同乐器冲突需要选择处理，已打开逐份文件对比清单。")
            self.show_duplicate_files()
            return
        if self.guidance_label is not None:
            self.guidance_label.setText(
                "当前草稿已经保存；普通分谱需达到分类最少乐器数，总谱可单份入库。"
            )
        QMessageBox.warning(
            self,
            title,
            "当前没有可正式归档的曲目。已识别分谱已存入草稿；普通分谱按分类规则补齐，总谱可单份入库。"
            "可以继续导入分谱，或点击“检查哪些曲子还缺乐器”。",
        )

    def open_warning_details(self, row: int, column: int) -> None:
        warning_id = int(self.warning_table.item(row, 0).data(Qt.UserRole))
        warning = next((warning for warning in self.db.list_warnings(self.current_batch_id) if int(warning["id"]) == warning_id), None)
        if warning and warning["code"] in DUPLICATE_CODES:
            self.show_duplicate_files(warning_id)
        else:
            self.open_selected_warning_pdf()

    def resolve_archived_duplicates(self, item_ids=None, for_archive=False) -> bool:
        if self.current_batch_id is None:
            return True
        batch_id = self.current_batch_id
        service = DuplicateService(self.db)
        rows = service.archived_conflict_rows(batch_id, item_ids)
        if not rows:
            return True
        dialog = QDialog(self)
        dialog.setWindowTitle("已有入库乐谱：确认是否转入重复库")
        dialog.resize(1050, 560)
        layout = QVBoxLayout(dialog)
        summary = QLabel()
        summary.setWordWrap(True)
        layout.addWidget(summary)
        table = ReadableTableWidget(0, 5)
        table.setObjectName("archived_duplicate_parts")
        table.setHorizontalHeaderLabels(["确认重复", "对比结果 / 位置", "曲目 / 乐器", "文件名", "PDF路径"])
        table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        table.setSelectionBehavior(QAbstractItemView.SelectRows)
        table.setSelectionMode(QAbstractItemView.ExtendedSelection)
        table.setWordWrap(False)
        table.setTextElideMode(Qt.ElideMiddle)
        for col, width in enumerate((85, 210, 190, 235, 265)):
            table.setColumnWidth(col, width)
        table.horizontalHeader().setSectionResizeMode(4, QHeaderView.Stretch)
        layout.addWidget(table)
        exact_ids = set()
        checked_ids = set()
        state = QLabel()
        state.setObjectName("archived_duplicate_state")
        state.setWordWrap(True)
        def update_state():
            state.setText(f"已勾选 {len(checked_ids)} 份；转存按钮保留库内谱；“保留选中新谱”按钮替换库内谱。原始PDF不改动。")
        def populate():
            ids = {row["item_id"] for row in rows if row["item_id"] is not None}
            exact_ids.clear()
            exact_ids.update(ids.intersection(service.already_archived_item_ids(batch_id)))
            checked_ids.intersection_update(ids)
            table.blockSignals(True)
            table.setRowCount(len(rows))
            groups = {group: index + 1 for index, group in enumerate(dict.fromkeys(row["group"] for row in rows))}
            summary.setText(f"已有入库乐谱，本次涉及 {len(ids)} 份草稿。内容完全相同 {len(exact_ids)} 份，"
                            f"其余 {len(ids - exact_ids)} 份为同曲同乐器但内容不同，请打开PDF对比后决定。")
            for index, row in enumerate(rows):
                existing = row["existing"]
                path = self._archived_pdf_path(existing) if existing else Path(row["path"])
                identity = f"{existing['seq']} / 分类{existing['category_code']} / " if existing else ""
                values = (str(groups[row["group"]]), f"{row['issue']} / {row['location']}",
                          f"{identity}{row['title']} / 乐器{row['instrument']}", row["name"], str(path.resolve()))
                for col, value in enumerate(values):
                    cell = QTableWidgetItem(value)
                    cell.setToolTip(value)
                    if col == 0 and row["item_id"] is not None:
                        cell.setFlags(cell.flags() | Qt.ItemIsUserCheckable)
                        cell.setData(Qt.UserRole, row["item_id"])
                        cell.setCheckState(Qt.Checked if row["item_id"] in checked_ids else Qt.Unchecked)
                    else:
                        cell.setFlags(cell.flags() & ~Qt.ItemIsUserCheckable)
                    table.setItem(index, col, cell)
            table.blockSignals(False)
            exact_button.setText(f"确认完全相同文件转入重复库（{len(exact_ids)}份）")
            exact_button.setEnabled(bool(exact_ids))
            update_state()
        def check_changed(cell):
            if cell.column() != 0 or cell.data(Qt.UserRole) is None:
                return
            item_id = int(cell.data(Qt.UserRole))
            if cell.checkState() == Qt.Checked:
                checked_ids.add(item_id)
            else:
                checked_ids.discard(item_id)
            table.blockSignals(True)
            for index, row in enumerate(rows):
                if row["item_id"] == item_id:
                    table.item(index, 0).setCheckState(cell.checkState())
            table.blockSignals(False)
            update_state()
        table.itemChanged.connect(check_changed)
        def open_selected():
            for path in dict.fromkeys(table.item(index, 4).text() for index in sorted({cell.row() for cell in table.selectedItems()})):
                self.open_pdf_path(Path(path))
        table.cellDoubleClicked.connect(lambda *_: open_selected())
        open_button = QPushButton("打开选中PDF对比")
        open_button.clicked.connect(open_selected)
        layout.addWidget(open_button)
        layout.addWidget(state)
        def transfer(ids, manual=True):
            nonlocal rows
            ids = set(ids)
            if not ids:
                QMessageBox.information(dialog, "请确认重复分谱", "请勾选已对比并确认重复的草稿；库内行仅供对比。")
                return
            try:
                self.refresh_warnings(batch_id)
                current_rows = service.archived_conflict_rows(batch_id, item_ids)
                if current_rows != rows:
                    rows = current_rows
                    checked_ids.clear()
                    populate()
                    raise ValueError("对比清单已变化，请重新核对后确认")
                output = self.db.get_setting("output_root", "")
                if not output:
                    raise ValueError("请先选择输出文件夹，才能保存已确认重复文件")
                destination, remaining = ConfirmedDuplicateService(self.db, output).confirm(batch_id, sorted(ids), manual=manual)
                if remaining == 0:
                    self.current_batch_id = None
                    self.warning_table.setRowCount(0)
                    self.report_table.setRowCount(0)
                    self.import_summary_label.setText("草稿箱已清空，重复分谱已转存")
                else:
                    self.refresh_warnings(batch_id)
                    self.refresh_song_reports()
                    self.show_import_summary(batch_id)
                self.refresh_import_items()
                self.guidance_label.setText(f"已转存 {len(ids)} 份分谱到已确认重复：{destination}")
                rows = service.archived_conflict_rows(batch_id, item_ids) if remaining else []
                if not rows:
                    dialog.accept()
                else:
                    populate()
                    state.setText(f"已转存 {len(ids)} 份。剩余分谱仍在草稿，请继续对比确认。")
            except (ValueError, OSError, sqlite3.Error) as exc:
                QMessageBox.warning(dialog, "重复转存未完成，草稿保留", str(exc))
        commands = QHBoxLayout()
        exact_button = QPushButton()
        exact_button.setObjectName("archived_duplicate_exact")
        exact_button.clicked.connect(lambda: transfer(exact_ids, manual=False))
        checked_button = QPushButton("确认勾选文件转入重复库")
        checked_button.setObjectName("archived_duplicate_confirm")
        checked_button.clicked.connect(lambda: transfer(checked_ids))
        commands.addWidget(exact_button)
        commands.addWidget(checked_button)
        layout.addLayout(commands)
        replace_button = QPushButton("保留选中新谱入库，旧谱转入重复库")
        replace_button.setObjectName("replace_archived_score")
        def replace_selected():
            nonlocal rows
            try:
                self.refresh_warnings(batch_id)
                current = service.archived_conflict_rows(batch_id, item_ids)
                if current != rows:
                    rows = current
                    checked_ids.clear()
                    populate()
                    raise ValueError("对比清单已变化，请重新选择新谱与库内谱")
                selected = {cell.row() for cell in table.selectedItems()}
                new_ids = {rows[index]["item_id"] for index in selected if rows[index]["item_id"] is not None} or set(checked_ids)
                if not new_ids:
                    raise ValueError("请选中要保留的新草稿分谱；如有多个库内目标，同时选中对应库内行")
                output = self.db.get_setting("output_root", "")
                if not output:
                    raise ValueError("请先选择输出文件夹")
                scores = self.db.list_archived_scores()
                choices = {}
                for new_id in new_ids:
                    new_rows = [row for row in rows if row["item_id"] == new_id]
                    groups = {row["group"] for row in new_rows}
                    code = new_rows[0]["instrument"]
                    candidates = [row for row in rows if row["existing"] and row["group"] in groups and row["instrument"] == code]
                    explicit = [rows[index] for index in selected if rows[index] in candidates]
                    owners = set()
                    for row in explicit or candidates:
                        existing = row["existing"]
                        owners.update(int(score["id"]) for score in scores if score["seq"] == existing["seq"]
                            and score["category_code"] == existing["category_code"] and score["instrument_code"] == code
                            and score["output_name"] == existing["output_name"])
                    if len(owners) != 1:
                        raise ValueError("库内目标不唯一，请同时选中一份新草稿和明确对应的库内分谱，不能自动猜测")
                    choices[new_id] = owners.pop()
                replacement = ReplacementService(self.db, output)
                plan = replacement.build_plan(batch_id, choices)
                if not self.show_replacement_preview(plan):
                    return
                backup, remaining = replacement.commit_plan(plan)
                checked_ids.difference_update(new_ids)
                if not remaining:
                    self.current_batch_id = None
                    self.warning_table.setRowCount(0)
                    self.report_table.setRowCount(0)
                    self.import_summary_label.setText("草稿箱已清空，新谱已替换入库")
                else:
                    self.refresh_warnings(batch_id)
                    self.refresh_song_reports()
                    self.show_import_summary(batch_id)
                self.refresh_import_items()
                self.guidance_label.setText(f"已替换入库 {len(plan.rows)} 份新谱；旧谱已保存到已确认重复。备份：{backup}")
                rows = service.archived_conflict_rows(batch_id, item_ids) if remaining else []
                if not rows:
                    dialog.accept()
                else:
                    populate()
            except (ValueError, OSError, sqlite3.Error) as exc:
                if "替换已提交" in str(exc):
                    if not self.db.list_import_items(batch_id):
                        self.current_batch_id = None
                        self.warning_table.setRowCount(0)
                        self.report_table.setRowCount(0)
                    else:
                        self._refresh_after_settings()
                    self.refresh_import_items()
                    self.guidance_label.setText(str(exc))
                    dialog.reject()
                elif "停止整理" in str(exc):
                    self.guidance_label.setText(str(exc))
                    dialog.reject()
                QMessageBox.warning(dialog, "替换未完成或需要检查", str(exc))
        replace_button.clicked.connect(replace_selected)
        layout.addWidget(replace_button)
        if for_archive:
            continue_button = QPushButton("暂不转存，继续入库无冲突分谱")
            continue_button.setObjectName("archived_duplicate_continue")
            continue_button.clicked.connect(dialog.accept)
            layout.addWidget(continue_button)
        close = QDialogButtonBox(QDialogButtonBox.Cancel)
        close.button(QDialogButtonBox.Cancel).setText("暂不转存，返回草稿")
        close.rejected.connect(dialog.reject)
        layout.addWidget(close)
        populate()
        accepted = dialog.exec() == QDialog.Accepted
        if not accepted and self.current_batch_id is not None and "停止整理" not in self.guidance_label.text():
            self.guidance_label.setText("已有入库乐谱尚未确认重复，分谱保留在草稿，未再次入库。")
        dialog.deleteLater()
        return accepted

    def show_replacement_preview(self, plan):
        dialog = QDialog(self)
        dialog.setWindowTitle("确认保留新谱并替换库内分谱")
        dialog.resize(1100, 580)
        layout = QVBoxLayout(dialog)
        summary = QLabel(f"替换 {len(plan.rows)} 份分谱；保留原编号、分类、版本及存档方式。原始PDF不改动。")
        summary.setWordWrap(True)
        layout.addWidget(summary)
        table = ReadableTableWidget(len(plan.rows), 7)
        table.setObjectName("replacement_preview")
        table.setHorizontalHeaderLabels(["曲目 / 编号", "乐器", "保留的新谱", "替换的库内文件", "旧谱转存到", "新谱入库位置", "源文件路径"])
        table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        table.setSelectionBehavior(QAbstractItemView.SelectRows)
        table.horizontalHeader().setSectionResizeMode(QHeaderView.Interactive)
        duplicates = json.loads(plan.duplicates)
        for index, row in enumerate(plan.rows):
            old = json.loads(row.score_snapshot)
            values = (f"{row.seq} / 分类{row.category} / {row.title}", row.instrument, row.source.name, row.targets[0].name,
                      str(Path(self.db.get_setting("output_root")) / "已确认重复" / duplicates[index]["relative_path"]),
                      "；".join(map(str, row.targets)), str(row.source))
            for column, value in enumerate(values):
                cell = QTableWidgetItem(value)
                cell.setToolTip(value + (f"\n新谱SHA-256：{row.sha256}" if column == 2 else
                                        f"\n旧谱SHA-256：{row.old_sha256}\n旧谱源文件：{old['source_path']}" if column == 3 else ""))
                table.setItem(index, column, cell)
        for column, width in enumerate((210, 60, 230, 230, 300, 330, 330)):
            table.setColumnWidth(column, width)
        layout.addWidget(table)
        def open_comparison():
            selected = {cell.row() for cell in table.selectedItems()}
            for index in sorted(selected):
                self.open_pdf_path(plan.rows[index].source)
                self.open_pdf_path(plan.rows[index].targets[0])
        open_button = QPushButton("打开选中新旧PDF对比")
        open_button.setObjectName("replacement_open_pdf")
        open_button.clicked.connect(open_comparison)
        layout.addWidget(open_button)
        confirmation = QCheckBox("已对比，确认保留新谱；旧谱转入已确认重复，属于同一版本而非新建版本")
        confirmation.setObjectName("replacement_confirm")
        layout.addWidget(confirmation)
        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        submit = buttons.button(QDialogButtonBox.Ok)
        submit.setText("备份并确认替换")
        submit.setObjectName("replacement_commit")
        submit.setEnabled(False)
        confirmation.toggled.connect(submit.setEnabled)
        buttons.button(QDialogButtonBox.Cancel).setText("取消")
        buttons.accepted.connect(dialog.accept)
        buttons.rejected.connect(dialog.reject)
        layout.addWidget(buttons)
        accepted = dialog.exec() == QDialog.Accepted and confirmation.isChecked()
        dialog.deleteLater()
        return accepted

    def show_duplicate_files(self, warning_id: int | None = None, item_ids=None) -> None:
        if self.current_batch_id is None:
            QMessageBox.information(self, "没有草稿", "当前没有需要检查的草稿")
            return
        service = DuplicateService(self.db)
        rows = (service.confirmation_rows(self.current_batch_id, item_ids) if item_ids is not None
                else service.list_rows(self.current_batch_id, warning_id))
        if not rows:
            QMessageBox.information(self, "没有重复冲突", "当前没有需要处理的重复或同乐器冲突")
            return
        dialog = QDialog(self)
        dialog.setWindowTitle("重复文件对比与处理")
        dialog.resize(1050, 560)
        layout = QVBoxLayout(dialog)
        summary = QLabel()
        summary.setWordWrap(True)
        layout.addWidget(summary)
        search = QLineEdit()
        search.setPlaceholderText("搜索曲名、文件名或路径")
        layout.addWidget(search)
        table = ReadableTableWidget(0, 7)
        table.setObjectName("duplicate_parts")
        table.setWordWrap(False)
        table.setTextElideMode(Qt.ElideMiddle)
        table.setHorizontalHeaderLabels(["对比组", "问题", "位置", "曲名 / 乐器", "文件名", "完整路径", "状态"])
        table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        table.setSelectionBehavior(QAbstractItemView.SelectRows)
        table.setSelectionMode(QAbstractItemView.ExtendedSelection)
        table.horizontalHeader().setSectionResizeMode(QHeaderView.Interactive)
        for col, width in enumerate((100, 150, 70, 140, 210, 220, 90)):
            table.setColumnWidth(col, width)
        checked_ids = set()
        selection_label = QLabel()
        def visible_ids():
            return {record["item_id"] for index, record in enumerate(rows)
                    if record["item_id"] is not None and not table.isRowHidden(index)}
        def checked_visible_ids():
            return checked_ids.intersection(visible_ids())
        def selected_ids():
            checked = checked_visible_ids()
            if checked_ids:
                return checked
            return {rows[item.row()]["item_id"] for item in table.selectedItems()
                    if not table.isRowHidden(item.row()) and rows[item.row()]["item_id"] is not None}
        def update_selection_label():
            ids = checked_visible_ids()
            groups = {record["group"] for record in rows if record["item_id"] in ids}
            selection_label.setText(f"已勾选 {len(ids)} 份草稿，涉及 {len(groups)} 组")
        def pdf_path(record):
            path = self._archived_pdf_path(record["existing"]) if record["existing"] else Path(record["path"])
            return path.resolve()
        def filter_rows(value):
            for row in range(table.rowCount()):
                table.setRowHidden(row, value.casefold() not in " ".join(table.item(row, col).text() for col in range(7)).casefold())
            update_selection_label()
        def populate():
            checked_ids.intersection_update(record["item_id"] for record in rows)
            table.blockSignals(True)
            table.clearSelection()
            table.setRowCount(len(rows))
            groups = {group: index + 1 for index, group in enumerate(dict.fromkeys(record["group"] for record in rows))}
            drafts = {record["item_id"] for record in rows if record["item_id"] is not None}
            summary.setText(f"{len(groups)} 组，涉及 {len(drafts)} 份草稿。人工确认重复不要求曲名相同；所选副本核验后转存，原PDF和库内文件保留。")
            for row, record in enumerate(rows):
                values = (str(groups[record["group"]]), record["issue"], record["location"],
                          f"{record['title']} / {record['instrument']}", record["name"], str(pdf_path(record)),
                          "待确认转存" if record["item_id"] is not None else "库内已保存")
                for col, value in enumerate(values):
                    item = QTableWidgetItem(value)
                    item.setToolTip(value)
                    if col == 0 and record["item_id"] is not None:
                        item.setData(Qt.UserRole, record["item_id"])
                        item.setFlags(item.flags() | Qt.ItemIsUserCheckable)
                        item.setCheckState(Qt.Checked if record["item_id"] in checked_ids else Qt.Unchecked)
                    elif col == 0:
                        item.setFlags(item.flags() & ~Qt.ItemIsUserCheckable)
                    table.setItem(row, col, item)
            table.blockSignals(False)
            filter_rows(search.text())
        def set_checked(ids):
            checked_ids.clear()
            checked_ids.update(ids)
            table.blockSignals(True)
            table.clearSelection()
            for index, record in enumerate(rows):
                if record["item_id"] is not None:
                    table.item(index, 0).setCheckState(Qt.Checked if record["item_id"] in checked_ids else Qt.Unchecked)
            table.blockSignals(False)
            update_selection_label()
        def on_checked(item):
            if item.column() != 0 or item.data(Qt.UserRole) is None:
                return
            ids = set(checked_ids)
            if item.checkState() == Qt.Checked:
                ids.add(item.data(Qt.UserRole))
            else:
                ids.discard(item.data(Qt.UserRole))
            set_checked(ids)
        table.itemChanged.connect(on_checked)
        populate()
        search.textChanged.connect(filter_rows)
        table.cellDoubleClicked.connect(lambda row, _: self.open_pdf_path(pdf_path(rows[row])))
        layout.addWidget(table)
        select_commands = QHBoxLayout()
        for label, name, action in (
            ("勾选可见草稿", "duplicate_select_visible", lambda: set_checked(visible_ids())),
            ("勾选完全相同的多余副本", "duplicate_select_exact", lambda: set_checked(service.exact_redundant_item_ids(self.current_batch_id, visible_ids()))),
            ("取消全部勾选", "duplicate_clear_selection", lambda: set_checked(set())),
        ):
            button = QPushButton(label)
            button.setObjectName(name)
            button.clicked.connect(action)
            select_commands.addWidget(button)
        layout.addLayout(select_commands)
        layout.addWidget(selection_label)
        commands = QGridLayout()
        open_button = QPushButton("打开选中PDF")
        def open_selected():
            indexes = ([index for index, record in enumerate(rows) if record["item_id"] in checked_visible_ids() and not table.isRowHidden(index)]
                       if checked_ids else sorted({item.row() for item in table.selectedItems() if not table.isRowHidden(item.row())}))
            paths = list(dict.fromkeys(pdf_path(rows[row]) for row in indexes))
            for path in paths:
                self.open_pdf_path(path)
        open_button.clicked.connect(open_selected)
        commands.addWidget(open_button, 0, 0)
        remove_button = QPushButton("确认选中重复并转存")
        remove_button.setObjectName("duplicate_confirm_selected")
        def remove_items(ids):
            nonlocal rows
            ids = sorted(set(ids))
            if not ids:
                QMessageBox.information(dialog, "没有可移出的草稿", "请选择草稿行；库内文件仅用于对比")
                return
            groups = {record["group"] for record in rows if record["item_id"] in ids}
            answer = QMessageBox.question(dialog, "确认重复并转存", f"本次涉及 {len(groups)} 组、{len(ids)} 份PDF。\n按你的人工判断保存到“已确认重复”，不要求名称或内容指纹相同，成功后移出草稿。\n原始PDF及已入库文件保留；确认所选均为重复副本吗？",
                                          QMessageBox.Yes | QMessageBox.No, QMessageBox.No)
            if answer != QMessageBox.Yes:
                return
            try:
                output = self.db.get_setting("output_root", "")
                if not output:
                    raise ValueError("请先选择输出文件夹，才能保存已确认重复文件")
                destination, remaining = ConfirmedDuplicateService(self.db, output).confirm(self.current_batch_id, ids, manual=True)
                self.guidance_label.setText(f"已转存 {len(ids)} 份PDF到已确认重复，完整且无冲突的曲目可继续归档。")
                if remaining == 0:
                    self.current_batch_id = None
                    self.warning_table.setRowCount(0)
                    self.report_table.setRowCount(0)
                    self.import_summary_label.setText("草稿箱已清空，已入库曲目可在目录中查看")
                    self.refresh_import_items()
                    dialog.accept()
                    return
                self.refresh_warnings(self.current_batch_id)
                self.refresh_song_reports()
                self.show_import_summary(self.current_batch_id)
                rows = (service.confirmation_rows(self.current_batch_id, item_ids) if item_ids is not None
                        else service.list_rows(self.current_batch_id, warning_id))
                populate()
                summary.setText(summary.text() + f" 已转存 {len(ids)} 份PDF到已确认重复。")
            except (ValueError, OSError, sqlite3.Error) as exc:
                QMessageBox.warning(dialog, "重复转存失败，草稿保留", str(exc))
        remove_button.clicked.connect(lambda: remove_items(selected_ids()))
        commands.addWidget(remove_button, 0, 1)
        keep_button = QPushButton("保留选中，其他分谱转存已确认重复")
        def keep_selected():
            kept_ids = selected_ids()
            selected = [record for record in rows if record["item_id"] in kept_ids]
            selected_groups = {record["group"] for record in selected}
            remove_items([record["item_id"] for record in rows if record["group"] in selected_groups and record["item_id"] in visible_ids() and record["item_id"] not in kept_ids])
        keep_button.clicked.connect(keep_selected)
        commands.addWidget(keep_button, 1, 0)
        clear_saved = QPushButton("确认并转存全批已入库相同副本")
        clear_saved.clicked.connect(lambda: remove_items(service.already_archived_item_ids(self.current_batch_id)))
        commands.addWidget(clear_saved, 1, 1)
        folder_button = QPushButton("打开已确认重复文件夹")
        folder_button.clicked.connect(self.open_confirmed_duplicate_folder)
        commands.addWidget(folder_button, 2, 0, 1, 2)
        layout.addLayout(commands)
        close = QDialogButtonBox(QDialogButtonBox.Close)
        close.button(QDialogButtonBox.Close).setText("返回继续归档")
        close.rejected.connect(dialog.reject)
        layout.addWidget(close)
        dialog.exec()
        dialog.deleteLater()

    def show_review_confirmation(self, plan: list[dict]) -> bool:
        dialog = QDialog(self)
        dialog.setWindowTitle("归档到待审查")
        dialog.resize(900, 500)
        layout = QVBoxLayout(dialog)
        layout.addWidget(QLabel(f"仅保存本次选中的 {len(plan)} 份分谱到待审查；未选中分谱保留草稿，原始PDF不变"))
        table = ReadableTableWidget(len(plan), 3)
        table.setHorizontalHeaderLabels(["原始文件名", "审查原因", "待审查相对路径"])
        table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        for row, item in enumerate(plan):
            for col, value in enumerate((Path(item["source_path"]).name, "、".join(item["reasons"]), item["relative_path"])):
                cell = QTableWidgetItem(value)
                cell.setToolTip(value)
                table.setItem(row, col, cell)
        table.horizontalHeader().setSectionResizeMode(QHeaderView.Stretch)
        table.cellDoubleClicked.connect(lambda row, _: self.open_pdf_path(Path(plan[row]["source_path"])))
        layout.addWidget(table)
        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.button(QDialogButtonBox.Ok).setText("保存到待审查")
        buttons.button(QDialogButtonBox.Cancel).setText("取消")
        buttons.accepted.connect(dialog.accept)
        buttons.rejected.connect(dialog.reject)
        layout.addWidget(buttons)
        accepted = dialog.exec() == QDialog.Accepted
        dialog.deleteLater()
        return accepted

    def archive_to_review(self) -> None:
        output = self.db.get_setting("output_root", "")
        if self.current_batch_id is None or not output:
            QMessageBox.warning(self, "无法转存", "请先导入分谱并选择输出文件夹")
            return
        rows = sorted({item.row() for item in self.score_table.selectedItems()})
        if not rows:
            QMessageBox.warning(self, "请选择分谱", "请先选中需要转入待审查的草稿分谱，不会默认处理整个草稿箱")
            return
        ids = {int(self.score_table.item(row, 1).data(Qt.UserRole)) for row in rows}
        try:
            service = ReviewService(self.db, output)
            plan = service.build_plan(self.current_batch_id, ids)
            self.warning_scan_completeness = True
            if not plan:
                QMessageBox.information(self, "没有待审查文件", "当前曲目没有待审查问题，可以使用正式归档")
                return
            if not self.show_review_confirmation(plan):
                return
            destination, remaining = service.commit_plan(self.current_batch_id, plan, ids)
        except (ValueError, OSError, sqlite3.Error) as exc:
            QMessageBox.warning(self, "待审查转存失败", str(exc))
            return
        if remaining == 0:
            self.current_batch_id = None
            self.warning_table.setRowCount(0)
            self.report_table.setRowCount(0)
            self.import_summary_label.setText("草稿箱已清空")
        else:
            self.refresh_draft_view()
            self.show_import_summary(self.current_batch_id)
        if remaining == 0:
            self.refresh_import_items()
        self.guidance_label.setText(f"已转存 {len(plan)} 份分谱到待审查，可在待审查列表中查看和放回草稿")
        QMessageBox.information(self, "待审查归档完成", f"已保存 {len(plan)} 份 PDF 和审查说明。\n{destination}")

    def store_unused_scores(self) -> None:
        output = self.db.get_setting("output_root", "")
        rows = sorted({item.row() for item in self.score_table.selectedItems()})
        if not output or self.current_batch_id is None or not rows:
            QMessageBox.warning(self, "无法暂存", "请先选择输出文件夹，并选中需要暂存的草稿分谱")
            return
        try:
            service = UnusedScoreService(self.db, output)
            ids = [int(self.score_table.item(row, 1).data(Qt.UserRole)) for row in rows]
            plan = service.build_plan(self.current_batch_id, ids)
            dialog = QDialog(self)
            dialog.setWindowTitle("确认暂时无用乐谱")
            dialog.resize(900, 500)
            layout = QVBoxLayout(dialog)
            label = QLabel(f"选中 {len(plan)} 份PDF，统一保存到：{service.root}\n原始PDF保留；成功后移出草稿，不进入正式乐谱库。")
            label.setWordWrap(True)
            layout.addWidget(label)
            table = ReadableTableWidget(len(plan), 2)
            table.setHorizontalHeaderLabels(["原文件名", "暂存文件名"])
            table.setEditTriggers(QAbstractItemView.NoEditTriggers)
            table.horizontalHeader().setSectionResizeMode(QHeaderView.Stretch)
            for row, item in enumerate(plan):
                original = QTableWidgetItem(Path(item["source_path"]).name)
                original.setToolTip(item["source_path"])
                table.setItem(row, 0, original)
                table.setItem(row, 1, QTableWidgetItem(item["relative_path"]))
            table.cellDoubleClicked.connect(lambda row, _: self.open_pdf_path(Path(plan[row]["source_path"])))
            layout.addWidget(table)
            buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
            buttons.button(QDialogButtonBox.Ok).setText("确认暂存")
            buttons.button(QDialogButtonBox.Cancel).setText("取消")
            buttons.accepted.connect(dialog.accept)
            buttons.rejected.connect(dialog.reject)
            layout.addWidget(buttons)
            accepted = dialog.exec() == QDialog.Accepted
            dialog.deleteLater()
            if not accepted:
                return
            destination, remaining = service.commit_plan(self.current_batch_id, plan)
        except (ValueError, OSError, sqlite3.Error) as exc:
            QMessageBox.warning(self, "暂存失败", str(exc))
            return
        if remaining == 0:
            self.current_batch_id = None
            self.warning_table.setRowCount(0)
            self.report_table.setRowCount(0)
            self.import_summary_label.setText("草稿箱已清空")
        else:
            self.refresh_warnings(self.current_batch_id)
            self.refresh_song_reports()
            self.show_import_summary(self.current_batch_id)
        self.refresh_import_items()
        self.guidance_label.setText(f"已暂存 {len(plan)} 份分谱：{destination}")

    def show_pending_review(self, unused: bool = False) -> None:
        output = self.db.get_setting("output_root", "")
        if not output:
            QMessageBox.warning(self, "无法查看", "请先选择输出文件夹")
            return
        service = UnusedScoreService(self.db, output) if unused else ReviewService(self.db, output)
        title = "暂时无用乐谱" if unused else "待审查文件"
        try:
            entries = service.list_pending()
        except (ValueError, OSError) as exc:
            QMessageBox.warning(self, f"无法读取{title}清单", str(exc))
            return
        dialog = QDialog(self)
        dialog.setWindowTitle(title)
        dialog.resize(950, 540)
        layout = QVBoxLayout(dialog)
        search = QLineEdit()
        search.setPlaceholderText("搜索文件名或原因")
        layout.addWidget(search)
        table = ReadableTableWidget(0, 3)
        table.setHorizontalHeaderLabels(["文件名", "原因", "保存路径"])
        table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        table.setSelectionBehavior(QAbstractItemView.SelectRows)
        table.setSelectionMode(QAbstractItemView.ExtendedSelection)
        table.horizontalHeader().setSectionResizeMode(QHeaderView.Stretch)
        def populate():
            table.setRowCount(len(entries))
            for row, entry in enumerate(entries):
                for col, value in enumerate((entry["pdf_path"].name, "、".join(entry["reasons"]), str(entry["pdf_path"]))):
                    cell = QTableWidgetItem(value)
                    cell.setToolTip("\n".join(entry["details"]) if col == 1 else value)
                    table.setItem(row, col, cell)
            filter_rows(search.text())
        def filter_rows(value):
            for row in range(table.rowCount()):
                hidden = value.casefold() not in " ".join(table.item(row, col).text() for col in range(3)).casefold()
                table.setRowHidden(row, hidden)
                if hidden:
                    table.selectionModel().select(table.model().index(row, 0), QItemSelectionModel.Deselect | QItemSelectionModel.Rows)
        search.textChanged.connect(filter_rows)
        populate()
        table.cellDoubleClicked.connect(lambda row, _: self.open_pdf_path(entries[row]["pdf_path"]))
        layout.addWidget(table)
        buttons = QHBoxLayout()
        open_button = QPushButton("打开选中PDF")
        open_button.clicked.connect(lambda: self.open_pdf_path(entries[table.currentRow()]["pdf_path"]) if table.currentRow() >= 0 and not table.isRowHidden(table.currentRow()) else None)
        buttons.addWidget(open_button)
        restore_button = QPushButton("放回草稿继续整理")
        def restore():
            nonlocal entries
            rows = sorted({item.row() for item in table.selectedItems() if not table.isRowHidden(item.row())})
            try:
                self.current_batch_id = service.restore_to_draft([entries[row] for row in rows], self.current_batch_id)
                self.refresh_warnings(self.current_batch_id)
                self.refresh_song_reports()
                self.show_import_summary(self.current_batch_id)
                self.guidance_label.setText(f"{title}已放回草稿")
                entries = service.list_pending()
                populate()
            except (ValueError, OSError, sqlite3.Error) as exc:
                QMessageBox.warning(dialog, "无法放回草稿", str(exc))
        restore_button.clicked.connect(restore)
        buttons.addWidget(restore_button)
        open_folder = QPushButton("打开暂时无用乐谱文件夹" if unused else "打开待审查文件夹")
        open_folder.clicked.connect(lambda: QDesktopServices.openUrl(QUrl.fromLocalFile(str(service.root.resolve()))))
        buttons.addWidget(open_folder)
        layout.addLayout(buttons)
        close = QDialogButtonBox(QDialogButtonBox.Close)
        close.button(QDialogButtonBox.Close).setText("关闭")
        close.rejected.connect(dialog.reject)
        layout.addWidget(close)
        dialog.exec()
        dialog.deleteLater()
