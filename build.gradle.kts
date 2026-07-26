// Top-level build file for Rezvan Mesh
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.3.0" apply false
    // Required from Kotlin 2.0+: the Compose Compiler moved out of AGP and
    // into the Kotlin repository, shipping in lockstep with the Kotlin
    // version itself. The old composeOptions { kotlinCompilerExtensionVersion }
    // mechanism in app/build.gradle.kts is incompatible with Kotlin 2.0+ and
    // has been removed there in favor of this plugin. See:
    // https://android-developers.googleblog.com/2024/04/jetpack-compose-compiler-moving-to-kotlin-repository.html
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0" apply false
    // Replaces kapt for Room's annotation processing (see app/build.gradle.kts).
    // kapt is old Java-stub-based annotation processing tooling with known,
    // real compatibility problems on Kotlin 2.x (metadata-reading failures,
    // K2 compiler incompatibilities) -- this is what caused
    // ':android:app:kaptDebugKotlin FAILED' after the Kotlin 2.3.0 bump.
    // Google's own Room docs explicitly recommend KSP over kapt for Kotlin
    // 2.0+: "Room now targets Kotlin language 2.0... Support for KSP2 is
    // also added and is recommended when using Room with Kotlin 2.0 or
    // higher." Version confirmed directly against google/ksp's GitHub
    // releases page (not guessed): KSP's versioning dropped the older
    // <kotlin-version>-<ksp-revision> suffix scheme after 2.3.0 and now
    // tracks Kotlin's version numbering directly -- 2.3.9 is the current
    // real release as of this check, and 2.3.7's own changelog confirms
    // "Bumped Kotlin target language version to 2.3".
    id("com.google.devtools.ksp") version "2.3.9" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    // kotlinOptions {} was deprecated in Kotlin 2.0 and REMOVED entirely in
    // Kotlin 2.2 -- this project targets Kotlin 2.3.0, so the old block
    // would be a hard build-script compile error, not a deprecation
    // warning. Migrated to compilerOptions {} per Kotlin's own migration
    // guide (kotlinlang.org/docs/gradle-compiler-options.html), including
    // the typed JvmTarget enum (a plain string is no longer accepted here).
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
        )
    }
}
