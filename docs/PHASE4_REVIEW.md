# Phase 4: Native PDF Reader

Historical evidence only. Current status is in PROJECT_STATE.md. PDF import was subsequently delivered in Phase 4.5; its absence below is a Phase 4 boundary, not the current state.

Status: passed for the Phase 4 native PDF reader scope.

Final tasks completed successfully: testDebugUnitTest, assembleDebug, lintDebug, and connectedDebugAndroidTest. All 53 tests passed: 48 JVM tests and 5 actual Android 14 emulator tests, with zero failures or skipped platform tests. Lint reports zero errors and 10 non-blocking warnings (nine dependency update notices and one optional KTX helper suggestion).

## Implementation

The production reader uses Android's PdfRenderer (API 21+, app minimum API 26). No third-party PDF dependency is added to the APK. Platform rendering and OS updates own the parser implementation: https://developer.android.com/reference/android/graphics/pdf/PdfRenderer

Only Domain-authorized ResolvedScoreSegment values reach the reader. Navigation supplies IDs, never arbitrary file paths. Personal concert viewing still reruns the existing Domain concert resolver. The renderer accepts only app-managed files and exact-version CACHED fallback files.

SHA-256 is checked through a duplicated descriptor of the same opened file used by PdfRenderer. Renaming/replacing a managed path between verification and rendering cannot select a different inode. A hash mismatch fails explicitly; it does not fall back to an older version.

Every binding range is validated against the actual document page count, and rendering itself also rejects a page outside that binding. The page sequence concatenates authorized ranges without exposing intervening pages. Database page ranges remain inclusive and one-based; only the renderer boundary converts to zero-based indexes.

Fit Page centers the full page. Fit Width renders at the actual viewport width and supports vertical dragging. It rasterizes the visible viewport at native pixel dimensions rather than enlarging a small full-page bitmap. Different page dimensions are read from each actual PDF page.

The viewport has a maximum 12-million-pixel allocation and an additional heap-based budget. Oversized allocations and bitmap allocation failures produce LoadFailed rather than silently switching to blurry rendering. No multi-page bitmap cache is retained. Native render work is serialized; cancelled/unpublished frames are recycled, while published frames are released by normal Android ownership/GC to avoid recycling a bitmap still being drawn.

PDF descriptors, renderer instances and page handles close after each operation. Hashing and native rendering run off the main thread. Scroll re-renders are debounced; a native call already running is allowed to finish, but cannot overwrite a newer request or version.

## State and Recovery

- FileMissing: missing or unavailable managed file, with no valid exact-version cache fallback.
- Corrupted: hash mismatch or an unreadable/malformed PDF.
- ActiveVersionMissing: explicit Domain/reader version failure.
- InvalidPageRange: bad binding range, actual document bounds violation or attempted page outside the authorized segment.
- LoadFailed: infrastructure/rendering failure or allocation budget exceeded.

Domain errors remain authoritative before file opening. Changing the member, version, binding or range cannot display a previous binding's frame: identity guards, request generations and cancellation protect the UI.

Page index and fit mode use SavedStateHandle keys including member, route context, part, binding, active version, hash and range. Session re-entry and framework state restoration recover the current page. Another member/version starts separately; rollback may restore the page previously used with that version. This is saved-instance/session restoration, not permanent database history.

The reader hides ordinary bottom navigation and system bars, with swipe-to-reveal system controls. Its PDF viewport is full-width and not constrained by the ordinary page content cap. Navigation back restores ordinary chrome.

## Annotation and Pedal Boundaries

PdfPageTransform exposes PDF page points (1/72 inch), viewport pixels, scale/offset and forward/inverse transforms. Origin is the rendered page's top-left. Normalized coordinates remain in 0..1, unaffected by display density, fit mode or scroll. Letterbox touches return no page coordinate.

PdfState.Ready exposes the exact version/binding through PdfPageRef and its zero-based source page index. Future annotations must retain that version/page identity, not just a screen coordinate. No annotation editor is implemented.

ReaderAction and nextPage/previousPage are the common controller actions for future input adapters. Both clamp at authorized sequence boundaries. No Bluetooth implementation is added.

## Verification Plan and Results

Existing 41 tests remain the regression baseline. New JVM tests cover disjoint page ranges, index boundaries, coordinate round trips across window sizes, fit/pan, current-page and fit-mode restoration, version/member isolation, resize, error recovery, and delayed old-version frame cleanup.

Robolectric does not provide an operational PDF document/renderer implementation in this environment. Real PDF tests were moved to Android instrumentation rather than substituting a fake renderer. A standard four-page vector score fixture is generated by ReportLab, checked with pypdf and visually rendered with Poppler. These are development tools only; the fixture is packaged solely in the test APK.

Device tests open and render real PDF bytes, check pixels and varying page sizes, validate missing/corrupted/wrong-hash/out-of-range cases, reject stale caches and oversized viewports, and exercise the Compose reader at ordinary and large portrait Pad sizes.

The Room-to-renderer version-switch test passed: after activeVersionId switches to a missing v2 file, the still-existing v1 PDF is never used as fallback. Native bitmap tests passed at 800 x 1000 and 2160 x 3500 pixels, with exact viewport dimensions and visible rendered content.

Reader UI tests passed at 800 x 1200 and 1280 x 1800 window configurations, including actual touch swipes in Fit Width mode. Exported screenshots are captures of the real Android reader component and real PDF renderer, not mock bitmaps. Both final screenshots were inspected: controls fit on one line, labels do not overlap, the authorized first page is PDF page 2, and staff lines/notes are sharp. The captured Compose root excludes system bars, so image heights differ from the full window configurations.

Issues discovered were repaired before acceptance: unsupported JVM PDF generation/rendering was replaced by real device testing with a standard fixture; the page-label assertion now checks the intended substring; screenshot export uses the device read/write pipe and survives test teardown; test SDK annotations follow Android test lint requirements; mode labels no longer wrap; scroll state and pointer geometry reset with version/window changes; error headers no longer imply indefinite loading.

The temporary emulator was shut down after validation. Official system images and isolated test tooling remain available for repeat runs; generated tool/AVD directories are ignored by Git.

## Artifacts

- Application APK: app/build/outputs/apk/debug/app-debug.apk
- JVM test report: app/build/reports/tests/testDebugUnitTest/index.html
- Android test report: app/build/reports/androidTests/connected/debug/index.html
- Static review: app/build/reports/lint-results-debug.html
- Native reader screenshots: app/build/reports/screenshots/pdf-standard-pad.png and pdf-large-pad.png

## Remaining Scope

- Physical manufacturer devices still need touch, density and prolonged performance verification.
- Very large/complex scanned PDFs incur repeated streamed hashing and file opening. Before adding caches, preserve immutable-version and same-file-descriptor guarantees.
- Password-protected/unreadable PDFs are rejected in this batch; no password entry flow is provided.
- Full annotation editing, Bluetooth integration, production authentication and PDF import administration remain later work.
- sources/ was not modified; no sample records are seeded into the production database.
