from contextlib import closing, contextmanager
import json
from pathlib import Path
import sqlite3

import pytest

from score_organizer.services.grouping_service import GroupingService, warning_fingerprint
from score_organizer.services.import_service import ImportService
from score_organizer.services.library_relocation import prepare_relocated_library
from score_organizer.storage.database import Database
from score_organizer.storage.writer_guard import register_format_one_writer, require_format_one_guards
from test_library_upgrade_snapshot import make_library, db_state


class FormatOneWarningStore(Database):
    """Test-only connection adapter; production Database must still reject v1.

    Exercise the unchanged real replace_batch_warnings method against the new
    candidate, with its full guard definitions and connection capability checked.
    """
    @contextmanager
    def session(self):
        with closing(sqlite3.connect(self.path)) as connection:
            connection.row_factory = sqlite3.Row
            register_format_one_writer(connection)
            connection.execute("PRAGMA foreign_keys=ON")
            connection.execute("BEGIN")
            require_format_one_guards(connection)
            try:
                yield connection
                connection.commit()
            except BaseException:
                connection.rollback()
                raise


def warning_library(tmp_path):
    db, root, sources = make_library(tmp_path)
    batch = ImportService(db).import_paths([sources[0]])
    GroupingService(db).refresh_warnings(batch)
    warnings = db.list_warnings(batch)
    assert warnings and any(row["code"] == "duplicate_existing_file" for row in warnings)
    return db, root, batch, warnings[0]


@pytest.mark.parametrize("state", ["待处理", "已忽略", "已解决", "已确认重复"])
def test_real_warning_refresh_keeps_decision_id_resolution_after_paths_change(tmp_path, state):
    db, _, batch, warning = warning_library(tmp_path)
    resolution = "用户已核对，保留原决定"
    with db.transaction() as connection:
        connection.execute("UPDATE warning SET state=?,resolution=? WHERE id=?", (state, resolution, warning["id"]))
    before = db_state(db.path)
    target = tmp_path / "candidate"
    proof = json.loads(prepare_relocated_library(db.path, target).read_text("utf-8"))
    store = FormatOneWarningStore(target / "runtime/library.sqlite3")
    migrated = {row["id"]: dict(row) for row in store.list_warnings(batch)}[warning["id"]]
    assert migrated["fingerprint"] != warning["fingerprint"]
    assert migrated["fingerprint"] == warning_fingerprint(migrated["code"], json.loads(migrated["details"]))
    assert migrated["state"] == state and migrated["resolution"] == resolution
    assert any(item["id"] == warning["id"] and item["old"] == warning["fingerprint"]
               and item["new"] == migrated["fingerprint"] for item in proof["warningFingerprintChanges"])
    # Use the actual ordinary warning builder and persistence method. Replacing
    # freshly computed warnings must match the migrated identities and decisions.
    refreshed = [GroupingService(store)._warning(row["code"], row["severity"], json.loads(row["details"]))
                 for row in store.list_warnings(batch)]
    store.replace_batch_warnings(batch, refreshed)
    after = {row["id"]: dict(row) for row in store.list_warnings(batch)}
    assert warning["id"] in after
    assert (after[warning["id"]]["state"], after[warning["id"]]["resolution"]) == (state, resolution)
    assert db_state(db.path) == before
    assert not proof["readyForActivation"]
    with pytest.raises(ValueError, match="不支持"):
        Database(store.path).connect()


def test_legacy_null_fingerprint_is_recovered_without_dropping_decision(tmp_path):
    db, _, batch, warning = warning_library(tmp_path)
    with db.transaction() as connection:
        connection.execute("UPDATE warning SET fingerprint=NULL,state='已忽略',resolution='旧版本已确认' WHERE id=?", (warning["id"],))
    target = tmp_path / "candidate"
    proof = json.loads(prepare_relocated_library(db.path, target).read_text("utf-8"))
    store = FormatOneWarningStore(target / "runtime/library.sqlite3")
    row = next(row for row in store.list_warnings(batch) if row["id"] == warning["id"])
    assert row["fingerprint"] == warning_fingerprint(row["code"], json.loads(row["details"]))
    assert (row["state"], row["resolution"]) == ("已忽略", "旧版本已确认")
    assert any(item["id"] == row["id"] and item["old"] is None for item in proof["warningFingerprintChanges"])


