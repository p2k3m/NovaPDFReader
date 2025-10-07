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

## Baseline profile generation and macrobenchmarks

NovaPDF ships a baseline profile so cold starts and the initial render of large documents
benefit from ahead-of-time compilation. The profile lives at
`app/src/main/baseline-prof.txt` and is regenerated from the Macrobenchmark test suite in
the `baselineprofile` module.

1. Boot a physical device or emulator running API 32+ with hardware acceleration enabled.
2. Install the debug build once so shared test fixtures are staged:

   ```bash
   ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
   ```

3. Execute the macrobench regressions to enforce the current performance budgets:

   ```bash
   ./gradlew :baselineprofile:connectedBenchmarkAndroidTest --stacktrace
   ```

   The run fails if cold-start, frame pacing, or render times regress beyond the thresholds
   encoded in `RegressionBenchmark`.

4. Generate a fresh baseline profile and copy it into the application module:

   ```bash
   ./gradlew :app:generateReleaseBaselineProfile --stacktrace
   cp app/build/outputs/baselineProfile/release/baseline-prof.txt app/src/main/baseline-prof.txt
   ```

5. Review the diff and commit the updated file together with any performance-sensitive
   code changes:

   ```bash
   git diff -- app/src/main/baseline-prof.txt
   ```

The CI workflow repeats these steps on a matrix device and fails the build if the generated
profile diverges from the committed snapshot, preventing stale artefacts from shipping.

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
The helper script `tools/check_logcat_for_crashes.py` mirrors the CI check and can be
run locally with a captured logcat dump to confirm that no ANR or crash signatures were
recorded:

```bash
adb logcat -d > logcat-after-tests.txt
tools/check_logcat_for_crashes.py
```

Pass a different log path or package name if needed:

```bash
tools/check_logcat_for_crashes.py path/to/log.txt --package com.example.app
```

## Gradle wrapper bootstrap

Binary assets such as the `gradle-wrapper.jar` are intentionally not stored in this repository. Instead, the wrapper JAR is stored as a Base64 text file at `gradle/wrapper/gradle-wrapper.jar.base64`. The included `gradlew` and `gradlew.bat` scripts automatically decode this archive to `gradle/wrapper/gradle-wrapper.jar` (Gradle 8.5) the first time you run them.

If your environment blocks execution of Python, PowerShell, or the `base64` utility, manually decode the file or download the wrapper from `https://services.gradle.org/distributions/gradle-8.5-bin.zip` and copy `gradle-8.5/lib/plugins/gradle-wrapper-8.5.jar` to `gradle/wrapper/gradle-wrapper.jar` before invoking Gradle.
