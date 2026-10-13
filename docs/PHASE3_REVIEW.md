# Phase 3: Compose Navigation and Pad UI

Historical evidence only. Current decisions and status are in PROJECT_STATE.md and PRODUCT_DESIGN.md. Placeholder/import limitations below refer to the time of Phase 3, not the current build.

Status: passed for the Phase 3 navigation, responsive Pad UI and Domain integration scope.
Final verification: testDebugUnitTest, assembleDebug and lintDebug completed with BUILD SUCCESSFUL.
After the final icon-resource relocation, assembleDebug and lintDebug were rerun successfully. Lint reports zero errors; only pinned-dependency update notices remain.

## Delivered

- Material 3 application frame: top app bar, four root destinations (library, concerts, profile, administration), back and refresh actions, and a bottom navigation bar.
- Room-driven library and concert lists, authorized piece details, resolved personal concert scores, current-member profile, administrator summary, and a score-reader placeholder.
- Reader destinations hide ordinary bottom navigation. Their arguments contain identifiers only; no file paths or serialized score objects are accepted from navigation.
- LibraryViewModel and explicit loading/error/ready state. Empty states derive from authorized lists; offline and file-missing flags coexist with ready data rather than discarding it.
- Hilt injection and lifecycle-aware Compose state collection. Selected member identity uses SavedStateHandle. Navigation uses the saved NavController back stack, single-top root switching and saved/restored tab state.

## Domain Boundary

UI imports no Room entities or DAOs and implements no permission, default-part, assignment or concert-order rules.

PresentationRepository observes Room invalidations, reads one transaction snapshot, and runs Domain projection on Dispatchers.IO. Every permission, member, version, binding, assignment, concert or cache change triggers a fresh projection.

The Domain extension provides catalog/profile projections and explicitly authorized part browsing. Clicking a library part calls resolvePart; it does not change the member's concert assignment. Concert-reader navigation reruns resolveConcert and validates the exact item and its assigned part. A mismatched or no-longer-visible identifier fails with a business error.

Member and route changes clear old state immediately. Results carry their member identity, and Compose rejects a result belonging to a previous selection. Old observation jobs are cancelled. Error text never prints raw database exceptions, identifiers or storage paths.

Current identity is a local member-selection entry point for this development phase, not authentication. Before deployment, replace it with a trusted authenticated session. The administration screen is a read-only summary and does not expose editing operations.

## Responsive Layout

Window constraints in dp determine layout; physical inches and pixel counts do not.

| Available Width | Layout |
| --- | --- |
| Under 600 dp | Compact, 16 dp horizontal padding, single column |
| 600-839 dp | Standard Pad, 32 dp padding, centered single column |
| 840-1199 dp | Expanded, adaptive library grid and split detail panes |
| 1200 dp and above | Large Pad, adaptive library grid and split detail panes |

Content is centered and capped at 1440 dp. Detail panes use weighted grid space rather than enlarged phone text. Lazy lists/grids use stable record IDs. Long headings wrap, and error/empty states scroll when vertical space is limited.

Native Robolectric screenshots were generated at 800 x 1200 dp and 1280 x 1800 dp (mdpi). Both were visually inspected: standard Pad detail is single-column; large portrait Pad detail is split. Text, icons and rows render without overlap. Pixel checks reject blank images. These are content-page screenshots using test fixtures, not physical-device captures.

## Storage and Offline Boundary

Connectivity observation uses Android's validated-network state. Offline does not disable local catalog or Domain queries. Network callbacks unregister when their flow is cancelled.

After authorization, file existence checks run on Dispatchers.IO. They accept only canonical paths inside app filesDir. A CACHED record for the exact active version can provide its managed cache path. OUTDATED cache files and paths outside managed storage are rejected. Missing local files have a specific recovery state.

This phase checks file existence/readability, not PDF parsing or SHA-256 verification. Those remain in the later import/offline implementation. No PDF renderer, annotation editor or Bluetooth implementation was added.

## Validation

Tasks: testDebugUnitTest, assembleDebug, lintDebug.

The final complete run passed all 41 tests with no failures or errors:

| Coverage | Tests |
| --- | ---: |
| Existing Domain/Room regression | 17 |
| Presentation Domain projections and unauthorized navigation | 7 |
| ViewModel state, retries, cancellation and member restoration | 5 |
| Compose detail, error, empty, offline and missing-file states | 5 |
| Navigation journeys, single-top roots and saved-stack restoration | 3 |
| Native Pad screenshot rendering | 2 |
| Room observation after permission revocation | 1 |
| Managed-path and stale-cache boundaries | 1 |

Issues found during verification were repaired: member-switch state is cleared immediately and carries an identity guard; unavailable default-part names are filtered in Domain even on the profile screen; ambiguous navigation test selectors now use unique button tags; screenshot capture uses native Canvas because the system PixelCopy callback timed out under Robolectric. Error recovery now opens the profile selection page. The old application placeholder was retired. New dependencies were moved into the existing version catalog and adaptive, round and monochrome launcher icons were added.

## Remaining Items

- Physical 20-27 inch Android devices still need interaction, density and manufacturer-specific runtime checks.
- Production authentication, administrative editing, import/download and PDF rendering are later-phase work.
- The transaction snapshot still reads the full library. Scoped database queries should replace it before very large libraries; this phase avoids main-thread resolution and per-row UI queries.
- Kotlin/Compose/Hilt/Coroutines versions remain on the previously verified toolchain. Dependency-update notices are not runtime failures and do not justify a toolchain upgrade in this phase.
- An empty database correctly starts with the member/catalog empty state. No demo records or PDFs are automatically inserted. UI fixtures exist only in tests and Preview.

Synced sources/ reference files were not modified.

## Artifacts

- APK: app/build/outputs/apk/debug/app-debug.apk
- Test report: app/build/reports/tests/testDebugUnitTest/index.html
- Lint report: app/build/reports/lint-results-debug.html
- Screenshots: app/build/reports/screenshots/standard-pad.png and large-portrait-pad.png
