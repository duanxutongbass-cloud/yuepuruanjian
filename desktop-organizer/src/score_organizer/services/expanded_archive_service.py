"""Archive component for an isolated format-1 runtime, not a startup entry.

Normal Database connections still reject format 1. This component does not
activate a candidate or establish process/filesystem isolation from old EXEs.
"""
from score_organizer.domain.numbering import EXPANDED_NAMING_VERSION
from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.expanded_runtime_guard import require_expanded_runtime


class ExpandedArchiveService(ArchiveService):
    _numbering_version = EXPANDED_NAMING_VERSION

    def _require_candidate(self):
        require_expanded_runtime(self.db, self.output_root)

    def build_plan(self, batch_id, item_ids=None, force=False):
        self._require_candidate()
        return super().build_plan(batch_id, item_ids, force)

    def commit_plan(self, batch_id, plans):
        self._require_candidate()
        return super().commit_plan(batch_id, plans)

    def score_paths(self, score):
        self._require_candidate()
        return super().score_paths(score)

    def score_paths_for_rows(self, scores, selected):
        self._require_candidate()
        return super().score_paths_for_rows(scores, selected)
