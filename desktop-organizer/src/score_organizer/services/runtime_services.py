"""GUI service selection follows the opened database's validated numbering contract."""
from importlib import import_module


def _create(db, module, name, *args):
    expanded=db._numbering_version == 2
    source=import_module('score_organizer.services.'+('expanded_' if expanded else '')+module)
    return getattr(source,('Expanded' if expanded else '')+name)(db,*args)


def archive(db,*args):return _create(db,'archive_service','ArchiveService',*args)
def catalog(db,*args):return _create(db,'catalog_service','CatalogService',*args)
def merge(db,*args):return _create(db,'catalog_merge_service','CatalogMergeService',*args)
def importer(db,*args):return _create(db,'import_service','ImportService',*args)
def reset(db,*args):return _create(db,'reset_service','ResetService',*args)
def program(db,*args):return _create(db,'program_service','ProgramService',*args)
def publish(db,*args):return _create(db,'team_publish','PublishService',*args)


def report(db):
    from score_organizer.services.report_service import ReportService
    return ReportService(db,naming_version=db._numbering_version)
