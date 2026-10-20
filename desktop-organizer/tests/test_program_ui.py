import json
import time

import pytest
from PySide6.QtCore import QTimer
from PySide6.QtWidgets import QApplication, QDialog, QDialogButtonBox, QFileDialog, QMessageBox

from score_organizer.ui.program_dialog import ProgramDialog
from score_organizer.ui.main_window import MainWindow
from test_catalog_filter import catalog_fixture


@pytest.mark.parametrize('accept', [False, True])
def test_native_search_category_orders_preview_and_export(tmp_path, accept):
    app = QApplication.instance() or QApplication([])
    db, _, _ = catalog_fixture(tmp_path)
    dialog = ProgramDialog(db)
    dialog.show()
    dialog.search.setText('天空')
    assert [r for r in range(3) if not dialog.results.isRowHidden(r)] == [0]
    dialog.results.selectRow(0)
    dialog.add_selected()
    dialog.search.setText('电影')
    assert [r for r in range(3) if not dialog.results.isRowHidden(r)] == [1]
    dialog.results.selectRow(1)
    dialog.add_selected()
    dialog.add_selected()
    assert len(dialog.entries) == 2
    dialog.search.clear()
    dialog.category.setCurrentIndex(dialog.category.findData('03'))
    assert [r for r in range(3) if not dialog.results.isRowHidden(r)] == [2]
    dialog.program.cellWidget(0, 0).setValue(8)
    dialog.program.cellWidget(1, 0).setValue(2)
    dialog.sort_orders()
    assert [e['order'] for e in dialog.entries] == [2, 8]
    dialog.program.selectRow(1)
    dialog.move(-1)
    assert [e['title'] for e in dialog.entries] == ['天空之城', '哈利波特01']
    dialog.program.selectRow(1)
    dialog.remove_selected()
    assert len(dialog.entries) == 1
    dialog.name.setText('测试音乐会')
    dialog.output.setText(str(tmp_path))
    dialog.resize(900, 650)
    app.processEvents()
    dialog.grab().save(str(tmp_path / 'program-900.png'))
    assert dialog.search.y() < 100
    dialog.resize(1300, 800)
    app.processEvents()
    dialog.grab().save(str(tmp_path / 'program-1300.png'))
    errors, inspected = [], []
    def inspect_preview():
        modal = app.activeModalWidget()
        if modal is None or modal.windowTitle() != '确认节目单打包':
            QTimer.singleShot(20, inspect_preview)
            return
        try:
            inspected.append(True)
            modal.grab().save(str(tmp_path / 'program-preview.png'))
            buttons = modal.findChild(QDialogButtonBox)
            buttons.button(QDialogButtonBox.Ok if accept else QDialogButtonBox.Cancel).click()
        except Exception as exc:
            errors.append(exc)
            modal.reject()
    QTimer.singleShot(50, inspect_preview)
    dialog.preview()
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        app.processEvents()
        if inspected and dialog.export.isEnabled() and (not accept or dialog.result_path):
            break
        time.sleep(0.02)
    assert not errors
    assert inspected
    assert dialog.export.isEnabled(), (dialog.status.text(), dialog.worker.isRunning(), dialog.result_path)
    output = tmp_path / '测试音乐会'
    assert output.exists() == accept
    if accept:
        assert len(list(output.rglob('*.pdf'))) == 3
    dialog.reject()
    app.processEvents()


def test_save_load_program_and_reject_stale_identity(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, _, _ = catalog_fixture(tmp_path)
    dialog = ProgramDialog(db)
    dialog.results.selectRow(0)
    dialog.add_selected()
    dialog.program.cellWidget(0, 0).setValue(5)
    path = tmp_path / 'saved.json'
    monkeypatch.setattr(QFileDialog, 'getSaveFileName', lambda *_: (str(path), ''))
    monkeypatch.setattr(QFileDialog, 'getOpenFileName', lambda *_: (str(path), ''))
    dialog.save_program()
    dialog.entries.clear()
    dialog.populate_program()
    dialog.load_program()
    assert [(e['title'], e['order']) for e in dialog.entries] == [('天空之城', 5)]
    data = json.loads(path.read_text(encoding='utf-8'))
    data['entries'][0]['library_seq'] = '999'
    path.write_text(json.dumps(data), encoding='utf-8')
    errors = []
    monkeypatch.setattr(QMessageBox, 'warning', lambda *args: errors.append(args[-1]))
    dialog.load_program()
    assert len(errors) == 1 and '当前库' in errors[0]
    assert dialog.entries[0]['order'] == 5
    dialog.close()
    app.processEvents()


def test_top_menu_entry_opens_program_dialog(tmp_path):
    app = QApplication.instance() or QApplication([])
    db, _, _ = catalog_fixture(tmp_path)
    window = MainWindow(db)
    errors = []
    def inspect():
        modal = app.activeModalWidget()
        try:
            assert isinstance(modal, ProgramDialog)
        except Exception as exc:
            errors.append(exc)
        finally:
            if modal:
                modal.reject()
    QTimer.singleShot(50, inspect)
    window.menu_actions['program_pack'].trigger()
    assert not errors
    window.close()
    app.processEvents()


@pytest.mark.parametrize('data', [[], {'format': 'score-program-v1', 'name': [], 'entries': []},
                                 {'format': 'score-program-v1', 'name': '演出', 'entries': [5]}])
def test_malformed_program_preserves_current_list(tmp_path, monkeypatch, data):
    app = QApplication.instance() or QApplication([])
    db, _, _ = catalog_fixture(tmp_path)
    dialog = ProgramDialog(db)
    dialog.results.selectRow(0)
    dialog.add_selected()
    path = tmp_path / 'bad.json'
    path.write_text(json.dumps(data), encoding='utf-8')
    monkeypatch.setattr(QFileDialog, 'getOpenFileName', lambda *_: (str(path), ''))
    errors = []
    monkeypatch.setattr(QMessageBox, 'warning', lambda *args: errors.append(args[-1]))
    dialog.load_program()
    assert len(errors) == 1
    assert len(dialog.entries) == 1 and dialog.entries[0]['title'] == '天空之城'
    dialog.close()
    app.processEvents()
