package io.github.imnotstable.msc.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ServerAddressTest {
    @TempDir
    Path dir;

    /**
     * Each row: the file (lines split by {@code ;}, MISSING for no file), the game address, the RCON address ("none"
     * for no port to dial) and what the 26.3 server does with that file.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "MISSING|127.0.0.1:25565|127.0.0.1:25575|writes a file of defaults and binds every address",
            "motd=x|127.0.0.1:25565|127.0.0.1:25575|a missing key is its default",
            "server-ip=|127.0.0.1:25565|127.0.0.1:25575|binds every address",
            "server-ip=0.0.0.0|127.0.0.1:25565|127.0.0.1:25575|binds every IPv4 address",
            "server-ip=0|127.0.0.1:25565|127.0.0.1:25575|InetAddress.getByName reads 0 as 0.0.0.0",
            "server-ip=::|::1:25565|::1:25575|binds every address",
            "server-ip=::0|::1:25565|::1:25575|binds every address",
            "server-ip=[::]|::1:25565|::1:25575|getByName takes a bracketed IPv6 literal",
            "server-ip=0:0:0:0:0:0:0:0|::1:25565|::1:25575|binds every address",
            "server-ip=192.168.1.20|192.168.1.20:25565|192.168.1.20:25575|binds that address only",
            "server-ip=[::1]|::1:25565|::1:25575|binds IPv6 loopback only",
            "server-ip=example.invalid|example.invalid:25565|example.invalid:25575|resolves the name to bind",
            "server-port=25599;rcon.port=25600|127.0.0.1:25599|127.0.0.1:25600|listens on the ports given",
            "server-port=abc;rcon.port=x1|127.0.0.1:25565|127.0.0.1:25575|a value Integer.parseInt refuses is the default",
            "server-port=25599 ;rcon.port=25600 ;motd=x|127.0.0.1:25565|127.0.0.1:25575|a trailing space is not a number",
            "server-port=65535;rcon.port=1|127.0.0.1:65535|127.0.0.1:1|the ends of the range",
            "server-port=0;rcon.port=0|none|none|0 is a random game port; RCON logs invalid port, disabled",
            "server-port=65536;rcon.port=-1|none|none|cannot bind the game port; RCON disabled",
            "server-port=99999999999;rcon.port=70000|127.0.0.1:25565|none|too long to parse is the default"})
    void dialsWhereTheServerListens(String file, String game, String rcon, String server) throws Exception {
        Path props = dir.resolve(ServerPropertiesFile.NAME);
        if (!file.equals("MISSING")) {
            Files.writeString(props, file.replace(";", "\n") + "\n");
        }
        var read = ServerPropertiesFile.read(props);
        assertThat(text(ServerAddress.game(read))).as(server).isEqualTo(game);
        assertThat(text(ServerAddress.rcon(read))).as(server).isEqualTo(rcon);
    }

    private static String text(ServerAddress a) {
        return a == null ? "none" : a.host() + ":" + a.port();
    }
}
