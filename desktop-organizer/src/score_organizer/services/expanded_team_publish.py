"""Read-only candidate publishing preparation with fail-closed remote negotiation.

No GUI/startup activation. NAS/Android v2 support must still be implemented before
any production server can truthfully advertise expandedPublicationReady.
"""
import json

from score_organizer.domain.numbering import EXPANDED_NAMING_VERSION
from score_organizer.domain.publication_capabilities import require_expanded_publication
from score_organizer.domain.score_naming import parse_score_number
from score_organizer.services.expanded_archive_service import ExpandedArchiveService
from score_organizer.services.expanded_runtime_guard import require_expanded_runtime
from score_organizer.services.team_publish import PublishService


class ExpandedPublishService(PublishService):
    _numbering_version = EXPANDED_NAMING_VERSION
    _api_prefix = '/v2'

    def _require_runtime(self):
        require_expanded_runtime(self.db, self.root)

    def _archive(self):
        return ExpandedArchiveService(self.db, self.root)

    def _remote_requirement(self, plan):
        # Immutable requirement for this invocation, never cached on the service.
        return max((part.number for part in plan.parts), key=lambda number:
                   int(parse_score_number(number, naming_version=self._numbering_version).seq))

    def _before_remote_write(self, plan, client, scope, requirement):
        def check_identity():
            principal = client.principal
            if (not principal or principal.get('role') != 'admin'
                    or json.dumps([client.address, principal.get('teamId'), principal.get('accountId')]) != scope):
                raise ValueError('发布期间账号、乐团或服务地址发生变化，请重新登录预览')

        self._require_runtime()
        check_identity()
        # No cached capability, write probe, 404 fallback, or format truncation.
        reply = client.request('GET', '/v2/admin/capabilities')
        check_identity()
        require_expanded_publication(reply, client.principal['teamId'], (requirement,))
        # Recheck the affected PDF(s) after negotiation. The full batch is checked
        # at publication, without rereading every other PDF on each upload.
        self.verify(plan)
