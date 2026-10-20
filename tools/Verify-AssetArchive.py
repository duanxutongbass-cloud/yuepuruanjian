"""Read-only, standard-library inspection of an Android asset archive; extracts nothing."""
import argparse
import hashlib
import json
import zipfile


def verify(path):
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        assert len(entries) <= 5002, "Too many entries"
        names = {entry.filename for entry in entries}
        assert len(names) == len(entries), "Duplicate ZIP entries"
        for entry in entries:
            assert not entry.is_dir() and not (entry.flag_bits & 1), "Directory/encrypted entry unsupported"
            limit = 16 * 1024 * 1024 if entry.filename == "manifest.json" else (
                64 * 1024 if entry.filename == "README.txt" else 256 * 1024 * 1024)
            assert 0 <= entry.file_size <= limit, "Entry too large"
        assert sum(entry.file_size for entry in entries) <= 2 * 1024**3 + 16 * 1024**2 + 65536
        root = json.loads(archive.read("manifest.json"))
        assert root["format"] == "ensemble-score-library"
        assert type(root["formatVersion"]) is int
        assert (root["formatVersion"], root["databaseVersion"]) in {(1, 3), (2, 4), (3, 5)}
        files = root["files"]
        assert names == {"manifest.json", "README.txt"} | {item["entry"] for item in files}
        assert len({item["versionId"] for item in files}) == len(files)
        pages = root["tables"]["annotation_pages"]
        if root["kind"] == "PERSONAL":
            assert not files and set(root["tables"]) == {"annotation_pages"}
            assert root["owner"] != "shared" and all(page["ownerKey"] == root["owner"] for page in pages)
        else:
            assert root["kind"] == "LIBRARY"
            assert all(page["ownerKey"] == "shared" for page in pages), "Mother archive includes private annotations"
            versions = {row["id"]: row for row in root["tables"]["score_pdf_versions"]}
            assert set(versions) == {item["versionId"] for item in files}
        for item in files:
            expected_name = "files/" + hashlib.sha256(item["versionId"].encode()).hexdigest() + ".pdf"
            assert item["entry"] == expected_name, "Unsafe entry name"
            digest = hashlib.sha256()
            size = 0
            with archive.open(item["entry"]) as stream:
                for block in iter(lambda: stream.read(65536), b""):
                    digest.update(block)
                    size += len(block)
            row = versions[item["versionId"]]
            assert size == item["bytes"] == row["fileSizeBytes"]
            assert digest.hexdigest() == item["sha256"].lower() == row["fileHash"].lower()
            assert row["managedFilePath"] == expected_name
        archive.read("README.txt")
        return {"valid": True, "kind": root["kind"], "pdfVersions": len(files),
                "annotationPages": len(pages), "scope": "ZIP/JSON integrity and owner filtering; not all database/domain invariants"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive")
    args = parser.parse_args()
    print(json.dumps(verify(args.archive), ensure_ascii=True))
