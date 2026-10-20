"""v2 import component; no product startup or GUI enables it yet."""
from score_organizer.domain.numbering import EXPANDED_NAMING_VERSION
from score_organizer.services.expanded_runtime_guard import require_expanded_runtime
from score_organizer.services.import_service import ImportService


class ExpandedImportService(ImportService):
    _numbering_version = EXPANDED_NAMING_VERSION

    def _require_runtime(self) -> None:
        require_expanded_runtime(self.db)
