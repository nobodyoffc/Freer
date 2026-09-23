plugins {
    id("com.android.application")
}

android {
    namespace = "com.fc.freer"
    compileSdk = 34

    signingConfigs {
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/freer-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            // Credentials live outside the repo, in ~/.gradle/gradle.properties.
            // Nothing here falls back to the debug key: a release that cannot be
            // signed privately fails in packageRelease instead of shipping.
            val storePath = providers.gradleProperty("FREER_RELEASE_STORE_FILE").orNull
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = providers.gradleProperty("FREER_RELEASE_STORE_PASSWORD").orNull
                keyAlias = providers.gradleProperty("FREER_RELEASE_KEY_ALIAS").orNull
                keyPassword = providers.gradleProperty("FREER_RELEASE_KEY_PASSWORD").orNull
            }
            // v3 carries a rotation proof, so this key can be replaced later
            // without forcing every user to uninstall. minSdk 28 makes v1 dead weight.
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    defaultConfig {
        applicationId = "com.fc.freer"
        minSdk = 28
        targetSdk = 34
        versionCode = 30202
        versionName = "3.2.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // libopus for voice calls (VOICE_SPEC §9.1), about 300 KB per ABI.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    ndkVersion = "27.1.12297006"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.hawk) // Keep for migration
    implementation("com.tencent:mmkv:1.3.9") // New primary database
    implementation(libs.swiperefreshlayout)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity:1.8.2")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation(project(":FC-AJDK"))
    
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")

    // ZXing for QR code generation
    implementation(libs.core)

    // CameraX dependencies
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.camera.extensions)
    
    // Guava for ListenableFuture
    implementation(libs.guava)
    implementation("com.google.guava:guava:32.1.3-android")
    
    // Gson for JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")
    
    // WorkManager for background tasks
    implementation("androidx.work:work-runtime:2.9.0")
}

tasks.matching { it.name == "packageRelease" }.configureEach {
    doFirst {
        val missing = listOf(
            "FREER_RELEASE_STORE_FILE",
            "FREER_RELEASE_STORE_PASSWORD",
            "FREER_RELEASE_KEY_ALIAS",
            "FREER_RELEASE_KEY_PASSWORD"
        ).filter { providers.gradleProperty(it).orNull.isNullOrBlank() }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release signing is not configured; missing $missing. " +
                    "Set these in ~/.gradle/gradle.properties. Release builds are " +
                    "never signed with the debug key."
            )
        }
    }
}
