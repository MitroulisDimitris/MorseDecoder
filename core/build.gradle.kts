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
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}
