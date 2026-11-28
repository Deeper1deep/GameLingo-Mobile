plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// The release keystore is never committed. The publish workflow drops it at
// keystore/gamelingo-release.keystore and passes the passwords as gradle
// properties, all sourced from GitHub Secrets.
val releaseKeystoreFile = rootProject.file("keystore/gamelingo-release.keystore")

android {
    namespace = "com.gamelingo.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gamelingo.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 28
        versionName = "0.9.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    signingConfigs {
        // Read signing secrets from gradle properties (local dev) with an
        // environment-variable fallback of the same name (CI passes env vars).
        fun signingSecret(name: String): String? =
            providers.gradleProperty(name).orNull ?: providers.environmentVariable(name).orNull
        if (releaseKeystoreFile.exists()) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = signingSecret("GAMELINGO_KEYSTORE_PASSWORD")
                keyAlias = signingSecret("GAMELINGO_KEY_ALIAS")
                keyPassword = signingSecret("GAMELINGO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // ~99MB of the APK is ML Kit native code duplicated per ABI.
            // Shipping arm64-v8a (every modern phone, incl. the Galaxy A35)
            // plus armeabi-v7a (older 32-bit devices) roughly halves the APK
            // without removing any feature; x86/x86_64 are emulator ABIs and
            // stay available in debug builds.
            ndk {
                abiFilters += setOf("arm64-v8a", "armeabi-v7a")
            }
            // Unsigned unless the fixed keystore is present. The publish
            // workflow refuses to continue without a signed, verified APK.
            if (releaseKeystoreFile.exists()) {
                signingConfig = signingConfigs.findByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // Unit tests read the SHIPPED datasets, not copies that can drift.
    sourceSets {
        getByName("test") {
            resources.srcDir("src/main/assets/understanding")
            // The Egyptian dictionary too: a copied fixture silently drifted
            // from the shipped file once already.
            resources.srcDir("src/main/assets/local_ai")
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0"
            )
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }


    // Unit tests on constrained CI/dev boxes: cap the forked test JVM so the
    // Robolectric suites cannot starve the Gradle/Kotlin daemons.
    tasks.withType<Test>().configureEach {
        maxHeapSize = "768m"
    }

    testOptions {
        // Robolectric migration tests read the exported Room schema JSONs
        // from src/test/assets; without this assets never reach the test runtime.
        unitTests.isIncludeAndroidResources = true
        // Lets the JVM render test's PNG (base64 between markers) reach the CI log.
        unitTests.all { it.testLogging.showStandardStreams = true }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.translate)
    // Offline speech recognition (Apache 2.0). The model is NOT bundled: the user downloads it.
    implementation(libs.vosk.android)
    implementation(libs.onnxruntime.mobile)
    implementation(libs.litertlm.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    // Room migration tests run on the JVM via Robolectric: no emulator needed.
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core)
}

// Keep every Kotlin module on metadata the Hilt javac processor can read.
configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:2.3.20")
    }
}
