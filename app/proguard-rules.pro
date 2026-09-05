# Sherpa Transcript ProGuard Rules

# ── Sherpa-ONNX JNI (nicht obfuskieren) ──────────────────────────────
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class com.k2fsa.sherpa.onnx.* {
    native <methods>;
}

# ── 0.12.0: Logging-Reduktion im Release (Threat Model T14) ──────────
# Alle Log.aufrufe werden im Release-Build entfernt (kein Leakage von
# internen Details via logcat). Debug-Builds bleiben unverändert.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
