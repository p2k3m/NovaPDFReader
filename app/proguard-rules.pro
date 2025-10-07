# Keep PdfRenderer and app surface API used by Pdfium to avoid reflection loss
# when R8 full mode is enabled.
-keep class android.graphics.pdf.** { *; }
-keepclassmembers class com.novapdf.reader.** { *; }

# Pdfium wraps native handles behind reflection-heavy Java helpers. R8 full
# mode strips those helpers without a keep rule which results in crashes when
# PdfiumCore loads a document.
-keep class com.shockwave.pdfium.** { *; }

# pdfbox-android references an optional JPEG2000 decoder which isn't bundled.
# Suppress R8 missing class errors so release builds can succeed without it.
-dontwarn com.gemalto.jp2.**

# PdfBox performs service loading to bridge PdfRenderer and Pdfium when
# documents need repairing. Preserve its Android-facing types so Pdf repair
# succeeds under R8 full mode.
-keep class com.tom_roush.** { *; }

# Lucene powers in-document search by using reflective lookups to instantiate
# analyzers and codecs. Keep its public APIs so the search index stays usable.
-keep class org.apache.lucene.** { *; }

# ML Kit dynamically discovers text recognition pipelines. Preserve the public
# surface and the internal ML Kit runtime namespaces that are reflectively
# loaded at startup.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }

# Allow Pdfium/PdfBox wiring to reference javax.annotation Nullable without
# pulling in the full dependency tree.
-dontwarn javax.annotation.**
