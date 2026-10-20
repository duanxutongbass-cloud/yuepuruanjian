# Phase 2 Review

Historical evidence only. Current decisions and implementation status are in PROJECT_STATE.md and the docs/ specification files. Later-phase limitations below refer to the time of Phase 2.

Status: passed for the Repository and Domain resolution scope.

## Implemented

- Hilt-bound ScoreRepository backed by a Room transaction snapshot.
- Global definition permissions and piece-specific part permissions are combined only for the requested member and piece. Archived members, pieces, parts, definitions and permission rows cannot grant access. Admin role does not implicitly bypass permissions.
- PiecePartAssignment overrides the default definition. Neither assignment nor default grants access. Ambiguous or dangling assignments fail explicitly.
- VisiblePart returns only authorized part metadata. ResolvedConcert, ResolvedConcertItem and ResolvedScoreSegment expose only successfully authorized scores.
- Active versions resolve exclusively through ScorePdf.activeVersionId. Cross-PDF version references fail; version numbers never provide a fallback.
- Bindings validate movement ownership and completeness. Whole-piece bindings and movement bindings cannot be mixed. Duplicate active bindings fail, including whole-piece bindings that SQLite's nullable unique index can otherwise allow.
- Page ranges are inclusive, one-based and bounded by the active version page count.
- Program items sort by sortOrder, then ID. Disabled and archived items are excluded. Any enabled item's business failure aborts the result rather than returning a partial program.
- Cache status resolves for the exact active version and member, with shared cache as fallback. Missing cache is NOT_CACHED; duplicate selected cache records fail.
- DomainResult and DomainError provide explicit business failures. Database infrastructure exceptions remain exceptions, and coroutine cancellation is not swallowed.
- Database schema 2 introduces isEnabled independently of archival. Migration 1 to 2 enables existing program items without deleting data.

## Validation

Executed testDebugUnitTest and assembleDebug using local Gradle 8.14.3 and Android Studio JBR.

Final result: BUILD SUCCESSFUL. 17 tests passed: 5 existing Room constraints tests, 10 Domain tests containing multiple boundary cases, and 2 Room repository/migration integration tests.

Migration test reconstructs schema 1 from its exported JSON, creates existing rows, opens schema 2 through the actual migration, and checks preserved data and enabled defaults. Room validates the resulting full schema.

The test schema reader initially assumed all tables had an indices array. Fixed it to accept omitted empty arrays, then reran the complete suite successfully.

Reviewed UI: current placeholder screens have no DAO or database reads and do not expose score data. Future score screens must consume ScoreRepository's authorized results; authenticated member identity must come from the application's session.

## Non-blocking Follow-up

- The initial repository reads all library tables in one transaction. Profile and introduce scoped queries before large production libraries; keep transaction consistency and the same authorization rules.
- Assignment ambiguity is rejected at read time. A future administrative editing workflow should enforce one active assignment per member and piece at write time.
- The UI remains the Phase 1 skeleton. This phase implements the business boundary and tests, not member login, editing screens, PDF rendering or file download.
- Cached status is metadata only; on-disk existence and hash verification belong to the later storage/offline implementation.
- Validation is JVM/Robolectric and APK compilation. No physical-device runtime or large-tablet visual verification was performed in this phase.

Synced files under sources/ were not modified.
