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
}

// The previous-version client (msc.previous-protocol) that Bots loads in its own class loader; the core ships it in
// bot/<version>/, the tests load it from the staged copy.
tasks.test { previousProtocolClient.passTo(this) }
