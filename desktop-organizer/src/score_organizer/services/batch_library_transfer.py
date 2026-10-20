"""The desktop UI defaults to collecting bad songs and continuing the batch."""
from score_organizer.services.library_transfer import inspect_package, restore_library
from score_organizer.services.library_transfer import export_library as _export


def export_library(db, destination, *, progress=lambda _message: None):
    return _export(db, destination, progress=progress, skip_errors=True)
