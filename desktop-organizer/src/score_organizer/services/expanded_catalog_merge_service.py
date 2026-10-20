"""Candidate v2 merge workflow with the guarded catalog editing policy.

No GUI or production startup enables this class. Merge audit portability still
needs its own compatibility check before format-1 activation can be allowed.
"""
from score_organizer.domain.numbering import EXPANDED_NAMING_VERSION
from score_organizer.services.catalog_merge_service import CatalogMergeService
from score_organizer.services.expanded_catalog_service import ExpandedCatalogService


class ExpandedCatalogMergeService(CatalogMergeService, ExpandedCatalogService):
    # Workflow methods come from CatalogMergeService; runtime and snapshot
    # checks come from ExpandedCatalogService before the shared CatalogService.
    _numbering_version = EXPANDED_NAMING_VERSION
