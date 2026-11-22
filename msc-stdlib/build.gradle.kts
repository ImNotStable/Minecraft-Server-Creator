plugins {
    id("msc.stdlib-conventions")
}

description = "Standard library for plugins and mods made with MSC: SQL, Mongo and Redis data, repositories, caches, " +
        "configs, messaging, events, formatting, geometry, scheduling, text."

dependencies {
    api(libs.hikaricp)
    // Every platform MSC builds for ships Gson, so it is not bundled.
    compileOnly(libs.gson)
    // Text: the platform (Paper, Velocity) or its adapter (Minestom) brings Adventure.
    compileOnly(libs.adventure.minimessage)
    compileOnly(libs.adventure.serializer.plain)
    compileOnly(libs.adventure.serializer.legacy)
    // Optional backends: a project that uses Mongo or Redis adds the driver (the guide says how).
    compileOnly(libs.mongodb.driver.sync)
    compileOnly(libs.jedis)

    testImplementation(libs.gson)
    testImplementation(libs.adventure.minimessage)
    testImplementation(libs.adventure.serializer.plain)
    testImplementation(libs.adventure.serializer.legacy)
    testImplementation(libs.mongodb.driver.sync)
    testImplementation(libs.jedis)
    testRuntimeOnly(libs.sqlite.jdbc)
    testRuntimeOnly(libs.h2)
    testRuntimeOnly(libs.postgresql)
    testRuntimeOnly(libs.mariadb.client)
}

// Live backend checks (MySQL, MariaDB, PostgreSQL, Mongo, Redis) run only when MSC_STDLIB_LIVE names their URLs; see
// LiveBackendsTest. CI has no database services, so they are skipped there.
tasks.test {
    System.getenv("MSC_STDLIB_LIVE")?.let { environment("MSC_STDLIB_LIVE", it) }
}
