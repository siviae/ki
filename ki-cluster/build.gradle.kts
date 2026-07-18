plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // The SessionStore SPI (the agent owns the contract).
    api(project(":ki-agent"))
    implementation(libs.spring.jdbc)
    implementation(libs.spring.context)

    testImplementation(kotlin("test"))
    // Exercise the JdbcTemplate impl offline against an embedded SQLite DataSource;
    // the same portable DDL/SQL runs on a host's Postgres in production.
    testImplementation(libs.sqlite.jdbc)
}

tasks.test {
    useJUnitPlatform()
}
