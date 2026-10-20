"""Repeatable local benchmark; only creates isolated data under project work/."""
import argparse
import cProfile
import hashlib
import json
import os
import pstats
import time
import uuid
from pathlib import Path

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
from PySide6.QtWidgets import QApplication
from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.correction_service import CorrectionService
from score_organizer.services.grouping_service import GroupingService
from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.services.report_service import ReportService
from score_organizer.storage.database import Database
from score_organizer.ui.main_window import MainWindow


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--label", required=True)
    parser.add_argument("--songs", type=int, default=150)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1] / "work" / "save-benchmark" / uuid.uuid4().hex
    root.mkdir(parents=True)
    sources = root / "sources"
    sources.mkdir()
    paths = []
    for song in range(args.songs):
        title = hashlib.sha256(f"song-{song}".encode()).hexdigest()[:24]
        for code in ("01", "02", "03"):
            path = sources / f"{code}01{title}.pdf"
            path.write_bytes(b"%PDF-1.4\n" + f"song-{song}-{code}\n".encode() + b"x" * 65536)
            paths.append(path)
    db = Database(root / "test.sqlite3")
    db.initialize()
    db.set_setting("output_root", str(root / "output"))
    connect = db.connect
    connections = [0]
    def counted():
        connections[0] += 1
        return connect()
    db.connect = counted
    metrics = {}
    def measure(name, call):
        before = connections[0]
        start = time.perf_counter()
        result = call()
        metrics[name] = {"seconds": round(time.perf_counter() - start, 4), "connections": connections[0] - before}
        return result
    profile = cProfile.Profile()
    profile.enable()
    batch = measure("import_files", lambda: ImportService(db).import_paths(paths))
    measure("warnings", lambda: GroupingService(db).refresh_warnings(batch))
    measure("reports", lambda: ReportService(db).build_song_reports(batch))
    app = QApplication.instance() or QApplication([])
    window = measure("window_load", lambda: MainWindow(db))
    ids = {row["id"] for row in db.list_import_items(batch) if row["source_path"] in {str(path) for path in paths[:3]}}
    def correction():
        service = CorrectionService(db)
        service.apply(service.build_plan(batch, ids))
    measure("save_correction", correction)
    def refresh():
        if hasattr(window, "refresh_draft_view"):
            window.refresh_draft_view()
            return
        window.refresh_warnings(batch)
        window.refresh_import_items()
        window.refresh_song_reports()
    measure("refresh_after_save", refresh)
    archive = ArchiveService(db, root / "output")
    plan = measure("archive_preview", lambda: archive.build_plan(batch, ids))
    assert len(plan) == 1 and len(plan[0].files) == 3
    measure("archive_commit", lambda: archive.commit_plan(batch, plan))
    measure("refresh_after_archive", refresh)
    profile.disable()
    for file_plan in plan[0].files:
        assert sha256_file(file_plan.source_path) == file_plan.source_sha256
        assert all(sha256_file(target) == file_plan.source_sha256 for target in file_plan.targets)
    assert len(db.list_import_items(batch)) == len(paths) - 3
    window.close()
    app.processEvents()
    metrics["total_seconds"] = round(sum(value["seconds"] for value in metrics.values()), 4)
    metrics["label"] = args.label
    metrics["files"] = len(paths)
    metrics["root"] = str(root)
    (root / "metrics.json").write_text(json.dumps(metrics, indent=2), encoding="utf-8")
    profile.dump_stats(str(root / "profile.pstats"))
    print(json.dumps(metrics, indent=2))
    pstats.Stats(profile).strip_dirs().sort_stats("cumulative").print_stats(12)


if __name__ == "__main__":
    main()
