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
    // Exercise the JdbcTemplate storage impls offline against an embedded SQLite DataSource;
    // the same portable DDL/SQL runs on a host's Postgres in production.
    testImplementation(libs.sqlite.jdbc)
    // Coordination (advisory locks, steering) is Postgres-only — the IT spins a real
    // Postgres via Testcontainers and self-skips when Docker/KI_IT is absent.
    testImplementation(libs.postgresql)
    testImplementation(libs.testcontainers.postgresql)
}

tasks.test {
    useJUnitPlatform()
}
