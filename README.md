# NovaPDF Reader

NovaPDF Reader is a Jetpack Compose Android application that experiments with "Adaptive Flow Reading" for fluid PDF consumption,
annotation, and accessibility enhancements on modern Android devices.

## Sample PDF fixture

Automated tests and screenshot generation expect a tiny CC0 1.0 licensed document that lives outside of the repository on S3.
By default the project points at `https://novapdf-sample-assets.s3.us-west-2.amazonaws.com/sample.pdf`, a CC0 1.0 document sized
specifically for automated smoke tests. If you need to swap in a different document, provide the HTTPS location of that object
through the Gradle property `-PnovapdfSamplePdfUrl=` when invoking instrumentation workflows (for example,
`./gradlew connectedCheck -PnovapdfSamplePdfUrl=https://your-bucket.s3.amazonaws.com/sample.pdf`). The instrumentation runner
receives the same URL through its arguments, downloads the file into the test cache, and renders it to validate the viewer
pipeline without shipping binary fixtures in source control.

See `docs/sample-pdf-license.md` for the redistribution notice covering the hosted document.

## Gradle wrapper bootstrap

Binary assets such as the `gradle-wrapper.jar` are intentionally not stored in this repository. Instead, the wrapper JAR is stored
as a Base64 text file at `gradle/wrapper/gradle-wrapper.jar.base64`. The included `gradlew` and `gradlew.bat` scripts automatically
decode this archive to `gradle/wrapper/gradle-wrapper.jar` (Gradle 8.5) the first time you run them.

If your environment blocks execution of Python, PowerShell, or the `base64` utility, manually decode the file or download the wrapper
from `https://services.gradle.org/distributions/gradle-8.5-bin.zip` and copy `gradle-8.5/lib/plugins/gradle-wrapper-8.5.jar`
to `gradle/wrapper/gradle-wrapper.jar` before invoking Gradle.
