plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.trailmix.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.trailmix.app"
        minSdk = 31
        targetSdk = 35
        versionCode = 11
        versionName = "1.10.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    /**
     * REL-07 (v1.10.0): a real, stable release key, replacing REL-02's debug-key placeholder.
     *
     * Why this had to change: the debug keystore is **auto-generated per machine**, so a
     * release cut on one machine cannot update an install that came from another — Android
     * rejects it as a signature mismatch, and the only workaround is an uninstall, which for
     * this app means permanent note loss (`allowBackup=false`, local-only, no cloud copy).
     * That directly threatened DIST-01/Obtainium, whose whole premise is in-place updates.
     *
     * Credentials live in the **Gradle home** (`~/.gradle/gradle.properties`), never in this
     * repo, so key material cannot be committed. When they're absent the build falls back to
     * the debug key so anyone can still clone and `assembleDebug`/`assembleRelease` — but the
     * resulting release APK is NOT publishable, because it won't upgrade real installs.
     * See [[Build and Deployment]] for the backup requirement.
     */
    val releaseStore = (findProperty("TRAILMIX_STORE_FILE") as String?)?.let(::file)
    val hasReleaseKey = releaseStore?.exists() == true

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseStore
                storePassword = findProperty("TRAILMIX_STORE_PASSWORD") as String?
                keyAlias = findProperty("TRAILMIX_KEY_ALIAS") as String?
                keyPassword = findProperty("TRAILMIX_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        release {
            // REL-02: R8 shrink/obfuscate + resource shrinking for release, previously off.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.mlkit.genai.prompt)
    implementation(libs.mlkit.genai.speech)
    implementation(libs.androidx.documentfile)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
