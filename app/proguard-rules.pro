# ── OkHttp ─────────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# ── org.json ────────────────────────────────────────────────────────────────
-keep class org.json.** { *; }

# ── PaddleOCR (NCNN JNI) ────────────────────────────────────────────────────
-keep class com.equationl.ncnnandroidppocr.** { *; }
-keep class com.equationl.ncnnandroidppocr.bean.** { *; }

# ── Kotlin ──────────────────────────────────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-keep class kotlin.Metadata { *; }

# ── App models (used by JSON / SharedPreferences) ───────────────────────────
-keep class com.example.aiassistant.AreaSelectionOverlay { *; }
