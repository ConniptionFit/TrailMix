plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    // BLD-03: the static-analysis/style gate this project never had. Applied (not `apply
    // false`) at the root too so `./gradlew ktlintCheck`/`ktlintFormat` reach every module
    // uniformly as the project grows past the single `:app` module.
    alias(libs.plugins.ktlint)
}
