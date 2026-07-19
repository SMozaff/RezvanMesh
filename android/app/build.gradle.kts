plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

// ---- git provenance (used in buildConfigField below) ----
val gitSha: String = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
}.standardOutput.asText.get().trim()

val gitBranch: String = providers.exec {
    commandLine("git", "rev-parse", "--abbrev-ref", "HEAD")
}.standardOutput.asText.get().trim()

android {
    setProperty("archivesBaseName", "RezvanMesh")
    namespace = "com.rezvani.mesh"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rezvani.mesh"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        buildConfigField("String",  "VERSION_NAME",    "\"${versionName}\"")
        buildConfigField("int",     "VERSION_CODE",    "${versionCode}")
        buildConfigField("String",  "BUILD_VARIANT",   "\"civilian\"")
        buildConfigField("String",  "GIT_SHA",         "\"$gitSha\"")
        buildConfigField("String",  "GIT_BRANCH",      "\"$gitBranch\"")
        buildConfigField("long",    "BUILD_TIME",      "${System.currentTimeMillis()}L")
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
            storeFile = if (keystorePath != null) file(keystorePath)
                        else project.rootProject.file("keystore.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias     = System.getenv("KEY_ALIAS")          ?: "rezvan"
            keyPassword  = System.getenv("KEY_PASSWORD")       ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled    = true
            isShrinkResources  = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            buildConfigField("boolean", "DEBUG_LOOPBACK",      "false")
            buildConfigField("boolean", "DEBUG_INJECT_PEERS",  "false")
        }
        debug {
            isDebuggable       = true
            applicationIdSuffix  = ".debug"
            versionNameSuffix    = "-debug"
            buildConfigField("boolean", "DEBUG_LOOPBACK",      "true")
            buildConfigField("boolean", "DEBUG_INJECT_PEERS",  "true")
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
        compose     = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "**/attach_hotspot_windows.dll"
            excludes += "META-INF/licenses/**"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        disable         += "MissingTranslation"
        disable         += "ExtraTranslation"
        checkReleaseBuilds = false
    }
}

dependencies {
    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // QR
    implementation("com.google.zxing:core:3.5.3")   // QR generation (BarcodeUtils)
    // Compose-native, on-device (no network calls) QR scanner. Not
    // zxing-android-embedded's CaptureActivity: that's an XML-layout-based
    // library activity whose customization surface (theming, orientation)
    // isn't easily controllable from a pure-Compose app with no XML layout
    // resources at all. CameraX + ML Kit gives a fully Compose-native
    // scanner screen with our own branding and automatic orientation
    // handling for free (see ui/screens/QrScannerScreen.kt).
    implementation("androidx.camera:camera-core:1.3.1")
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")
    implementation("com.google.mlkit:barcode-scanning:17.2.0")

    // Room + SQLCipher
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")

    // Security
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Local Broadcast Manager
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    // Unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("app.cash.turbine:turbine:1.0.0")

    // Android tests
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kapt {
    correctErrorTypes = true
}