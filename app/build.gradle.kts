plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

/** An app bundle (Play Store) splits by CPU type itself, and can't be built with APK splits. */
val bundling = gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }
val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

android {
    namespace = "org.tamodak.hisar"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "org.tamodak.hisar"
        // Same API levels as Killit. (compileSdk is newer: GeckoView needs 37.1.)
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        // The CPU types GeckoView ships its engine for. (LiteRT also has 32-bit x86, which would
        // otherwise make Play offer the app to phones it can't run on.) APKs get them from the
        // splits below; Android allows only one of the two.
        if (bundling) ndk { abiFilters += abis }

        // Where the picture filter's models are downloaded from on first start (they are not in the
        // APK, to keep it small): the GitHub release made by tools/vision/package_models.sh.
        // -Phisar.modelsUrl=… points a test build somewhere else (a local server).
        val modelsVersion = providers.gradleProperty("hisar.modelsVersion").get()
        val modelsUrl = providers.gradleProperty("hisar.modelsUrl")
            .getOrElse("https://github.com/tamodak/Hisar/releases/download/models-$modelsVersion/")
        buildConfigField("String", "MODELS_URL", "\"$modelsUrl\"")
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
        }
    }
    // GeckoView ships native libraries for three CPU types (about 160 MB each, uncompressed). One
    // APK per type keeps each install to its own.
    splits {
        abi {
            isEnable = !bundling
            reset()
            include(*abis.toTypedArray())
            isUniversalApk = false
        }
    }
    packaging {
        jniLibs {
            // LiteRT's GPU accelerator: the image models run on the CPU (its GPU delegate rejects
            // their operations), so 3 MB per CPU type saved.
            excludes += "**/libLiteRtClGlAccelerator.so"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // Settings shows the version name.
        buildConfig = true
    }
}

// The unit tests also run the browser extension's scripts (src/test/js), so they rerun when those change.
tasks.withType<Test>().configureEach {
    inputs.dir("src/main/assets/extensions").withPropertyName("extension")
    inputs.dir("src/test/js").withPropertyName("extensionTests")
}

room {
    // Exported schemas let future migrations be written and tested against the real history.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.geckoview)
    implementation(libs.litert) {
        // Google Play's model-download service for LiteRT's "AI Pack" feature, which Hisar does not
        // use (its models are in the APK). It would bring in proprietary Play services and
        // background-work permissions.
        exclude(group = "com.google.android.play", module = "ai-delivery")
    }
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
