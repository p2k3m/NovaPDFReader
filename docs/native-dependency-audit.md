# Native dependency audit

NovaPDF Reader relies on a handful of libraries that either bundle native code or originate from desktop-first ecosystems. We maintain runtime coverage for each dependency across the Android API levels we support (30, 32, and 34) to ensure compatibility regressions are caught quickly.

## Dependency coverage summary

| Library | Purpose | Runtime validation |
| --- | --- | --- |
| `com.github.mhiew:pdfium-android` | Powers high-fidelity PDF rendering through Pdfium. | `pdfiumRendersSamplePage` opens the bundled sample document with `PdfiumCore` to validate JNI loading and page access on device.【F:app/src/androidTest/kotlin/com/novapdf/reader/NativeDependencyCompatibilityTest.kt†L49-L71】 |
| `com.tom-roush:pdfbox-android` | Extracts searchable text and metadata from PDFs. | `pdfBoxAndroidExtractsText` initialises `PDFBoxResourceLoader` and confirms text extraction from the sample document.【F:app/src/androidTest/kotlin/com/novapdf/reader/NativeDependencyCompatibilityTest.kt†L73-L90】 |
| Apache Lucene (`lucene-core`, `lucene-analyzers-common`) | Provides the indexing pipeline for on-device search. | `luceneTokenizesTextOnDevice` instantiates `StandardAnalyzer` and verifies token emission inside instrumentation tests.【F:app/src/androidTest/kotlin/com/novapdf/reader/NativeDependencyCompatibilityTest.kt†L92-L107】 |
| ML Kit on-device text recognition (`com.google.mlkit:text-recognition`) | Enables OCR for imported documents when indexes are missing. | `mlKitTextRecognizerProcessesFrame` runs a recognition pass on a synthetic NV21 frame to ensure the native ML runtime initialises correctly.【F:app/src/androidTest/kotlin/com/novapdf/reader/NativeDependencyCompatibilityTest.kt†L109-L143】 |

## Test matrix

Managed virtual devices are configured for API levels 30, 32, and 34. Running `./gradlew nativeDependencyAndroidTestMatrix` executes the full instrumentation suite—including the compatibility checks above—against all three system images so regressions are caught before release.【F:app/build.gradle.kts†L350-L378】【F:app/build.gradle.kts†L419-L431】
