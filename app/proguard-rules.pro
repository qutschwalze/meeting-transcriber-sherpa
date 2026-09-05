# Sherpa Transcript ProGuard Rules

# ── Sherpa-ONNX JNI (nicht obfuskieren) ──────────────────────────────
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class com.k2fsa.sherpa.onnx.* {
    native <methods>;
}

# ── 0.11.3: Logging im Release – nur verbose/debug/info entfernen ──────
# Log.e und Log.w bleiben erhalten für Crash-Diagnose und田野テスト
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# ── Quick Settings Tile (R8 behält Manifest-Komponenten, aber zur Sicherheit) ──
-keep class com.sherpa.transcript.service.QuickStartTileService { *; }
-keep class com.sherpa.transcript.service.RecordingService { *; }
-keep class com.sherpa.transcript.MainActivity { *; }
-keep class com.sherpa.transcript.ui.live.LiveViewModel { *; }
