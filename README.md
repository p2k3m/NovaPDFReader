# NovaPDF Reader

NovaPDF Reader is a Jetpack Compose Android application that experiments with "Adaptive Flow Reading" for fluid PDF consumption, annotation, and accessibility enhancements on modern Android devices.

## Adaptive Flow performance tooling

Adaptive Flow now records frame pacing through `Choreographer` on the main thread so that preloading logic can back off when the UI is under pressure. Two dedicated Gradle tasks are available to exercise the timing heuristics and frame monitoring in isolation:

```
./gradlew adaptiveFlowPerformance
./gradlew frameMonitoringPerformance
```

Both tasks reuse the Robolectric unit tests backing the Adaptive Flow manager and give fast feedback without running the full unit test suite.

## Sample PDF fixture

Automated tests and screenshot generation rely on a tiny CC0 1.0 licensed document that now ships inline with the instrumentation test sources. The encoded fixture is decoded directly into the device cache before opening it in the viewer so rendering can be validated without relying on external network services or bundling binary blobs in git.

See `docs/sample-pdf-license.md` for the redistribution notice covering the bundled document.

## Running connected Android tests locally

Instrumentation and macrobenchmark tests require an Android SDK installation that includes
the `platform-tools`, `build-tools`, and emulator components for API level 32.

1. Install the Android command-line tools and use `sdkmanager` to download the required
   packages:

   ```bash
  sdkmanager "platform-tools" "build-tools;34.0.0" "platforms;android-32" "emulator"
   ```

2. Point Gradle to your SDK installation by setting `ANDROID_SDK_ROOT`/`ANDROID_HOME` or by
   adding an `sdk.dir=/absolute/path/to/sdk` entry to `local.properties`.

3. Ensure that a device or emulator is available before invoking:

   ```bash
   ./gradlew connectedAndroidTest
   ```

When no device is present, the build gracefully skips connected tests while still verifying
that the project compiles.

## CI validation for heavy PDF workloads

Continuous integration now provisions a synthetic stress PDF with 32 pages that mix large,
panoramic, and extreme aspect ratios to exercise Pdfium rendering paths. Instrumentation
tests open portrait, landscape, tall infographic, and ultra-wide panorama variants of the
document and drive a thousand-page fixture through the UI to ensure the viewer can handle
atypical source material. The workflow invokes `connectedAndroidTest` with
`--rerun-tasks --no-build-cache` so the heavy document scenarios always execute on every
matrix device instead of being satisfied from prior outputs. It fails fast if logcat
reports an Application Not Responding dialog, fatal Java exception, fatal signal, or forced
process restart for `com.novapdf.reader`. It also verifies that both the
`LargePdfInstrumentedTest.openLargeAndUnusualDocumentWithoutAnrOrCrash` and
`PdfViewerUiAutomatorTest.loadsThousandPageDocumentAndActivatesAdaptiveFlow` cases ran
without being skipped so regressions cannot silently avoid the heavy document coverage. To
reproduce the checks locally, run `./gradlew connectedAndroidTest` on an emulator or device
and inspect `adb logcat` for `ANR in com.novapdf.reader` or fatal exception entries.

## Gradle wrapper bootstrap

Binary assets such as the `gradle-wrapper.jar` are intentionally not stored in this repository. Instead, the wrapper JAR is stored as a Base64 text file at `gradle/wrapper/gradle-wrapper.jar.base64`. The included `gradlew` and `gradlew.bat` scripts automatically decode this archive to `gradle/wrapper/gradle-wrapper.jar` (Gradle 8.5) the first time you run them.

If your environment blocks execution of Python, PowerShell, or the `base64` utility, manually decode the file or download the wrapper from `https://services.gradle.org/distributions/gradle-8.5-bin.zip` and copy `gradle-8.5/lib/plugins/gradle-wrapper-8.5.jar` to `gradle/wrapper/gradle-wrapper.jar` before invoking Gradle.
