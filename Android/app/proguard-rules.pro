# Add project specific ProGuard rules here.

# REL-02: minify/shrinkResources are now on for release. Room, Hilt, and Compose all ship
# their own consumer ProGuard rules (no extra keep needed here for them). ML Kit GenAI's
# request/response/options builders are used via generated Kotlin DSL + reflection-adjacent
# proto machinery internally — keep the whole package defensively since this app has never
# been built/tested with minification on and a stripped field there fails silently at
# runtime rather than at compile time.
-keep class com.google.mlkit.genai.** { *; }
-dontwarn com.google.mlkit.genai.**

# sherpa-onnx (AI-01): the AAR's own bundled proguard.txt is empty — upstream never shipped
# the rules its own JNI bridge needs, and vendoring the AAR via implementation(files(...))
# (necessary since it isn't on Maven Central/JitPack — see the Gradle comment where it's
# added) means Gradle won't merge a consumer-rules.pro from it even if there were one.
# Confirmed by a real crash on the Pixel 9 Pro: a release (minified) build's R8 pass renamed/
# stripped OfflineSpeakerSegmentationModelConfig's fields, and the native layer's
# GetFieldID-by-name lookup then aborted the whole process (JNI DETECTED ERROR: fid == null)
# instead of throwing a catchable Kotlin exception — R8 has no way to see a field is used
# because the only reference to it is a hardcoded string inside libsherpa-onnx-jni.so.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

