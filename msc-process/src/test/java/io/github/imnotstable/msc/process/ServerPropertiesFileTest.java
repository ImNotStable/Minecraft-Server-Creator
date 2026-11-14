package io.github.imnotstable.msc.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ServerPropertiesFileTest {
    @TempDir
    Path dir;

    /** Values a password, MOTD or URL may hold that a naive {@code key=value} write would change. */
    @ParameterizedTest
    @ValueSource(strings = {"pässwörd", "密码", "pw€", "😀x", "a\\b", "C:\\keys\\x", "\\", "trailing\\", " lead",
            "  two", "trail ", "\tTab", "#hash", "!bang", "a=b", "=x", "a:b", ":x", "http://host:8080/p?a=b#f",
            "\\u00e4 as text", "\\uZZZZ", "", " ", "line\nbreak", "cr\rlf", "nul\0", "x\\", "ß=é:#!"})
    void whatIsSetIsWhatTheServerReadsBack(String value) throws IOException {
        Path file = dir.resolve(ServerPropertiesFile.NAME);
        Files.writeString(file, "# Minecraft server properties\nmotd=A Server\n", StandardCharsets.UTF_8);
        ServerPropertiesFile.set(file, Map.of("rcon.password", value));
        assertThat(ServerPropertiesFile.read(file)).containsEntry("rcon.password", value).containsEntry("motd", "A Server");
        assertThat(serverReads(Files.readAllBytes(file))).containsEntry("rcon.password", value);
        // After its first start the server stores the file again, raw UTF-8 (#391): still the same value.
        byte[] rewritten = serverStores(serverReads(Files.readAllBytes(file)));
        assertThat(ServerPropertiesFile.read(write(rewritten))).containsEntry("rcon.password", value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"rcon.password", "a b", "k=v", "k:v", "#k", "!k", "kä", "k\\"})
    void keysAreEscapedToo(String key) throws IOException {
        Path file = dir.resolve(ServerPropertiesFile.NAME);
        ServerPropertiesFile.set(file, Map.of(key, "v"));
        ServerPropertiesFile.set(file, Map.of(key, "w"));
        assertThat(serverReads(Files.readAllBytes(file))).containsExactly(Map.entry(key, "w"));
    }

    @Test
    void readsRawUtf8AsTheServerWritesIt() throws IOException {
        Properties p = new Properties();
        p.setProperty("rcon.password", "pässwörd");
        Path file = write(serverStores(p));
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).contains("pässwörd");
        assertThat(ServerPropertiesFile.read(file)).containsEntry("rcon.password", "pässwörd");
    }

    @Test
    void fallsBackToIso88591WhenTheBytesAreNotUtf8() throws IOException {
        Path file = write("rcon.password=p\u00e4ss\nmotd=\\u00e9\n".getBytes(StandardCharsets.ISO_8859_1));
        assertThat(ServerPropertiesFile.read(file)).containsEntry("rcon.password", "päss").containsEntry("motd", "é");
        assertThat(serverReads(Files.readAllBytes(file))).containsEntry("rcon.password", "päss");
    }

    @Test
    void aMalformedEscapeIsAnIoException() throws IOException {
        Path file = write("rcon.password=\\uZZZZ\n".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> ServerPropertiesFile.read(file)).isInstanceOf(IOException.class);
        assertThat(ServerPropertiesFile.read(dir.resolve("missing"))).isEmpty();
    }

    @Test
    void setKeepsCommentsOtherLinesAndOrder() throws IOException {
        Path file = write(("#Minecraft server properties\r\n#Sat Nov 14\r\n\r\nmotd=Hello\r\ngamemode = survival\r\n"
                + "! old comment\r\nlong=one\\\r\n    two\r\ndifficulty:easy\r\nmax-players 20\r\ngamemode=creative\r\n")
                .getBytes(StandardCharsets.UTF_8));
        Map<String, String> values = new LinkedHashMap<>();
        values.put("gamemode", "creative");
        values.put("difficulty", "peaceful");
        values.put("long", "x");
        values.put("force-gamemode", "true");
        ServerPropertiesFile.set(file, values);
        assertThat(Files.readString(file)).isEqualTo("#Minecraft server properties\n#Sat Nov 14\n\nmotd=Hello\n"
                + "gamemode=creative\n! old comment\nlong=x\ndifficulty=peaceful\nmax-players 20\nforce-gamemode=true\n");
    }

    @Test
    void setCreatesAMissingFileAndRefusesBadKeys() throws IOException {
        Path file = dir.resolve("new/server.properties");
        ServerPropertiesFile.set(file, Map.of("enable-rcon", "true"));
        assertThat(Files.readString(file)).isEqualTo("enable-rcon=true\n");
        assertThatThrownBy(() -> ServerPropertiesFile.set(file, Map.of("", "x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readString(file)).isEqualTo("enable-rcon=true\n");
    }

    @Test
    void aCommentEndingInABackslashDoesNotSwallowTheNextLine() throws IOException {
        Path file = write("# a path C:\\\nrcon.port=1\n".getBytes(StandardCharsets.UTF_8));
        ServerPropertiesFile.set(file, Map.of("rcon.port", "2"));
        assertThat(Files.readString(file)).isEqualTo("# a path C:\\\nrcon.port=2\n");
    }

    private Path write(byte[] bytes) throws IOException {
        Path file = Files.createTempFile(dir, "server", ".properties");
        Files.write(file, bytes);
        return file;
    }

    /** Settings.loadFromFile of the 26.3 server: a strict UTF-8 reader, then ISO-8859-1. */
    private static Properties serverReads(byte[] bytes) throws IOException {
        Properties p = new Properties();
        try (Reader in = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))) {
            p.load(in);
        } catch (CharacterCodingException e) {
            p = new Properties();
            try (Reader in = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.ISO_8859_1)) {
                p.load(in);
            }
        }
        return p;
    }

    /** Settings.store of the 26.3 server: Properties.store over a UTF-8 writer, so non-ASCII stays raw. */
    private static byte[] serverStores(Properties p) throws IOException {
        Writer out = new StringWriter();
        p.store(out, "Minecraft server properties");
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
}
