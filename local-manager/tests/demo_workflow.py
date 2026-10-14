"""Execute the real CLI with synthetic inputs and save a machine-checkable transcript."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile

from fixtures import legacy_archive, write_pdf
from score_manager import Catalog
from score_manager.validation import sha
from score_manager.workflow import ASSET_TABLES, digest


def demonstrate(root):
    root.mkdir(parents=True, exist_ok=False)
    transcript = []
    live = root / "live.sqlite"
    source = root / "synthetic-legacy.zip"
    legacy_archive(source)
    env = {**os.environ, "PYTHONPATH": str(Path(__file__).resolve().parents[1])}

    def cli(db, *args, expected=0):
        argv = [sys.executable, "-m", "score_manager", "--db", str(db), *args]
        run = subprocess.run(argv, env=env, capture_output=True, text=True, timeout=90)
        transcript.append({"argv": argv, "returncode": run.returncode, "stdout": run.stdout, "stderr": run.stderr})
        (root / "cli-transcript.json").write_text(json.dumps(transcript, ensure_ascii=False, indent=2), encoding="utf-8")
        assert run.returncode == expected, transcript[-1]
        return json.loads(run.stdout) if expected == 0 else None

    def edit(db, draft, action, request, **arguments):
        return cli(db, "edit", draft, action, "--request-id", request, "--args", json.dumps(arguments))["result"]

    def review(db, draft):
        return cli(db, "review", draft, "--member", "legacy-player")

    def confirm(db, draft, reviewed):
        return cli(db, "confirm", draft, "--member", "legacy-player", "--review-hash", reviewed["confirmationHash"], "--yes")

    def state(db):
        with Catalog(db) as c:
            return digest(c)

    cli(live, "init", "--confirm-create")
    seed = cli(live, "begin")["draftId"]
    legacy = edit(live, seed, "restore_archive", "restore", source=str(source), expected_sha256=sha(source.read_bytes()))
    assert legacy["recoveryScope"] == "ANDROID_V1_ONLY_NO_NUMBERING_CATEGORIES_HISTORY"
    edit(live, seed, "assign_number", "number", piece_id="legacy-piece", number=998)
    confirm(live, seed, review(live, seed))

    before = state(live)
    draft = cli(live, "begin")["draftId"]
    pdf_path = write_pdf(root, "synthetic-new.pdf", pages=5)
    pdf = edit(live, draft, "import_pdf", "pdf", path=str(pdf_path))
    category = edit(live, draft, "add_category", "category", code="73", name="Synthetic user category")
    piece = edit(live, draft, "add_piece", "piece", title="Synthetic CLI piece", category_id=category)
    part = edit(live, draft, "add_part", "part", piece_id=piece, instrument_id="legacy-instrument")
    binding = edit(live, draft, "bind", "binding", part_id=part, pdf_id=pdf["scorePdfId"], start_page=2, end_page=3)
    reviewed = review(live, draft)
    assert state(live) == before
    visible = next(p for p in reviewed["memberPreview"]["pieces"] if p["pieceId"] == piece)
    assert visible["number"] == 999
    assert visible["visibleParts"][0]["segments"][0]["sourcePages"] == [1, 2]
    cli(live, "confirm", draft, "--member", "legacy-player", "--review-hash", reviewed["confirmationHash"], expected=2)
    assert state(live) == before  # Missing explicit --yes cannot save.
    first = confirm(live, draft, reviewed)
    committed = state(live)
    assert confirm(live, draft, reviewed) == first
    assert cli(live, "status", draft) == first
    assert state(live) == committed

    cancelled = cli(live, "begin")["draftId"]
    edit(live, cancelled, "add_piece", "unused", title="Never committed")
    cli(live, "cancel", cancelled)
    assert state(live) == committed
    cli(live, "review", cancelled, "--member", "legacy-player", expected=2)
    pending_path = live.parent / (live.name + ".drafts") / (cancelled + ".sqlite")
    cli(pending_path, "export", "--owner", "legacy-admin", "--output", str(root / "forbidden.zip"), "--confirm-complete-bundle", expected=2)
    assert not (root / "forbidden.zip").exists()

    bundle = root / "synthetic-complete-bundle.zip"
    exported = cli(live, "export", "--owner", "legacy-admin", "--output", str(bundle), "--confirm-complete-bundle")
    assert exported["bundleKind"] == "COMPLETE_LOCAL_BUNDLE"
    restored = root / "restored.sqlite"
    cli(restored, "init", "--confirm-create")
    restore_draft = cli(restored, "begin")["draftId"]
    restored_result = edit(restored, restore_draft, "restore_archive", "restore", source=str(bundle), expected_sha256=sha(bundle.read_bytes()))
    assert restored_result["recoveryScope"] == "COMPLETE_LOCAL_BUNDLE"
    with Catalog(restored) as c:
        assert not c.rows("pieces")
    confirm(restored, restore_draft, review(restored, restore_draft))
    with Catalog(live) as a, Catalog(restored) as b:
        assert digest(a) == digest(b)
        counts = {t: len(b.rows(t)) for t in ASSET_TABLES}
        assert a.source_pages(binding) == b.source_pages(binding)
    with zipfile.ZipFile(bundle) as z:
        child = root / "android-v1-only.zip"
        child.write_bytes(z.read("library.zip"))
    legacy_db = root / "legacy-only.sqlite"
    cli(legacy_db, "init", "--confirm-create")
    child_draft = cli(legacy_db, "begin")["draftId"]
    child_result = edit(legacy_db, child_draft, "restore_archive", "child", source=str(child), expected_sha256=sha(child.read_bytes()))
    assert child_result["recoveryScope"] == "ANDROID_V1_ONLY_NO_NUMBERING_CATEGORIES_HISTORY"
    confirm(legacy_db, child_draft, review(legacy_db, child_draft))
    with Catalog(legacy_db) as c:
        assert not c.rows("local_numbers") and not c.rows("local_categories") and not c.rows("local_number_history")
    summary = {"passed": True, "cliInvocations": len(transcript), "syntheticOnly": True,
               "draftLeavesLiveUnchanged": True, "explicitConfirmationRequired": True,
               "repeatConfirmationIdempotent": True, "cancelLeavesLiveUnchanged": True,
               "completeAssetDigestEqual": True, "stablePieceId": piece, "stableVersionId": pdf["versionId"],
               "sourcePages": [1, 2], "numbers": [998, 999], "tableCounts": counts,
               "memberPreview": reviewed["memberPreview"], "legacyChildRecoveryScope": child_result["recoveryScope"],
               "limitations": "Metadata simulation only; no login, PDF rendering/splitting/delivery, native Windows or Android execution"}
    (root / "demo-result.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    return summary


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    print(json.dumps(demonstrate(parser.parse_args().output)))
