# Keep PdfRenderer and related classes
-keep class android.graphics.pdf.** { *; }
-keepclassmembers class com.novapdf.reader.** { *; }
-dontwarn javax.annotation.**
# pdfbox-android references an optional JPEG2000 decoder which isn't bundled.
# Suppress R8 missing class errors so release builds can succeed without it.
-dontwarn com.gemalto.jp2.**

# Lucene, PdfBox, and ML Kit rely on extensive reflection. R8 was stripping
# their implementation classes from release builds which triggered runtime
# crashes when the screenshot harness tried to open documents. Keep their
# public APIs intact so the viewer can initialise search and OCR components
# safely when minification is enabled.
-keep class org.apache.lucene.** { *; }
-keep class com.tom_roush.** { *; }
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }
