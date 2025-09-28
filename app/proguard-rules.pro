# Keep PdfRenderer and related classes
-keep class android.graphics.pdf.** { *; }
-keepclassmembers class com.novapdf.reader.** { *; }
-dontwarn javax.annotation.**
# pdfbox-android references an optional JPEG2000 decoder which isn't bundled.
# Suppress R8 missing class errors so release builds can succeed without it.
-dontwarn com.gemalto.jp2.**
