// MCProtocolLib for the previous Minecraft version (Minestom lags Paper by one), which msc-bot's Bots loads in its
// own class loader from <root>/<version>/. One definition for msc-bot, msc-bench (staged for tests and runs) and
// msc-core (shipped in its distribution's bot/<version>/), so the three cannot drift.
import io.github.imnotstable.msc.buildlogic.PreviousProtocolClient

val libs = the<VersionCatalogsExtension>().named("libs")
val clientVersion = provider { libs.findVersion("mcprotocollib-previous").get().requiredVersion }

val previousProtocol = configurations.dependencyScope("previousProtocol")
val previousProtocolJars = configurations.resolvable("previousProtocolJars") { extendsFrom(previousProtocol.get()) }
dependencies {
    addProvider<MinimalExternalModuleDependency, ExternalModuleDependency>(
        previousProtocol.name, libs.findLibrary("mcprotocollib-previous").get()) { isTransitive = false }
}

val protocolsRoot = layout.buildDirectory.dir("protocols")
// Syncs the whole root, so the folder of a version that was bumped away is removed instead of being loaded as an
// extra speaker by Bots.
val stagePreviousProtocol = tasks.register<Sync>("stagePreviousProtocol") {
    into(protocolsRoot)
    into(clientVersion) { from(previousProtocolJars) }
}

extensions.add(PreviousProtocolClient::class.java, "previousProtocolClient",
    PreviousProtocolClient(files(previousProtocolJars), clientVersion, protocolsRoot, stagePreviousProtocol))
