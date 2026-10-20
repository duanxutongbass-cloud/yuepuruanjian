"""Candidate protocol contract; deployed v1 servers do not advertise v2 readiness."""
from score_organizer.domain.numbering import EXPANDED_NAMING_VERSION, normalize_seq
from score_organizer.domain.score_naming import parse_score_number


def require_expanded_publication(reply, team_id, numbers):
    error = '服务尚未明确支持扩位发布及兼容读取，请先完成三端升级；未继续上传'
    if (type(team_id) is not str or not team_id
            or not isinstance(reply, dict) or type(reply.get('protocolVersion')) is not int
            or reply['protocolVersion'] != 2 or reply.get('teamId') != team_id
            or reply.get('candidateOnly') is not False
            or reply.get('expandedPublicationReady') is not True):
        raise ValueError(error)
    numbering = reply.get('numbering')
    if not isinstance(numbering, dict):
        raise ValueError(error)
    versions = numbering.get('namingVersions')
    if (not isinstance(versions, list) or len(versions) != 2
            or any(type(version) is not int for version in versions) or set(versions) != {1, 2}
            or numbering.get('sequenceEncoding') != 'decimal-string'
            or numbering.get('expandedSeparator') != '-'):
        raise ValueError(error)
    maximum = numbering.get('maximumSequence')
    if type(maximum) is not str:
        raise ValueError(error)
    try:
        canonical = normalize_seq(maximum, naming_version=EXPANDED_NAMING_VERSION)
    except ValueError:
        raise ValueError(error) from None
    if canonical != maximum or int(maximum) < 1000:
        raise ValueError(error)
    for number in numbers:
        parsed = parse_score_number(number, naming_version=EXPANDED_NAMING_VERSION)
        if int(parsed.seq) > int(maximum):
            raise ValueError('所选编号超过服务明确支持的范围，未继续上传')

