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
