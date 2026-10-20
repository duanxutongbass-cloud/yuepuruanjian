from __future__ import annotations

import hashlib
from dataclasses import asdict
from pathlib import Path

from score_organizer.domain.filename_parser import FilenameFormat, parse_score_filename
from score_organizer.storage.database import Database


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


class ImportService:
    _numbering_version = 1

    def __init__(self, db: Database) -> None:
        self.db = db

    def import_paths(self, paths: list[str | Path], batch_id: int | None = None) -> int:
        self._require_runtime()
        instruments = self.db.instrument_codes()
        categories = self.db.category_codes()
        prepared = []
        for path in self._expand_paths(paths):
            parsed = parse_score_filename(path.name, instruments, categories, naming_version=self._numbering_version)
            status = self._status_for(path, parsed.format)
            file_hash = sha256_file(path) if path.is_file() and path.suffix.casefold() == ".pdf" else None
            prepared.append((path, file_hash, status, asdict(parsed)))
        # Hash files before acquiring the write lock; commit the batch atomically.
        with self.db.transaction(reuse=True):
            self._require_runtime()
            if batch_id is None:
                batch_id = self.db.create_import_batch()
            for path, file_hash, status, fields in prepared:
                self.db.add_import_item(batch_id=batch_id, source_path=str(path), source_sha256=file_hash,
                                        status=status, parsed_fields=fields)
        return batch_id

    def _require_runtime(self) -> None:
        """Normal v1 imports use the existing Database compatibility checks."""

    def _expand_paths(self, paths: list[str | Path]) -> list[Path]:
        expanded: list[Path] = []
        for raw_path in paths:
            path = Path(raw_path)
            if path.is_dir():
                expanded.extend(
                    sorted(
                        (candidate for candidate in path.rglob("*.pdf") if candidate.is_file()),
                        key=lambda item: str(item).casefold(),
                    )
                )
            else:
                expanded.append(path)
        return expanded

    def _status_for(self, path: Path, filename_format: FilenameFormat) -> str:
        if path.suffix.casefold() != ".pdf":
            return "skipped"
        if filename_format == FilenameFormat.INVALID:
            return "needs_manual_entry"
        return "recognized"
