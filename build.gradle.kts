plugins {
    // Applied in subprojects; declared here (apply false) so the version is shared.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

subprojects {
    repositories {
        mavenCentral()
    }
}
