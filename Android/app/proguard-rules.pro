# Add project specific ProGuard rules here.

# REL-02: minify/shrinkResources are now on for release. Room, Hilt, and Compose all ship
# their own consumer ProGuard rules (no extra keep needed here for them). ML Kit GenAI's
# request/response/options builders are used via generated Kotlin DSL + reflection-adjacent
# proto machinery internally — keep the whole package defensively since this app has never
# been built/tested with minification on and a stripped field there fails silently at
# runtime rather than at compile time.
-keep class com.google.mlkit.genai.** { *; }
-dontwarn com.google.mlkit.genai.**

