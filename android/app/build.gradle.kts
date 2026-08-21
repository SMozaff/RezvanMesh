plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // kotlin-kapt replaced by KSP -- see root build.gradle.kts for the full
    // rationale (kapt's real Kotlin-2.x compatibility problems, confirmed
    // by this exact build failure; Google's own Room docs recommend KSP for
    // Kotlin 2.0+). Confirmed via full-repo search that Room's compiler was
    // the ONLY thing in this project using kapt -- no other kapt(...)
    // dependency declarations exist, so kotlin-kapt can be fully removed
    // rather than kept alongside KSP for some other library.
    id("com.google.devtools.ksp")
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

    // android.kotlinOptions {} removed -- deprecated since Kotlin 2.0.0, and
    // its deprecation level was raised to a build ERROR starting Kotlin
    // 2.2.0 (this project targets 2.2.20) per Kotlin's own official "What's
    // new in 2.2.0" notes. Migrated to the top-level kotlin.compilerOptions
    // {} DSL below, per the official migration guide
    // (developer.android.com/build/migrate-to-built-in-kotlin and
    // kotlinlang.org/docs/gradle-compiler-options.html).

    buildFeatures {
        compose     = true
        buildConfig = true
    }

    // composeOptions { kotlinCompilerExtensionVersion = "..." } removed --
    // no longer valid from Kotlin 2.0+. The Compose Compiler now ships in
    // lockstep with the Kotlin plugin itself via the
    // org.jetbrains.kotlin.plugin.compose Gradle plugin applied above.

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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

val requiredNativeAbis = listOf("arm64-v8a", "armeabi-v7a")
val verifyNativeLibraries = tasks.register("verifyNativeLibraries") {
    group = "verification"
    description = "Fails the build when the Rust JNI libraries are absent from the APK source tree."
    doLast {
        val missing = requiredNativeAbis.mapNotNull { abi ->
            val library = project.file("src/main/jniLibs/$abi/librezvan_core.so")
            if (library.isFile && library.length() > 0L) null else library.path
        }
        check(missing.isEmpty()) {
            "Missing Rust JNI libraries: ${missing.joinToString()}. " +
                "Run scripts/build_rust.sh before assembling the APK."
        }
    }
}

tasks.named("preBuild") {
    dependsOn(verifyNativeLibraries)
}

dependencies {
    // Kotlin -- version should track the Kotlin Gradle plugin version
    // declared in the root build.gradle.kts (2.2.20, stepped back through 2.3.0 and 2.2.21 from an
    // initial 2.3.0 attempt after a KSP/AGP version-compatibility issue --
    // see the ksp plugin comment in root build.gradle.kts for the full
    // story); this was previously hardcoded to 1.9.22, which would have
    // mismatched the plugin version after the AGP/Kotlin/Gradle coordinated
    // upgrade regardless of which exact 2.x version was ultimately chosen.
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.20")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // QR
    implementation("com.google.zxing:core:3.5.4")   // QR generation (BarcodeUtils)
    // Compose-native, on-device (no network calls) QR scanner. Not
    // zxing-android-embedded's CaptureActivity: that's an XML-layout-based
    // library activity whose customization surface (theming, orientation)
    // isn't easily controllable from a pure-Compose app with no XML layout
    // resources at all. CameraX + ML Kit gives a fully Compose-native
    // scanner screen with our own branding and automatic orientation
    // handling for free (see ui/screens/QrScannerScreen.kt).
    implementation("androidx.camera:camera-core:1.5.1")
    implementation("androidx.camera:camera-camera2:1.5.1")
    implementation("androidx.camera:camera-lifecycle:1.5.1")
    implementation("androidx.camera:camera-view:1.5.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // Room + SQLCipher
    //
    // Room bumped from 2.6.1 to 2.8.4 -- NOT a routine version bump, this was
    // required to fix a real build failure: ':android:app:kspDebugKotlin
    // FAILED' with 'java.lang.IllegalStateException: unexpected jvm
    // signature V'. Confirmed via multiple independent real-world bug
    // reports (github.com/google/ksp/issues/2957, and a real project's
    // actual fix commit) that this is a KNOWN KSP2 bug when processing Room
    // DAO methods that are `suspend fun` with an implicit Unit return type
    // -- which this project's DAOs use extensively (insert/update/delete
    // methods across ChannelDao, ContactDao, MessageDao,
    // VoiceBroadcastLogDao). The confirmed fix across multiple real reports
    // is upgrading Room itself, not further adjusting KSP/Kotlin versions.
    // Room requires room-runtime, room-ktx, and room-compiler to all be the
    // EXACT SAME version (Room's own stated requirement) -- all three
    // updated together here.
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    // Migrated from kapt to ksp -- see plugins{} block comment above.
    ksp("androidx.room:room-compiler:2.8.4")
    // Migrated from net.zetetic:android-database-sqlcipher (deprecated,
    // superseded in 2022, does NOT support 16KB memory page sizes) to
    // net.zetetic:sqlcipher-android -- Google Play has required 16KB page
    // size support for apps targeting Android 15+ (this app's targetSdk)
    // since November 1, 2025. Confirmed via Zetetic's official migration
    // guide (zetetic.net/sqlcipher/sqlcipher-for-android-migration) that for
    // this app's specific usage (Room integration via a single-argument
    // byte[] password factory constructor, see AppDatabase.kt) this is a
    // drop-in swap: net.sqlcipher.database.SupportFactory ->
    // net.zetetic.database.sqlcipher.SupportOpenHelperFactory, both taking
    // the same (byte[] password) constructor -- confirmed directly against
    // the new artifact's source. The androidx.sqlite version bump below is
    // required by the new artifact per the same migration guide.
    implementation("net.zetetic:sqlcipher-android:4.17.0")
    implementation("androidx.sqlite:sqlite:2.6.2")

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
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// kapt { correctErrorTypes = true } removed -- no longer meaningful now that
// Room's annotation processing runs via KSP instead of kapt (see plugins{}
// block comment above). KSP resolves symbols directly from Kotlin source
// rather than generating Java stubs, so this kapt-specific workaround has no
// KSP equivalent -- confirmed via Google's own official kapt-to-KSP
// migration guide (developer.android.com/build/migrate-to-ksp), which lists
// this exact block as something to simply delete, not replace.