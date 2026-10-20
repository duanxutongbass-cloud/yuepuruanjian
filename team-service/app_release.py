"""Public, bounded Android update channel. No account data or PDFs are exposed here."""
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import zipfile
from catalog import FileReply
from service import require

PACKAGE = "com.ensemble.scorelibrary"
MAX_APK = 150 * 1024 * 1024


class AppReleases:
    def __init__(self, directory):
        self.root = Path(directory) / "app-releases"

    @staticmethod
    def validate(metadata):
        require(metadata.get("packageName") == PACKAGE, "invalid_release")
        require(type(metadata.get("versionCode")) is int and 0 < metadata["versionCode"] < 2_100_000_000, "invalid_release")
        require(isinstance(metadata.get("versionName"), str) and 0 < len(metadata["versionName"]) <= 40, "invalid_release")
        require(type(metadata.get("size")) is int and 0 < metadata["size"] <= MAX_APK, "invalid_release")
        require(isinstance(metadata.get("sha256"), str) and re.fullmatch("[a-f0-9]{64}", metadata["sha256"]), "invalid_release")
        require(isinstance(metadata.get("notes"), str) and len(metadata["notes"]) <= 2000, "invalid_release")
        return metadata

    def current(self):
        manifest = self.root / "current.json"
        if not manifest.exists():
            return None
        require(not manifest.is_symlink() and manifest.stat().st_size <= 8192, "release_unavailable", 503)
        return self.validate(json.loads(manifest.read_text(encoding="utf-8")))

    def reply(self, path, if_match=None):
        metadata = self.current()
        if path == "/v1/app-release":
            return dict(available=False) if metadata is None else dict(metadata, available=True)
        require(path == "/v1/app-release/apk" and metadata is not None, "release_unavailable", 404)
        require(if_match == '"' + metadata["sha256"] + '"', "version_changed", 412)
        target = self.root / (metadata["sha256"] + ".apk")
        require(target.is_file() and not target.is_symlink() and target.stat().st_size == metadata["size"], "release_unavailable", 503)
        return FileReply(target.open("rb"), metadata["size"], metadata["sha256"], metadata["size"], 0, False,
                         "application/vnd.android.package-archive")

    def publish(self, source, version_code, version_name, notes=""):
        source = Path(source)
        require(source.is_file() and not source.is_symlink() and 0 < source.stat().st_size <= MAX_APK, "invalid_release")
        with zipfile.ZipFile(source) as apk:
            require("AndroidManifest.xml" in apk.namelist() and "classes.dex" in apk.namelist(), "invalid_release")
        self.root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=".release-", dir=self.root) as stage:
            copied = Path(stage) / "update.apk"
            digest = hashlib.sha256()
            with source.open("rb") as input_file, copied.open("xb") as output:
                size = 0
                while block := input_file.read(65536):
                    size += len(block)
                    require(size <= MAX_APK, "invalid_release")
                    digest.update(block)
                    output.write(block)
                output.flush()
                os.fsync(output.fileno())
            metadata = self.validate(dict(packageName=PACKAGE, versionCode=version_code, versionName=version_name,
                sha256=digest.hexdigest(), size=size, notes=notes))
            previous = self.current()
            require(previous is None or version_code > previous["versionCode"] or metadata == previous, "release_version_conflict", 409)
            target = self.root / (metadata["sha256"] + ".apk")
            if target.exists():
                require(not target.is_symlink() and hashlib.sha256(target.read_bytes()).hexdigest() == metadata["sha256"], "release_unavailable", 503)
            else:
                os.replace(copied, target)
            pending = Path(stage) / "current.json"
            with pending.open("x", encoding="utf-8") as output:
                json.dump(metadata, output, ensure_ascii=False)
                output.flush()
                os.fsync(output.fileno())
            os.replace(pending, self.root / "current.json")
            if os.name != "nt":
                descriptor = os.open(self.root, os.O_RDONLY)
                try:
                    os.fsync(descriptor)
                finally:
                    os.close(descriptor)
            return metadata
