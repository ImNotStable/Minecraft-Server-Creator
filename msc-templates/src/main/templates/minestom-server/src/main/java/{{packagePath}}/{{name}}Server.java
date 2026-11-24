package {{packageName}};

import io.github.imnotstable.msc.agent.minestom.MscAgent;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.LightingChunk;
import net.minestom.server.instance.block.Block;

public final class {{name}}Server {
    private {{name}}Server() {
    }

    public static void main(String[] args) {
        MinecraftServer server = MinecraftServer.init();

        InstanceContainer world = MinecraftServer.getInstanceManager().createInstanceContainer();
        world.setChunkSupplier(LightingChunk::new);
        world.setGenerator(unit -> unit.modifier().fillHeight(-64, -60, Block.GRASS_BLOCK));
        MinecraftServer.getGlobalEventHandler().addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(world);
            event.getPlayer().setRespawnPoint(new Pos(0.5, -60, 0.5));
        });

        // Minecraft Server Creator's agent: lets its tools see "world"; idle when msc-core did not start the server.
        MscAgent.name(world, "world");
        MscAgent.start();

        // A host (Docker, Pterodactyl) sets the address in SERVER_IP and SERVER_PORT; the dev server's otherwise.
        String ip = System.getenv().getOrDefault("SERVER_IP", "{{serverIp}}");
        int port = Integer.parseInt(System.getenv().getOrDefault("SERVER_PORT", "{{serverPort}}"));
        server.start(ip, port);
        System.out.println("Minestom listening on " + ip + ":" + port);
    }
}
