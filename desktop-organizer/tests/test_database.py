import sqlite3
import unittest
import json

from score_organizer.storage.database import Database


class DatabaseTests(unittest.TestCase):
    def test_database_initializes_instruments_and_default_category(self):
        with self.subTest("initial categories"):
            with self._database() as db:
                categories = db.list_categories()
                self.assertEqual(
                    [(row["code"], row["name"]) for row in categories],
                    [
                        ("01", "久石让"),
                        ("02", "电影音乐"),
                        ("03", "中国风格"),
                        ("04", "迪士尼"),
                        ("05", "古典"),
                        ("06", "游戏动漫"),
                        ("07", "周宏德"),
                        ("08", "杂项"),
                        ("09", "三重奏"),
                    ],
                )

    def test_database_rejects_duplicate_sequence_across_categories(self):
        with self._database() as db:
            db.upsert_category("02", "迪士尼")
            db.create_song("01", "001", "天空之城", status="archived")
            with self.assertRaises(sqlite3.IntegrityError):
                db.create_song("02", "001", "示例曲目", status="archived")

    def test_allocate_next_sequence_fills_global_gap(self):
        with self._database() as db:
            db.upsert_category("02", "迪士尼")
            db.create_song("01", "001", "天空之城", status="archived")
            db.create_song("02", "007", "示例曲目", status="archived")
            self.assertEqual(db.allocate_next_sequence(), "002")

    def test_settings_persist(self):
        with self._database() as db:
            db.set_setting("output_root", "D:/乐谱库输出")
            self.assertEqual(db.get_setting("output_root"), "D:/乐谱库输出")
            self.assertEqual(db.get_setting("missing", "fallback"), "fallback")

    def test_import_items_persist(self):
        with self._database() as db:
            batch_id = db.create_import_batch()
            item_id = db.add_import_item(
                batch_id,
                "C:/scores/0101天空之城.pdf",
                "abc123",
                "recognized",
                {"format": "old", "title": "天空之城"},
            )
            items = db.list_import_items(batch_id)
            self.assertEqual(len(items), 1)
            self.assertEqual(items[0]["source_sha256"], "abc123")
            self.assertEqual(json.loads(items[0]["parsed_fields"])["title"], "天空之城")
            self.assertEqual(items[0]["id"], item_id)

    def test_manual_entry_updates_import_item(self):
        with self._database() as db:
            batch_id = db.create_import_batch()
            item_id = db.add_import_item(
                batch_id,
                "C:/scores/天空之城.pdf",
                "abc123",
                "needs_manual_entry",
                {"format": "invalid", "reason": "文件名编号无效"},
            )
            db.update_import_item_manual_fields(item_id, "01", "01", "天空之城")
            item = db.list_import_items(batch_id)[0]
            fields = json.loads(item["parsed_fields"])
            self.assertEqual(item["status"], "recognized")
            self.assertEqual(item["decision"], "manual_entry")
            self.assertEqual(fields["instrument_code"], "01")
            self.assertEqual(fields["category_code"], "01")
            self.assertEqual(fields["title"], "天空之城")

    def test_import_batch_summary_counts_statuses(self):
        with self._database() as db:
            batch_id = db.create_import_batch()
            db.add_import_item(batch_id, "a.pdf", "1", "recognized", {})
            db.add_import_item(batch_id, "b.pdf", "2", "needs_manual_entry", {})
            db.add_import_item(batch_id, "c.txt", None, "skipped", {})
            self.assertEqual(
                db.import_batch_summary(batch_id),
                {"total": 3, "recognized": 1, "needs_manual_entry": 1, "skipped": 1},
            )

    def test_latest_draft_batch_ignores_archived_batches(self):
        with self._database() as db:
            archived_id = db.create_import_batch()
            db.add_import_item(archived_id, "old.pdf", "1", "recognized", {})
            db.mark_import_batch_archived(archived_id)
            draft_id = db.create_import_batch()
            db.add_import_item(draft_id, "new.pdf", "2", "recognized", {})

            self.assertEqual(db.latest_draft_batch_id(), draft_id)
            self.assertEqual(db.latest_import_batch_id(), draft_id)

    def test_clear_draft_batch_removes_only_unarchived_import_records(self):
        with self._database() as db:
            draft_id = db.create_import_batch()
            db.add_import_item(draft_id, "draft.pdf", "1", "recognized", {})

            removed_count = db.clear_draft_batch(draft_id)

            self.assertEqual(removed_count, 1)
            self.assertIsNone(db.latest_draft_batch_id())
            self.assertEqual(db.list_import_items(draft_id), [])

    def test_clear_draft_batch_rejects_archived_batch(self):
        with self._database() as db:
            batch_id = db.create_import_batch()
            db.add_import_item(batch_id, "archived.pdf", "1", "recognized", {})
            db.mark_import_batch_archived(batch_id)

            with self.assertRaises(ValueError):
                db.clear_draft_batch(batch_id)

    def test_remove_import_items_from_draft_keeps_remaining_items(self):
        with self._database() as db:
            batch_id = db.create_import_batch()
            first_id = db.add_import_item(batch_id, "ready.pdf", "1", "recognized", {})
            second_id = db.add_import_item(batch_id, "waiting.pdf", "2", "recognized", {})

            remaining = db.remove_import_items_from_draft(batch_id, [first_id])

            self.assertEqual(remaining, 1)
            self.assertEqual(db.latest_draft_batch_id(), batch_id)
            self.assertEqual([item["id"] for item in db.list_import_items(batch_id)], [second_id])

    def _database(self):
        import tempfile
        from pathlib import Path

        class TempDatabase:
            def __enter__(inner_self):
                root = Path.cwd() / "work" / "test-db"
                root.mkdir(parents=True, exist_ok=True)
                inner_self.directory = tempfile.TemporaryDirectory(dir=root)
                db = Database(Path(inner_self.directory.name) / "scores.sqlite3")
                db.initialize()
                return db

            def __exit__(inner_self, exc_type, exc, tb):
                inner_self.directory.cleanup()

        return TempDatabase()


if __name__ == "__main__":
    unittest.main()
