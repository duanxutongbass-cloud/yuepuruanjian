from PySide6.QtCore import QTimer
from PySide6.QtWidgets import QApplication, QComboBox, QLabel, QLineEdit, QPushButton, QTableWidget

from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.storage.database import Database
from score_organizer.ui.main_window import MainWindow


def catalog_fixture(tmp_path, *, valid_pdf=False):
    db = Database(tmp_path / "catalog.sqlite3")
    db.initialize()
    root = tmp_path / "out"
    db.set_setting("output_root", str(root))
    sources = []
    for category, title in [("01", "天空之城"), ("02", "哈利波特01"), ("03", "独立曲目")]:
        files = []
        for code in ("01", "02", "03"):
            pdf = tmp_path / f"{code}{category}{title}.pdf"
            if valid_pdf:
                from pypdf import PdfWriter
                writer = PdfWriter()
                writer.add_blank_page(595, 842)
                writer.add_metadata({'/Title': f'Synthetic {category} {title} {code}'})
                with pdf.open('wb') as stream:
                    writer.write(stream)
            else:
                pdf.write_bytes(f"%PDF {category} {title} {code}".encode("utf-8"))
            files.append(pdf)
        batch = ImportService(db).import_paths(files)
        archive = ArchiveService(db, root)
        archive.commit_plan(batch, archive.build_plan(batch))
        sources.extend(files)
    return db, root, sources


def test_catalog_category_filter_is_exact_combines_search_and_clears_hidden_selection(tmp_path):
    app = QApplication.instance() or QApplication([])
    db, root, sources = catalog_fixture(tmp_path)
    before = {p: sha256_file(p) for p in [*sources, *root.rglob("*.pdf")]}
    records = [dict(r) for r in db.list_archived_scores()]
    window = MainWindow(db)
    def inspect():
        dialog = app.activeModalWidget()
        category = dialog.findChild(QComboBox, "catalog_category_filter")
        search = dialog.findChild(QLineEdit)
        table = dialog.findChild(QTableWidget)
        count = dialog.findChild(QLabel, "catalog_count")
        def visible():
            return [table.item(row, 0).text() for row in range(table.rowCount()) if not table.isRowHidden(row)]
        assert visible() == ["01", "02", "03"]
        assert category.itemText(category.findData("01")) == "01 久石让"
        category.setCurrentIndex(category.findData("01"))
        assert visible() == ["01"]
        assert count.text() == "当前 1 首 / 全库 3 首"
        table.selectRow(0)
        category.setCurrentIndex(category.findData("02"))
        assert visible() == ["02"]
        assert table.selectedItems() == [] and table.currentRow() == -1
        search.setText("天空")
        assert visible() == []
        assert count.text() == "当前 0 首 / 全库 3 首"
        search.setText("哈利")
        assert visible() == ["02"]
        dialog.grab().save(str(tmp_path / "catalog-category-filter.png"))
        search.clear()
        category.setCurrentIndex(category.findData("05"))
        assert visible() == []
        category.setCurrentIndex(0)
        assert visible() == ["01", "02", "03"]
        dialog.reject()
    QTimer.singleShot(50, inspect)
    window.show_archived_catalog()
    assert [dict(r) for r in db.list_archived_scores()] == records
    assert {p: sha256_file(p) for p in before} == before
    window.close()
    app.processEvents()


def test_filtered_catalog_edit_switches_to_updated_category_and_refreshes(tmp_path, monkeypatch):
    from PySide6.QtWidgets import QPushButton
    app = QApplication.instance() or QApplication([])
    db, root, _ = catalog_fixture(tmp_path)
    window = MainWindow(db)
    monkeypatch.setattr(window, "show_catalog_edit_preview", lambda plan: True)
    observed = []
    def inspect_updated():
        dialog = app.activeModalWidget()
        table = dialog.findChild(QTableWidget)
        category = dialog.findChild(QComboBox, "catalog_category_filter")
        assert category.currentData() == "04"
        visible = [row for row in range(table.rowCount()) if not table.isRowHidden(row)]
        observed.append([(table.item(row, 0).text(), table.item(row, 1).text()) for row in visible])
        assert table.currentRow() == visible[0]
        dialog.reject()
    def fill_editor():
        dialog = app.activeModalWidget()
        category = dialog.findChild(QComboBox, "catalog_category")
        category.setCurrentIndex(category.findData("04"))
        next(b for b in dialog.findChildren(QPushButton) if b.text() == "预览修改").click()
        QTimer.singleShot(50, inspect_updated)
    def inspect():
        dialog = app.activeModalWidget()
        category = dialog.findChild(QComboBox, "catalog_category_filter")
        category.setCurrentIndex(category.findData("01"))
        dialog.findChild(QTableWidget).selectRow(0)
        QTimer.singleShot(50, fill_editor)
        next(b for b in dialog.findChildren(QPushButton) if b.text() == "修改选中曲目的曲名和分类").click()
    QTimer.singleShot(50, inspect)
    window.show_archived_catalog()
    assert observed == [[("04", "001")]]
    assert len(list((root / "按曲目分类" / "04001天空之城（03）").glob("*.pdf"))) == 3
    window.close()
    app.processEvents()


def test_empty_catalog_category_filter_and_counts(tmp_path):
    app = QApplication.instance() or QApplication([])
    db = Database(tmp_path / "empty.sqlite3")
    db.initialize()
    db.upsert_category("10", "自定义风格", False)
    window = MainWindow(db)
    def inspect():
        dialog = app.activeModalWidget()
        category = dialog.findChild(QComboBox, "catalog_category_filter")
        assert category.itemText(category.findData("10")) == "10 自定义风格"
        category.setCurrentIndex(category.findData("10"))
        assert dialog.findChild(QLabel, "catalog_count").text() == "当前 0 首 / 全库 0 首"
        dialog.reject()
    QTimer.singleShot(50, inspect)
    window.show_archived_catalog()
    assert db.count_songs() == 0
    window.close()
    app.processEvents()