def test_details_without_locators_keep_exact_text_and_existing_identity(tmp_path):
    db, _, _, _ = warning_library(tmp_path)
    details = '{ "description" : "这是纯历史说明 C:/old/file.pdf", "count" : 3 }'
    fingerprint = warning_fingerprint("descriptive", json.loads(details))
    with db.transaction() as connection:
        cursor = connection.execute("INSERT INTO warning(code,fingerprint,severity,state,details) VALUES(?,?,?,?,?)",
                                    ("descriptive", fingerprint, "warning", "已解决", details))
        identity = cursor.lastrowid
    target = tmp_path / "candidate"
    proof = json.loads(prepare_relocated_library(db.path, target).read_text("utf-8"))
    with closing(sqlite3.connect(target / "runtime/library.sqlite3")) as connection:
        assert connection.execute("SELECT details,fingerprint,state FROM warning WHERE id=?", (identity,)).fetchone() == (details, fingerprint, "已解决")
    assert not any(item["id"] == identity for item in proof["warningFingerprintChanges"])


@pytest.mark.parametrize("fingerprint", ["custom-value", "0" * 64, "", "A" * 64])
def test_unknown_identity_cannot_silently_reset_a_decision(tmp_path, fingerprint):
    db, _, _, warning = warning_library(tmp_path)
    with db.transaction() as connection:
        connection.execute("UPDATE warning SET fingerprint=?,state='已忽略' WHERE id=?", (fingerprint, warning["id"]))
    before = db_state(db.path)
    target = tmp_path / "candidate"
    with pytest.raises(ValueError, match="标识与原详情不匹配"):
        prepare_relocated_library(db.path, target)
    assert not (target / "prepared.json").exists() and db_state(db.path) == before
    with closing(sqlite3.connect(target / "runtime/library.sqlite3")) as connection:
        assert connection.execute("SELECT fingerprint,state FROM warning WHERE id=?", (warning["id"],)).fetchone() == (fingerprint, "已忽略")


def test_ambiguous_duplicate_identities_are_blocked_instead_of_merging_decisions(tmp_path):
    db, _, batch, warning = warning_library(tmp_path)
    with db.transaction() as connection:
        connection.execute("INSERT INTO warning(batch_id,code,fingerprint,severity,state,details,resolution) VALUES(?,?,?,?,?,?,?)",
                           (batch, warning["code"], None, warning["severity"], "已忽略", warning["details"], "另一个独立决定"))
    before = db_state(db.path)
    target = tmp_path / "candidate"
    with pytest.raises(ValueError, match="重复标识"):
        prepare_relocated_library(db.path, target)
    assert not (target / "prepared.json").exists() and db_state(db.path) == before


def test_same_identity_in_another_batch_retains_each_separate_decision(tmp_path):
    db, _, batch, warning = warning_library(tmp_path)
    other = db.create_import_batch()
    with db.transaction() as connection:
        connection.execute("INSERT INTO warning(batch_id,code,fingerprint,severity,state,details,resolution) VALUES(?,?,?,?,?,?,?)",
                           (other, warning["code"], warning["fingerprint"], warning["severity"], "已忽略", warning["details"], "第二批决定"))
    target = tmp_path / "candidate"
    prepare_relocated_library(db.path, target)
    store = FormatOneWarningStore(target / "runtime/library.sqlite3")
    row = store.list_warnings(other)[0]
    first = next(item for item in store.list_warnings(batch) if item["id"] == warning["id"])
    assert row["fingerprint"] == first["fingerprint"]
    assert row["id"] != first["id"] and row["state"] == "已忽略" and row["resolution"] == "第二批决定"


def test_original_json_key_order_does_not_make_a_valid_identity_unknown(tmp_path):
    db, _, _, warning = warning_library(tmp_path)
    details = dict(reversed(list(json.loads(warning["details"]).items())))
    with db.transaction() as connection:
        connection.execute("UPDATE warning SET details=? WHERE id=?", (json.dumps(details, ensure_ascii=False, indent=2), warning["id"]))
    proof = json.loads(prepare_relocated_library(db.path, tmp_path / "candidate").read_text("utf-8"))
    assert proof["warningFingerprintsReconciled"]


def test_identity_trigger_cannot_change_the_users_resolution_during_migration(tmp_path):
    db, _, _, warning = warning_library(tmp_path)
    with db.transaction() as connection:
        connection.execute("CREATE TRIGGER unexpected_identity_effect AFTER UPDATE OF fingerprint ON warning BEGIN UPDATE warning SET resolution='unexpected' WHERE id=new.id; END")
    before = db_state(db.path)
    target = tmp_path / "candidate"
    with pytest.raises(ValueError, match="其他记录"):
        prepare_relocated_library(db.path, target)
    assert not (target / "prepared.json").exists() and db_state(db.path) == before
    with closing(sqlite3.connect(target / "runtime/library.sqlite3")) as connection:
        assert connection.execute("SELECT resolution FROM warning WHERE id=?", (warning["id"],)).fetchone() == (warning["resolution"],)
