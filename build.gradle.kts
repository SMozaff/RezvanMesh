// Top-level build file for Rezvan Mesh
plugins {
    id("com.android.application") version "8.7.3" apply false
    // Stepped back from 2.3.0, through 2.2.21, to 2.2.20 -- matching
    // exactly the Kotlin version the confirmed-working KSP release below
    // (2.2.20-2.0.2) is built against. See the ksp plugin comment for the
    // full version-history reasoning.
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    // Required from Kotlin 2.0+: the Compose Compiler moved out of AGP and
    // into the Kotlin repository, shipping in lockstep with the Kotlin
    // version itself. The old composeOptions { kotlinCompilerExtensionVersion }
    // mechanism in app/build.gradle.kts is incompatible with Kotlin 2.0+ and
    // has been removed there in favor of this plugin. See:
    // https://android-developers.googleblog.com/2024/04/jetpack-compose-compiler-moving-to-kotlin-repository.html
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    // Replaces kapt for Room's annotation processing (see app/build.gradle.kts).
    // kapt is old Java-stub-based annotation processing tooling with known,
    // real compatibility problems on Kotlin 2.x (metadata-reading failures,
    // K2 compiler incompatibilities) -- this is what caused
    // ':android:app:kaptDebugKotlin FAILED' after the Kotlin bump.
    //
    // Version history/reasoning -- this took several attempts, documenting
    // in full so it isn't re-litigated:
    //   1st: guessed "2.3.0-2.0.4" -- doesn't exist (KSP dropped that exact
    //      suffix format around then).
    //   2nd: 2.3.9 -- hit ':android:app.addKspConfigurations(boolean)'
    //      NoSuchMethodError. ROOT CAUSE, since confirmed directly against
    //      KSP's own source (KspSubplugin.kt calls
    //      checkMinimumAgpVersion(componentsExtension.pluginVersion) on
    //      apply) and Kotlin's own official AGP-9-migration version matrix
    //      (github.com/Kotlin/kotlin-agent-skills, VERSION-MATRIX.md):
    //      "KSP version is no longer tied to the Kotlin compiler version
    //      since 2.3.0. AGP 9.0 and built-in Kotlin support added in
    //      2.3.1." -- i.e. KSP >= 2.3.1 assumes AGP 9.0's API surface,
    //      which addKspConfigurations is part of. Our AGP is 8.7.3.
    //   3rd: 2.2.21-2.0.5 -- SAME ERROR. Root cause of THIS failure: that
    //      exact version string most likely doesn't exist as a published
    //      artifact (Maven Central shows 2.2.21-RC-2.0.4 and
    //      2.2.21-RC2-2.0.4 as the real releases near that number, not a
    //      final "2.2.21-2.0.5") -- Gradle's plugin resolution failure mode
    //      for a nonexistent version can surface confusingly rather than as
    //      a clean "not found" in all cases, so this was not actually a
    //      repeat of the AGP-9.0-API problem, just an invalid version guess
    //      producing a similar-looking failure.
    //   4th, current: 2.2.20-2.0.2 -- confirmed to actually exist and build
    //      against a real project (github.com/google/ksp/issues/2614: a
    //      real user reports 2.2.20-2.0.2 "is able to build fine", with a
    //      regression only in the LATER 2.2.20-2.0.3 patch). Safely below
    //      the confirmed 2.3.1 AGP-9.0-API cutoff.
    id("com.google.devtools.ksp") version "2.2.20-2.0.2" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    // kotlinOptions {} was deprecated in Kotlin 2.0.0, and its deprecation
    // level was raised to a build ERROR starting Kotlin 2.2.0 (confirmed via
    // Kotlin's own official "What's new in 2.2.0" notes) -- this project
    // targets Kotlin 2.2.21, so the old block would fail the build, not
    // just warn. Migrated to compilerOptions {} per Kotlin's own migration
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
