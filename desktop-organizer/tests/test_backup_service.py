from score_organizer.services.backup_service import BackupService
from score_organizer.storage.database import Database


def test_backup_and_restore_preserves_song_number(tmp_path):
    db_path = tmp_path / "scores.sqlite3"
    db = Database(db_path)
    db.initialize()
    db.create_song("01", "001", "天空之城", status="archived")

    backup = BackupService(db).create_backup(tmp_path / "backup")
    changed = Database(db_path)
    changed.initialize()
    changed.create_song("02", "002", "另一首", status="archived")

    BackupService(changed).restore_backup(backup)
    restored = Database(db_path)
    restored.initialize()

    assert restored.count_songs() == 1
    assert restored.allocate_next_sequence() == "002"
