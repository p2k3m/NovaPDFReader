# Pdfium thousand-page crash

## Summary

* **Date discovered:** 2024-09-19
* **Area:** Pdf rendering (Pdfium integration)
* **Symptom:** Instrumentation screenshot harness crashed the `com.novapdf.reader` process while opening the 1,000-page stress document. `pdfiumCore.newDocument` triggered a native abort inside the legacy `com.github.barteksc:pdfium-android:1.9.0` binaries.
* **Reproduction:**
  ```bash
  adb shell am instrument -w -r \
      -e runScreenshotHarness true \
      -e class com.novapdf.reader.ScreenshotHarnessTest#openThousandPageDocumentForScreenshots \
      com.novapdf.reader.test/androidx.test.runner.AndroidJUnitRunner
  ```
  Inspect `adb logcat` for the fatal signal that terminates the app process before the viewer reaches its ready flag.

## Mitigation

* Upgraded Pdfium to `com.github.mhiew:pdfium-android:1.9.2`, which ships the upstream crash fix for extremely large documents on Android 13+.
* Regenerated the bundled thousand-page PDF fixture with a balanced `/Pages` tree so the harness no longer drives the legacy ReportLab asset that exposed huge `/Kids` arrays.
* Preferred the deterministic on-device writer for the harness fixture generation so outdated bundled assets cannot reintroduce the regression.
* Keep the screenshot harness in the connected test suite so regressions surface quickly in CI.
* When diagnosing related issues, capture a logcat trace while running the harness to confirm whether Pdfium threw a managed exception (handled by `PdfDocumentRepository`) or a native abort.

## Verification

* `./gradlew test`
* Screenshot harness reaches the ready state without a process crash (requires a device/emulator).
