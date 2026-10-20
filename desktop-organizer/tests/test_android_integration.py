import importlib.util
import json
import sqlite3
from pathlib import Path
import pytest

spec = importlib.util.spec_from_file_location('android_rules_export', Path(__file__).parents[1] / 'android-integration/export_rules.py')
exporter = importlib.util.module_from_spec(spec); spec.loader.exec_module(exporter)

def test_export_is_read_only_preserves_custom_codes_and_never_overwrites(tmp_path):
    db = tmp_path / 'source.sqlite3'
    with sqlite3.connect(db) as connection:
        for table in ('instrument', 'category'):
            connection.execute(f'CREATE TABLE {table}(code TEXT,name TEXT,active INTEGER)')
        connection.executemany('INSERT INTO instrument VALUES(?,?,?)', [('21','长笛',1),('01','旧声部',0)])
        connection.execute('INSERT INTO category VALUES(?,?,?)', ('37','自定义分类',1))
    before = db.read_bytes()
    result = tmp_path / 'rules.json'
    exporter.export_rules(db, result)
    rules = json.loads(result.read_text(encoding='utf8'))
    assert rules['instruments'] == {'21':'长笛'}
    assert rules['categories'] == {'37':'自定义分类'}
    assert rules['sequenceDigits'] == 3
    assert db.read_bytes() == before
    with pytest.raises(FileExistsError): exporter.export_rules(db, result)

def test_companion_data_directory_is_optional_and_keeps_legacy_default(monkeypatch, tmp_path):
    from score_organizer.main import default_db_path
    monkeypatch.delenv('SCORE_ORGANIZER_DATA_DIR', raising=False)
    original = default_db_path()
    monkeypatch.setenv('SCORE_ORGANIZER_DATA_DIR', str(tmp_path))
    assert default_db_path() == tmp_path / 'score_organizer.sqlite3'
    monkeypatch.delenv('SCORE_ORGANIZER_DATA_DIR')
    assert default_db_path() == original

def test_android_seed_dictionary_matches_desktop_defaults():
    from score_organizer.storage.database import INITIAL_INSTRUMENTS, INITIAL_CATEGORIES
    contract = json.loads((Path(__file__).parents[1] / 'android-integration/default-rules.json').read_text(encoding='utf8'))
    assert contract['instruments'] == dict(INITIAL_INSTRUMENTS)
    assert contract['categories'] == dict(INITIAL_CATEGORIES)
    android_path = Path(__file__).parents[2] / 'app/src/main/assets/score-organizer-rules.json'
    assert json.loads(android_path.read_text(encoding='utf8')) == contract

def test_export_menu_writes_dictionary_and_cancel_does_not_write(tmp_path, monkeypatch):
    from PySide6.QtWidgets import QApplication, QFileDialog, QMessageBox
    from score_organizer.storage.database import Database
    from score_organizer.ui.main_window import MainWindow
    app = QApplication.instance() or QApplication([])
    db = Database(tmp_path / 'local.sqlite3'); db.initialize()
    window = MainWindow(db)
    messages = []
    monkeypatch.setattr(QMessageBox, 'information', lambda *args: messages.append(args[2]))
    output = tmp_path / 'android-rules.json'
    before = db.path.read_bytes()
    monkeypatch.setattr(QFileDialog, 'getSaveFileName', lambda *args: ('', ''))
    window.menu_actions['android_rules_export'].trigger()
    assert not output.exists()
    monkeypatch.setattr(QFileDialog, 'getSaveFileName', lambda *args: (str(output), 'JSON'))
    window.menu_actions['android_rules_export'].trigger()
    assert json.loads(output.read_text(encoding='utf8'))['categories']['01'] == '久石让'
    assert db.path.read_bytes() == before
    assert len(messages) == 1
    window.close(); app.processEvents()
