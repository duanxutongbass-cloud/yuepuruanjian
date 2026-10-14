# 重奏团曲谱库

Android Kotlin / Jetpack Compose project, application version 0.9.7 (baseline 0.9.6, database schema 3).

0.9.7 separates PDF selection from import confirmation and suggests the title from the selected filename. See [PDF import entry fix](docs/PDF_IMPORT_ENTRY_REVIEW.md). Organizer-specific instrument/category recognition is awaiting the actual naming rules.

2026-10-14: Local main integrates the Android UI, annotation reliability, sync core, isolated HTTP/offline test adapter, and local management workflow/review prototype. See [local merge verification](docs/LOCAL_MERGE_REVIEW.md), [local manager](local-manager/README.md), and [HTTP test service](test-service/README.md).

- [Documentation](docs/README.md)
- [Roadmap](docs/PRODUCT_ROADMAP.md)
- [Current status and verification boundaries](docs/PROJECT_STATE.md)

Open with Android Studio; use Gradle 8.14.3 and a compatible JDK/Android Studio JBR. Configure your own Android SDK. Android SDK 36 Robolectric tests require JDK 21; the Android paper-workspace slice was also verified with Gradle 8.13. This snapshot does not include a Gradle wrapper; use a local Gradle installation.

Private scores, personal backups, signing keys, local configuration and generated build outputs are excluded. Included score.pdf is a generated synthetic test fixture.

Real-device acceptance remains pending; Phase 10 is planning, not a deployed NAS service.
