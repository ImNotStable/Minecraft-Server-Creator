plugins {
    id("msc.java-conventions")
}

description = "Headless test player: joins the dev server as a real client, moves, jumps, clicks and reports."

dependencies {
    // Region and BlockGrid describe the world the bot moves through.
    api(project(":msc-protocol"))
    implementation(libs.mcprotocollib)
}
