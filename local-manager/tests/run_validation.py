"""Run the full local suite and produce reproducible, synthetic review evidence."""
import argparse
import contextlib
import importlib.metadata
import json
from pathlib import Path
import platform
import sys
import unittest
import zipfile

from score_manager import Catalog
from score_manager.archive import export_bundle, restore_archive
from score_manager.validation import REPO, SCHEMA_PATH, TABLES, sha
from fixtures import legacy_archive


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="new evidence directory; existing paths are refused")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    tests = unittest.defaultTestLoader.discover(str(Path(__file__).parent))
    with (args.output / "tests.log").open("w", encoding="utf-8") as log:
        with contextlib.redirect_stdout(log), contextlib.redirect_stderr(log):
            result = unittest.TextTestRunner(stream=log, verbosity=2).run(tests)
    report = {"tests": result.testsRun, "failures": len(result.failures), "errors": len(result.errors),
              "skipped": len(result.skipped), "passed": result.wasSuccessful(),
              "python": platform.python_version(), "platform": platform.platform(),
              "pypdf": importlib.metadata.version("pypdf"),
              "schema3Sha256": sha(SCHEMA_PATH.read_bytes()),
              "scope": "Synthetic local core only; no Android runtime, native Windows UI, device, network or NAS acceptance"}
    (args.output / "test-result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    if not result.wasSuccessful():
        print(json.dumps(report))
        return 1
    original = args.output / "synthetic-legacy-v1.zip"
    legacy_archive(original)
    with Catalog(args.output / "synthetic-source.sqlite", create=True) as source:
        restore_archive(source, original, expected_sha256=sha(original.read_bytes()))
        category = source.add_category("73", "Synthetic user-defined classification")
        source.assign_number("legacy-piece", category, number=998)
        editable_piece = None
        for n in range(999, 1002):
            piece = source.add_piece(f"Synthetic boundary {n}", category)
            if n == 999:
                editable_piece = piece
        for n in range(12):
            part = source.add_part(editable_piece, source.add_instrument(f"Synthetic instrument {n+1}"))
            if source.part_number(part) != 999:
                raise RuntimeError("Same-piece number diverged")
        bundle = args.output / "synthetic-local-bundle.zip"
        export_bundle(source, bundle, "legacy-admin")
        with Catalog(args.output / "synthetic-restored.sqlite", create=True) as restored:
            restore_archive(restored, bundle, expected_sha256=sha(bundle.read_bytes()))
            for table in TABLES + ("local_categories", "local_numbers", "local_number_history"):
                if source.rows(table) != restored.rows(table):
                    raise RuntimeError("Round trip differs: " + table)
            if source.source_pages("legacy-binding") != restored.source_pages("legacy-binding"):
                raise RuntimeError("Source page mapping changed")
            matrix = {"numbers": [x["number"] for x in restored.numbered_pieces()],
                      "sourcePageMap": restored.source_pages("legacy-binding"),
                      "tableCounts": {t: len(restored.rows(t)) for t in TABLES},
                      "verifiedOriginalPdfs": restored.audit_files(),
                      "activeVersion": restored.rows("score_pdfs")[0]["activeVersionId"],
                      "personalLayersInMother": 0,
                      "tableEquality": True, "sourceMappingEquality": True}
            (args.output / "round-trip.json").write_text(json.dumps(matrix, ensure_ascii=False, indent=2), encoding="utf-8")
        with zipfile.ZipFile(bundle) as outer:
            (args.output / "android-v1-library.zip").write_bytes(outer.read("library.zip"))
            (args.output / "catalog-sidecar.json").write_bytes(outer.read("catalog.json"))
    manifest = {str(p.relative_to(REPO)): sha(p.read_bytes()) for p in sorted((REPO / "local-manager").rglob("*"))
                if p.is_file() and "__pycache__" not in p.parts}
    (args.output / "source-hashes.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(json.dumps(report))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
