// :core — platform-independent decoder. No Android dependencies, ever.
// The whole DSP chain and the §6 acceptance harness live here so they run as
// plain JUnit on the desktop (NFR-3 speed, NFR-5 determinism).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Benches and diagnostics are switched on by environment variables, which Gradle does
    // not treat as task inputs: with the build cache on, `REAL_DIR=... gradlew :core:test`
    // was served a cached run in which the bench had been skipped, and printed nothing.
    // Any of them set -> always execute, never cache (their inputs are files outside the
    // build, e.g. fresh phone captures, so a cached result would be stale anyway).
    val benchEnv = listOf(
        "BENCH", "BENCH_CSV", "DIAG", "DIAG_WAV", "REAL_DIR", "REAL_CASES", "REAL_CHUNK",
        "REAL_REPORT", "REAL_WAV", "PHONE_CLIPS_OUT", "FIXTURE_WAV_DIR",
        "DEMO_WAV_OUT", "DEMO_WAV_TEXT", "DEMO_WAV_WPM",
    ).filter { System.getenv(it) != null }
    if (benchEnv.isNotEmpty()) {
        outputs.upToDateWhen { false }
        outputs.cacheIf("bench env set: $benchEnv") { false }
    }
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}
