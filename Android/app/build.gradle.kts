plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

ksp {
    // Room migration tests (MigrationTest.kt) need the real per-version schema JSON to
    // build historical databases from — hand-deriving CREATE TABLE SQL by reading the
    // entity source risks silently diverging from what Room actually generates.
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.trailmix.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.trailmix.app"
        minSdk = 31
        targetSdk = 35
        versionCode = 22
        versionName = "1.19.0"
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

    testOptions {
        unitTests {
            // REL-13: without this, any call into the android.jar stubs throws "not mocked",
            // which in practice meant a class became untestable the moment it logged a
            // warning — a bad trade when the classes worth testing hardest are the ones with
            // failure paths worth logging. Returning defaults affects only calls that
            // previously threw, so it cannot mask a passing assertion.
            isReturnDefaultValues = true
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
    // MigrationTest.kt: a real (pure-JVM) SQLite engine to run the production Migration
    // objects against, without needing Robolectric or an instrumented device — Android's
    // own android.database.sqlite classes are unit-test stubs that don't execute real SQL.
    testImplementation(libs.sqlite.jdbc)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // First instrumented tests (BLD-01): things a JVM unit test or a Robolectric shadow
    // can't be trusted for on this codebase — real MediaStore queries, real SAF I/O — run
    // against the tethered Pixel via ./gradlew connectedDebugAndroidTest.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
}
