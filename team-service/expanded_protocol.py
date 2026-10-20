"""Explicit, isolated /v2 numbering API candidate; no deployment/startup switch.

Existing /v1 number-bearing routes fail closed on this application. Account and
other UUID-only protocols keep their existing authentication/authorization. The
normal Application, CLI, image and v1 capabilities remain unchanged defaults.
"""
from catalog import Catalog
from expanded_candidate import ExpandedCandidateService
from score_numbers import MAX_SEQUENCE, parse_number
from server import Application
from service import Rejected, require


def expanded_reader(value):
    if value is None or value == '':
        return None
    try:
        return parse_number(value)
    except Rejected:
        raise Rejected(426, 'reader_numbering_upgrade_required') from None


class ExpandedCatalog(Catalog):
    def validate_service(self, service):
        require(isinstance(service, ExpandedCandidateService) and service.database_version == 3 and
                service.naming_version == 2 and service.marker_format == ExpandedCandidateService.marker_format,
                'candidate_service_required', 503)

    @staticmethod
    def number_inventory(db, now):
        from number_inventory import inventory
        return inventory(db, now, parse=parse_number, maximum=MAX_SEQUENCE, protocol=2)

    read_number = staticmethod(expanded_reader)
    write_number = staticmethod(parse_number)
    protocol_version = 2

    def __init__(self, service):
        self.validate_service(service)
        super().__init__(service)

    @classmethod
    def metadata(cls, row):
        result = super().metadata(row)
        number = cls.read_number(result['scoreNumber'])
        return dict(result, namingVersion=number.version if number else None,
                    sequence=number.sequence if number else None)

    def dispatch(self, *args, **kwargs):
        result = super().dispatch(*args, **kwargs)
        return dict(result, protocolVersion=2) if isinstance(result, dict) else result


def numbering_route(path):
    return path in {
        '/v1/catalog', '/v1/admin/ensemble', '/v1/admin/programs', '/v1/admin/catalog-actions',
        '/v1/admin/artifacts', '/v1/admin/artifacts/publish', '/v1/admin/grants', '/v1/admin/number-inventory',
        '/v1/admin/uploads', '/v1/admin/publications', '/v1/admin/publication-cancellations',
        '/v1/admin/program-freezes',
        '/v1/admin/permanent-deletions/preview', '/v1/admin/permanent-deletions',
        '/v1/admin/number-releases','/v1/admin/number-releases/preview','/v1/admin/number-releases/commit',
    } or path.startswith(('/v1/artifacts/', '/v1/admin/uploads/', '/v1/program-freezes/'))


class ExpandedApplication(Application):
    catalog_type = ExpandedCatalog

    def __call__(self, env, start_response):
        # Request-local copy; a legacy request cannot inherit a preceding v2
        # request's mode in the same worker, concurrent thread or nested call.
        request = dict(env)
        path = env.get('PATH_INFO', '')
        mapped = '/v1/' + path[len('/v2/'):] if path.startswith('/v2/') else ''
        supported = numbering_route(mapped) or mapped == '/v1/admin/capabilities'
        request['score.numbering.v2'] = supported
        if supported:
            request['PATH_INFO'] = mapped
        return super().__call__(request, start_response)

    def check_protocol(self, env, method, path):
        # Runs after current account/device/membership authentication and before
        # reading JSON or PDF bytes. Old readers receive no expanded payload.
        if numbering_route(path):
            require(env['score.numbering.v2'], 'reader_numbering_upgrade_required', 426)

    def capability_reply(self, token, device, env):
        if not env['score.numbering.v2']:
            return super().capability_reply(token, device, env)
        with self.service.connection() as db:
            actor = self.service.authenticate(db, token, device)
            require(actor['role'] == 'admin', 'forbidden', 403)
            team = db.execute("SELECT value FROM metadata WHERE key='teamId'").fetchone()
            require(team is not None, 'not_initialized', 503)
            # Isolated server support is not full desktop/Android readiness.
            return dict(protocolVersion=2, teamId=team[0], candidateOnly=True,
                        expandedPublicationReady=False,
                        numbering=dict(namingVersions=[1, 2], maximumSequence=str(MAX_SEQUENCE),
                                       sequenceEncoding='decimal-string', expandedSeparator='-'))
