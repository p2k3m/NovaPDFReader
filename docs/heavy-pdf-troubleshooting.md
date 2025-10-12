# Heavy PDF Troubleshooting

This guide documents the synthetic "heavy" documents that NovaPDF uses to stress rendering, caching, and Adaptive Flow paths. Each section lists where the fixture comes from, how it is validated, and the expected behaviour when engineers reproduce failures locally.

## How the heavy suite runs

Continuous integration generates and opens two local stress fixtures on every device: a 32-page "unusual dimensions" document and a thousand-page pagination stress test. Instrumentation asserts that each variant opens without Application Not Responding (ANR) dialogs, renders sample pages, and activates Adaptive Flow before the job passes.【F:README.md†L232-L253】【F:app/src/androidTest/kotlin/com/novapdf/reader/LargePdfInstrumentedTest.kt†L35-L94】【F:app/src/androidTest/kotlin/com/novapdf/reader/PdfViewerUiAutomatorTest.kt†L52-L116】

## Known heavy and failure fixtures

| Fixture | Source | What it tests | Expected behaviour |
| --- | --- | --- | --- |
| `stress-large.pdf` | Generated on-device by `StressDocumentFactory` when tests call `installStressDocument` | Exercises Pdfium layout code with alternating portrait, landscape, infographic, and panorama pages | Repository `open()` succeeds and reports 32 pages; sampled pages expose aspect ratios matching the variant rotation; rendering each sampled page returns a bitmap and no ANRs are logged.【F:app/src/androidTest/kotlin/com/novapdf/reader/StressDocumentFactory.kt†L24-L127】【F:app/src/androidTest/kotlin/com/novapdf/reader/LargePdfInstrumentedTest.kt†L35-L95】 |
| `stress-thousand-pages.pdf` | Installed via `TestDocumentFixtures.installThousandPageDocument`; falls back to the deterministic writer in `ThousandPagePdfWriter` | Validates pagination performance, page-tree depth, and Adaptive Flow activation across 1,000 pages | Viewer UI reports 1,000 pages after swiping through the document, Adaptive Flow status becomes Active, and WorkManager stays idle until annotations are saved manually.【F:app/src/androidTest/kotlin/com/novapdf/reader/PdfViewerUiAutomatorTest.kt†L52-L119】【F:test-harness/src/main/kotlin/com/novapdf/reader/ThousandPagePdfWriter.kt†L9-L119】 |
| `monster-encrypted.pdf` | Test fixture decoded from `fixtures/monster-encrypted.base64` inside unit tests | Simulates third-party PDFs that are encrypted or password-protected | `PdfDocumentRepository.open()` throws `PdfOpenException` with `ACCESS_DENIED`; UI should surface the "permission" error string rather than crashing.【F:app/src/test/kotlin/com/novapdf/reader/data/PdfDocumentRepositoryEdgeCaseTest.kt†L98-L149】【F:presentation/viewer/src/main/kotlin/com/novapdf/reader/PdfViewerViewModel.kt†L1170-L1184】 |
| `oversized.pdf` | Created ad-hoc inside `PdfDocumentRepositoryEdgeCaseTest` | Covers the 100&nbsp;MiB safety ceiling for cached downloads | `PdfDocumentRepository.open()` returns `null`; callers should fall back to streaming/remote options and never attempt to render the oversized artefact.【F:app/src/test/kotlin/com/novapdf/reader/data/PdfDocumentRepositoryEdgeCaseTest.kt†L25-L55】 |

## Troubleshooting workflow

1. **Reproduce locally**. Run `./gradlew connectedAndroidTest` on a hardware-accelerated emulator to regenerate the fixtures and capture logcat. The helper script `tools/check_logcat_for_crashes.py` mirrors the CI watchdog, so a clean run there confirms that no ANRs or fatal signals occurred.【F:README.md†L232-L253】
2. **Confirm cache state**. Both stress documents write into the app cache using sanitized file names (`stress-large.pdf`, `stress-thousand-pages.pdf`). If `installStressDocument` or the thousand-page fixture fails, delete the cache directory so the generators can retry on the next run.【F:app/src/androidTest/kotlin/com/novapdf/reader/StressDocumentFactory.kt†L24-L64】【F:app/src/androidTest/kotlin/com/novapdf/reader/CacheFileNames.kt†L5-L33】
3. **Validate the binary**. The thousand-page generator performs structural checks (header, footer, page tree depth) before accepting a cached artefact. If repeated runs report validation failures, force regeneration by clearing the cache and watching for logcat entries from `TestDocumentFixtures`.【F:app/src/androidTest/kotlin/com/novapdf/reader/TestDocumentFixtures.kt†L294-L486】
4. **Match UI messaging**. When an encrypted or oversized file is encountered, ensure the UI surfaces the appropriate localized error instead of crashing or looping retries. Robolectric tests exercise these branches, so regressions typically point to mismatched error-code wiring or missing string resources.【F:app/src/test/kotlin/com/novapdf/reader/data/PdfDocumentRepositoryEdgeCaseTest.kt†L25-L149】【F:presentation/viewer/src/main/kotlin/com/novapdf/reader/PdfViewerViewModel.kt†L1170-L1184】

Following this checklist keeps the heavy PDF coverage green and helps isolate whether regressions stem from document generation, caching, or viewer presentation.
