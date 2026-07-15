plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(libs.koog.agents)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}

application {
    mainClass.set("dev.ki.ai.MainKt")
}
