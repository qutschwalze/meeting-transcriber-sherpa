# Add project specific ProGuard rules here.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class com.k2fsa.sherpa.onnx.* {
    native <methods>;
}
