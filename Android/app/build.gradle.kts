plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ktlint)
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
        versionCode = 24
        versionName = "1.20.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // sherpa-onnx's AAR bundles all 4 ABIs (~50MB uncompressed: ONNX Runtime + its own
        // native libs, ×4). Real targets for this app are the Pixel 9 Pro (arm64-v8a) and the
        // local emulator (x86_64) — armeabi-v7a/x86 are 32-bit and irrelevant to both, so
        // filtering them out here roughly halves what actually ships.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // sherpa-onnx's native loader mmaps these assets directly out of the APK rather than
    // buffering through a decompressing read — real Pixel 9 Pro testing found this crashes
    // the process with SIGABRT deep in libsherpa-onnx-jni.so's newFromAsset() when the asset
    // is DEFLATE-compressed (Android's default for any extension not on its no-compress
    // list), since there's then no contiguous byte range to map. Storing them uncompressed
    // is the same fix TensorFlow Lite's own Android docs prescribe for the identical
    // mmap-a-bundled-model pattern.
    androidResources {
        noCompress += listOf("onnx")
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
                "proguard-rules.pro",
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

    lint {
        // BLD-03: `./gradlew lint` had never been run as part of this project's own handoff
        // checklist, so nothing enforced it — this baseline is that gate's day-zero snapshot,
        // not a blanket suppression. Everything in it is a *deferred*, already-tracked category,
        // not an unnoticed one: ~40 GradleDependency/AndroidGradlePluginVersion warnings are
        // BLD-04's own dependency-currency backlog item verbatim; OldTargetApi is the same
        // currency question for compileSdk/targetSdk; SelectedPhotoAccess (Android 14+ partial
        // photo access) is a real UX enhancement, not a defect, and bigger than a lint fix;
        // ObsoleteSdkInt's suggestion to drop mipmap-anydpi-v26's version qualifier was tried
        // and reverted — it broke AAPT2 resource linking outright ("resource mipmap/ic_launcher
        // not found"), so the qualifier stays despite what the generic heuristic claims. Every
        // *other* finding from the day this baseline was created was fixed outright, not
        // deferred (see BLD-03 in Future Improvements.md for the fixed list). A lint run that
        // reports anything beyond this baseline is a genuinely new finding.
        baseline = file("lint-baseline.xml")
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
    // sherpa-onnx (AI-01/AI-12 migration spike): the real, officially pre-built Android AAR
    // downloaded directly from the GitHub release, not a Maven Central/JitPack coordinate —
    // k2-fsa doesn't publish to Maven Central, and this vendored-file approach avoids adding a
    // dependency on JitPack's build service ever being up. Bumping to a newer sherpa-onnx
    // release is: download its .aar, replace this file, update the version in its name/comment.
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
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
    // Compose UI tests target dependency-free composables only (plain data/callbacks, no
    // ViewModel/Hilt/DataStore) — SettingsRepository and friends use the same DataStore file
    // name as the real app, so an instrumented test that constructed one for real would read
    // and write actual user settings on whatever device runs it. See project memory.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
