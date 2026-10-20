"""Explicit naming-v2 on v4 deletion storage. No in-place downgrade or activation."""
from contextlib import closing
import json
from pathlib import Path
import sqlite3
import tempfile

from backup_archive import plain_path, fingerprint
from expanded_candidate import ROOT, NAMING, _inventory, _publish_candidate
from expanded_protocol import ExpandedApplication, ExpandedCatalog, expanded_reader
from permanent_storage import PermanentStorage
from permanent_backup import restore_permanent_archive
from score_numbers import parse_number
from service import require

FORMAT = 'score-team-expanded-permanent-storage-v1'
MARKER = 'expanded-permanent-storage.json'
ARCHIVE_FORMAT = 'score-team-backup-v4'


class ExpandedPermanentStorage(PermanentStorage):
    naming_version = 2
    marker_format = FORMAT
    marker_file = MARKER

    def principal(self, db, account):
        return dict(super().principal(db, account), numberingProtocol=2)


class ExpandedPermanentCatalog(ExpandedCatalog):
    def validate_service(self, service):
        require(type(service) is ExpandedPermanentStorage, 'expanded_permanent_service_required', 503)


class ExpandedPermanentApplication(ExpandedApplication):
    catalog_type = ExpandedPermanentCatalog


class ExpandedReadyApplication(ExpandedPermanentApplication):
    """Serving entry requires explicit activated storage, including retirement merge."""
    def __init__(self, service, allow_loopback_http=False):
        checked=open_active(service.directory)
        require(checked._identity == service._identity, 'expanded_activation_required',503)
        super().__init__(service,allow_loopback_http)

    def capability_reply(self, token, device, env):
        reply=super().capability_reply(token,device,env)
        if env['score.numbering.v2']:
            reply.update(candidateOnly=False,expandedPublicationReady=True)
        return reply


def prepare(archive, destination):
    archive, destination = plain_path(archive), plain_path(destination)
    require(destination.parent.is_dir() and not destination.exists(), 'candidate_requires_new_directory', 409)
    original = fingerprint(archive)
    with tempfile.TemporaryDirectory(prefix='.expanded-permanent-', dir=destination.parent) as tmp:
        restored = Path(tmp)/'restored'
        proof = restore_permanent_archive(archive, restored)
        storage = PermanentStorage(restored)
        marker, _ = storage._marker()
        with storage.connection() as db:
            _inventory(db)
        # All business rows, retirement journals and IDs survive unchanged. Only
        # the isolated storage path and naming identity change, never user data.
        with closing(sqlite3.connect(storage.path)) as db:
            db.execute('UPDATE metadata SET value=? WHERE key=?', ('2', NAMING))
            db.execute('UPDATE metadata SET value=? WHERE key=?', (str(destination.resolve()), ROOT))
            db.commit()
        (restored/PermanentStorage.marker_file).unlink()
        marker.update(format=FORMAT, namingVersion=2, directory=str(destination.resolve()))
        check = lambda: require(fingerprint(archive) == original, 'candidate_source_changed', 409)
        check()
        copied = _publish_candidate(restored, destination, marker, check, marker_name=MARKER)
    with ExpandedPermanentStorage(destination).connection() as db:
        _inventory(db, expanded_reader, parse_number)
    return dict(databaseVersion=4, namingVersion=2, expandedPublicationReady=False,
                sourceSha256=original['sha256'], copiedFiles=copied, storageId=marker['storageId'])


def create_archive(service, destination):
    from permanent_backup import create_permanent_archive
    return create_permanent_archive(service, destination, _format=ARCHIVE_FORMAT, _storage_type=ExpandedPermanentStorage)


def validate_archive(archive, stage):
    from permanent_backup import validate_permanent_archive
    return validate_permanent_archive(archive, stage, _format=ARCHIVE_FORMAT, _storage_type=ExpandedPermanentStorage)


def restore_archive(archive, destination):
    return restore_permanent_archive(archive, destination, _format=ARCHIVE_FORMAT, _storage_type=ExpandedPermanentStorage)


def activate(directory, authority, confirmed=False):
    from permanent_runtime import activate as activate_permanent
    return activate_permanent(directory, authority, confirmed,
        _storage_type=ExpandedPermanentStorage, _application_type=ExpandedPermanentApplication)


def open_active(directory):
    from permanent_runtime import open_active as open_permanent
    return open_permanent(directory, _storage_type=ExpandedPermanentStorage)
