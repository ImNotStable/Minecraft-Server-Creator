plugins {
    id("msc.java-conventions")
    id("msc.previous-protocol")
}

description = "Headless test player: joins the dev server as a real client, moves, jumps, clicks and reports."

dependencies {
    // Region and BlockGrid describe the world the bot moves through.
    api(project(":msc-protocol"))
    implementation(libs.mcprotocollib)
    // Raises the netty-all 4.2.1.Final that MCProtocolLib pulls; reaches msc-core and msc-bench through msc-bot.
    implementation(platform(libs.netty.bom))
    implementation(libs.gson)
    // HttpStall: the one stall rule for HTTP reads (the pack download), shared with msc-core and msc-bench.
    implementation(project(":msc-process"))
    testImplementation(testFixtures(project(":msc-process")))
}

// Minestom's data artifact (a dependency of the catalog's minestom, not a new library) carries vanilla's collision
// shape for every block state; BlockShapesDriftTest checks BlockShapes against it. Only that jar reaches the tests.
val minestomData = configurations.dependencyScope("minestomData")
val minestomDataPath = configurations.resolvable("minestomDataPath") { extendsFrom(minestomData.get()) }
dependencies { minestomData(libs.minestom) }

// The previous-version client (msc.previous-protocol) that Bots loads in its own class loader; the core ships it in
// bot/<version>/, the tests load it from the staged copy.
tasks.test {
    previousProtocolClient.passTo(this)
    classpath += minestomDataPath.get().filter { it.name.startsWith("data-") }
    systemProperty("msc.minecraft", libs.versions.minecraft.get())
}
