"""Only synthetic geometry and labels; no user score is opened by these tests."""
import io
import json
from pathlib import Path
import zipfile

from pypdf import PdfWriter
from pypdf.generic import DecodedStreamObject, NameObject, NumberObject, RectangleObject

from score_manager.validation import TABLES, entry, sha


def pdf_bytes(pages=3, label="synthetic", *, encrypted=False, geometry=False):
    writer = PdfWriter()
    writer.add_metadata({"/Title": "SYNTHETIC TEST ONLY: " + label})
    for n in range(pages):
        page = writer.add_blank_page(width=400 + n, height=600 + n)
        stream = DecodedStreamObject()
        stream.set_data(f"q 0 0 0 RG 1 w 20 {30+n} 120 80 re S Q\n".encode())
        page[NameObject("/Contents")] = writer._add_object(stream)
        if geometry and n == 1:
            page[NameObject("/Rotate")] = NumberObject(90)
            page[NameObject("/CropBox")] = RectangleObject([10, 20, 380, 580])
    if encrypted:
        writer.encrypt("synthetic-password")
    output = io.BytesIO()
    writer.write(output)
    return output.getvalue()


def write_pdf(directory, name="synthetic.pdf", **kwargs):
    path = Path(directory) / name
    path.write_bytes(pdf_bytes(label=name, **kwargs))
    return path


def write_zip(path, entries):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as z:
        for name, data in entries:
            z.writestr(name, data)


def ink():
    return json.dumps([{"id": "ink-original", "kind": "SYMBOL", "color": 4294901760,
                        "width": .003, "symbol": "♯", "points": [[.25, .75]]}], ensure_ascii=False)


def legacy_archive(path):
    """Independent schema-3/v1 golden-style fixture, not made by our exporter.

    Retains non-UUID legacy String IDs accepted by Android; new workbench IDs
    remain UUIDs. Active version is deliberately NOT the largest version number.
    """
    first, second = pdf_bytes(3, "old-v1", geometry=True), pdf_bytes(4, "old-v2")
    tables = {x: [] for x in TABLES}
    tables["members"] = [{"id": "legacy-admin", "displayName": "Synthetic administrator", "role": "ADMIN", "defaultPartDefinitionId": None, "isArchived": 0},
                         {"id": "legacy-player", "displayName": "Synthetic player", "role": "MEMBER", "defaultPartDefinitionId": "legacy-instrument", "isArchived": 0}]
    tables["pieces"] = [{"id": "legacy-piece", "title": "Synthetic piece", "composer": None, "arranger": None, "notes": "synthetic only", "isArchived": 0, "createdAtEpochMillis": 1}]
    tables["part_definitions"] = [{"id": "legacy-instrument", "name": "User-defined sound", "family": "Synthetic", "sortOrder": 3, "isArchived": 0}]
    tables["piece_parts"] = [{"id": "legacy-part", "pieceId": "legacy-piece", "partDefinitionId": "legacy-instrument", "displayName": None, "isArchived": 0}]
    tables["movements"] = [{"id": "legacy-movement", "pieceId": "legacy-piece", "title": "Synthetic section", "sortOrder": 1, "isArchived": 0}]
    tables["score_pdfs"] = [{"id": "legacy-pdf", "title": "Synthetic source", "activeVersionId": "legacy-v1", "isArchived": 0, "createdAtEpochMillis": 1}]
    files = []
    for index, data in enumerate((first, second), 1):
        vid = f"legacy-v{index}"
        tables["score_pdf_versions"].append({"id": vid, "scorePdfId": "legacy-pdf", "versionNumber": index, "managedFilePath": entry(vid), "originalUri": "content://synthetic/source", "fileHash": sha(data), "fileSizeBytes": len(data), "pageCount": index + 2, "createdAtEpochMillis": index, "createdByMemberId": "legacy-admin"})
        files.append({"versionId": vid, "entry": entry(vid), "bytes": len(data), "sha256": sha(data)})
    tables["part_score_bindings"] = [{"id": "legacy-binding", "piecePartId": "legacy-part", "movementId": "legacy-movement", "scorePdfId": "legacy-pdf", "startPage": 2, "endPage": 3, "isArchived": 0}]
    tables["member_part_permissions"] = [{"id": "legacy-global-grant", "memberId": "legacy-player", "partDefinitionId": "legacy-instrument", "isArchived": 0}]
    tables["member_piece_part_permissions"] = [{"id": "legacy-exception-grant", "memberId": "legacy-player", "piecePartId": "legacy-part", "isArchived": 0}]
    tables["piece_part_assignments"] = [{"id": "legacy-assignment", "memberId": "legacy-player", "piecePartId": "legacy-part", "isArchived": 0}]
    tables["concerts"] = [{"id": "legacy-concert", "title": "Synthetic concert", "performanceAtEpochMillis": None, "status": "READY", "isArchived": 0}]
    tables["concert_items"] = [{"id": "legacy-item", "concertId": "legacy-concert", "pieceId": "legacy-piece", "sortOrder": 2, "isEnabled": 1, "isArchived": 0}]
    tables["annotation_pages"] = [{"id": "legacy-document", "versionId": "legacy-v1", "pageIndex": 1, "ownerKey": "shared", "payload": ink(), "generation": 3, "publishedRevisionId": "legacy-revision"}]
    tables["annotation_revisions"] = [{"id": "legacy-revision", "documentId": "legacy-document", "payload": ink(), "createdBy": "legacy-admin", "createdAt": 123}]
    # Independently assembled using AnnotationRepository.manifest's exact order.
    manifest = f"legacy-binding:legacy-part:legacy-movement:legacy-v1:{sha(first)}:3:2:3#legacy-item:legacy-piece:2"
    tables["annotation_concert_snapshots"] = [{"id": "legacy-snapshot", "concertId": "legacy-concert", "payload": json.dumps({"legacy-v1:1": ink()}), "manifest": manifest, "createdBy": "legacy-admin", "createdAt": 456}]
    root = {"format": "ensemble-score-library", "formatVersion": 1, "databaseVersion": 3, "kind": "LIBRARY", "owner": "legacy-admin", "createdAtEpochMillis": 789, "tables": tables, "files": files, "versionReferences": []}
    write_zip(path, [("manifest.json", json.dumps(root).encode()), ("README.txt", b"Synthetic legacy v1 fixture"), (entry("legacy-v1"), first), (entry("legacy-v2"), second)])
    return root


def mutate_zip(source, target, mutate):
    with zipfile.ZipFile(source) as z:
        entries = {n: z.read(n) for n in z.namelist()}
    mutate(entries)
    write_zip(target, entries.items())


def change_manifest(source, target, mutate):
    def change(entries):
        root = json.loads(entries["manifest.json"])
        mutate(root)
        entries["manifest.json"] = json.dumps(root).encode()
    mutate_zip(source, target, change)
