"""Canonical numbering and truthful rollout gates; expanded writes remain closed."""
from dataclasses import dataclass
import re

from service import Rejected, require

MAX_SEQUENCE = 9_223_372_036_854_775_807


@dataclass(frozen=True)
class ScoreNumber:
    instrument: str
    category: str
    sequence: str
    version: int


def parse_number(value):
    require(type(value) is str and len(value) <= 25, 'invalid_score_number')
    legacy = re.fullmatch(r'([0-9]{2})([0-9]{2})([0-9]{3})', value)
    if legacy:
        require(legacy[3] != '000', 'invalid_score_number')
        return ScoreNumber(legacy[1], legacy[2], legacy[3], 1)
    expanded = re.fullmatch(r'([0-9]{2})-([0-9]{2})-([1-9][0-9]{3,18})', value)
    require(expanded is not None and int(expanded[3]) <= MAX_SEQUENCE, 'invalid_score_number')
    return ScoreNumber(expanded[1], expanded[2], expanded[3], 2)


def require_current_writer(value):
    number = parse_number(value)
    require(number.version == 1, 'numbering_upgrade_required', 426)
    return number


def current_reader(value):
    # Operator-staged older PDFs may legitimately have no numbered metadata.
    if value is None or value == '':
        return None
    try:
        return require_current_writer(value)
    except Rejected:
        raise Rejected(426, 'reader_numbering_upgrade_required') from None


def capabilities(service, token, device):
    with service.connection() as db:
        actor = service.authenticate(db, token, device)
        require(actor['role'] == 'admin', 'forbidden', 403)
        team = db.execute("SELECT value FROM metadata WHERE key='teamId'").fetchone()
        require(team is not None, 'not_initialized', 503)
        # Parsing v2 syntax alone is not permission to publish or distribute it.
        return dict(protocolVersion=1, teamId=team[0], expandedPublicationReady=False,
                    numbering=dict(namingVersions=[1], maximumSequence='999',
                                   sequenceEncoding='decimal-string', expandedSeparator='-'))
