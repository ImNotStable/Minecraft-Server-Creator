plugins {
    id("msc.java-conventions")
}

description = "Headless test player: joins the dev server as a real client, moves, jumps, clicks and reports."

dependencies {
    // Region and BlockGrid describe the world the bot moves through.
    api(project(":msc-protocol"))
    implementation(libs.mcprotocollib)
    implementation(libs.gson)
}

// MCProtocolLib for the previous Minecraft version, loaded by Bots in its own class loader. The core ships it in
// bot/<version>/; the tests load it from here.
val previousProtocol = configurations.dependencyScope("previousProtocol")
val previousProtocolJars = configurations.resolvable("previousProtocolJars") { extendsFrom(previousProtocol.get()) }
dependencies {
    previousProtocol(libs.mcprotocollib.previous) { isTransitive = false }
}

val stagePreviousProtocol = tasks.register<Sync>("stagePreviousProtocol") {
    from(previousProtocolJars)
    into(layout.buildDirectory.dir("protocols/${libs.versions.mcprotocollib.previous.get()}"))
}

tasks.test {
    dependsOn(stagePreviousProtocol)
    val protocols = layout.buildDirectory.dir("protocols")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dmsc.bot.protocols=" + protocols.get().asFile.absolutePath)
    })
}
