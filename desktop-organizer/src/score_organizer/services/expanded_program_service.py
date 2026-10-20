"""Expanded program export from a guarded candidate; no production activation."""
from score_organizer.domain.numbering import EXPANDED_NAMING_VERSION
from score_organizer.services.expanded_archive_service import ExpandedArchiveService
from score_organizer.services.expanded_runtime_guard import require_expanded_runtime
from score_organizer.services.program_service import ProgramService


class ExpandedProgramService(ProgramService):
    _numbering_version = EXPANDED_NAMING_VERSION
    _manifest_format = 'score-program-v2'

    def _require_runtime(self):
        require_expanded_runtime(self.db, self.root)

    def _archive(self):
        return ExpandedArchiveService(self.db, self.root)

    def _require_export_destination(self, parent):
        if parent.resolve().is_relative_to(self.db.path.parent.resolve()):
            raise ValueError('请在独立运行库及源文件目录之外选择打包位置')
