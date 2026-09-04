import java.util.Properties

plugins {
    // No versions: AGP and the Kotlin plugin already sit on the build classpath
    // via build-logic's includeBuild, and re-declaring a version there is an
    // error. The baseline-profile plugin is declared `apply false` at the root
    // for the same reason.
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.baselineProfile)
}

/**
 * Generates the Baseline Profile shipped with the app.
 *
 * A profile is a list of classes and methods to compile ahead of time, so the
 * first run of a code path is not interpreted. It is captured by driving the
 * real installed app through a journey and recording what executed.
 *
 * Deliberately NOT a Kotlin Multiplatform module and deliberately not using the
 * `cards.*` convention plugins: this is a `com.android.test` module that ships
 * nothing, exists only at build time, and has no iOS counterpart.
 */
fun localProperty(key: String): String? =
    rootProject.file("local.properties")
        .takeIf { it.exists() }
        ?.let { file -> Properties().apply { file.inputStream().use(::load) } }
        ?.getProperty(key)

android {
    namespace = "com.dangerfield.cards.baselineprofile"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // 28 is the floor for profile capture; the app itself still ships to 24.
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Optional reserved-account credentials, forwarded to the generator as
        // instrumentation arguments. Read from local.properties or -P, never
        // committed: this repository is public. Absent is fine — the app then
        // makes a guest account, which profiles just as well.
        listOf("cards.benchmark.email", "cards.benchmark.password").forEach { key ->
            // Both spellings. `ORG_GRADLE_PROJECT_<name>` is how CI passes a
            // secret without putting it on the command line, and that mechanism
            // cannot express dots — so the underscore form is what a GitHub
            // Actions env var actually lands as.
            val underscored = key.replace('.', '_')
            val value = localProperty(key)
                ?: providers.gradleProperty(key).orNull
                ?: providers.gradleProperty(underscored).orNull
            value?.takeIf { it.isNotBlank() }
                ?.let { testInstrumentationRunnerArguments[key] = it }
        }
    }

    targetProjectPath = ":apps:compose"

    // An emulator the build starts and throws away, so generating a profile
    // needs no physical device and no device farm. This is the difference
    // between a profile and a frame-timing benchmark: a profile records *which*
    // code ran, which an emulator answers exactly as well as real hardware.
    testOptions.managedDevices.allDevices {
        create<com.android.build.api.dsl.ManagedVirtualDevice>("pixel6Api34") {
            device = "Pixel 6"
            apiLevel = 34
            systemImageSource = "aosp"
        }
    }
}

baselineProfile {
    // `-Pcards.benchmark.useConnectedDevice=true` runs against an emulator or
    // phone you started yourself, so you can WATCH the journey instead of
    // inferring it from a failure string. The managed device is headless, which
    // is right for CI and miserable for debugging.
    //
    // Off by default: a profile captured on whatever happened to be plugged in
    // is not reproducible, and that is not a mistake worth making silently.
    val useConnected =
        providers.gradleProperty("cards.benchmark.useConnectedDevice").orNull == "true"

    if (!useConnected) managedDevices += "pixel6Api34"
    useConnectedDevices = useConnected
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.testExt.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macroJunit4)
}
