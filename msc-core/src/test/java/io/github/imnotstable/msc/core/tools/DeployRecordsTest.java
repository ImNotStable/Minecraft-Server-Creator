package io.github.imnotstable.msc.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** #418: one unreadable line of the deploy record costs only that line, never the other records. */
class DeployRecordsTest {
    private static final char BS = '\\';
    /** A Windows path typed by hand with single backslashes: {@code \}{@code ui} is a broken unicode escape. */
    private static final String HAND_EDITED = "D" + BS + ":" + BS + "projects" + BS + "ui" + BS + "Other=plugins/other.jar";
    /** Readable, though not what the hand meant: single backslashes before other letters are dropped. */
    private static final String ODD_BUT_READABLE = "E" + BS + ":" + BS + "games" + BS + "Third=plugins/third.jar";

    private static String file(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    @Test
    void anUnreadableLineCostsOnlyItself() {
        DeployRecords r = DeployRecords.parse(file(DeployRecords.HEADER, "/ws/Lobby=plugins/oldname.jar", HAND_EDITED,
                ODD_BUT_READABLE, "/ws/Fourth=mods/fourth.jar"));
        assertThat(r.get("/ws/Lobby")).isEqualTo("plugins/oldname.jar");
        assertThat(r.get("/ws/Fourth")).isEqualTo("mods/fourth.jar");
        assertThat(r.get("E:gamesThird")).isEqualTo("plugins/third.jar");
        assertThat(r.unreadable()).containsExactly(3);
        assertThat(r.note(Path.of("deployed.properties")))
                .startsWith("deploy record: line 3 of deployed.properties could not be read")
                .contains("skipped; the other records were used");
    }

    @Test
    void aRewriteChangesOnlyThisProjectsLineAndKeepsTheUnreadableOneAsAComment() throws IOException {
        DeployRecords r = DeployRecords.parse(file(DeployRecords.HEADER, "/ws/Lobby=plugins/oldname.jar", HAND_EDITED,
                ODD_BUT_READABLE));
        String text = r.with("/ws/Lobby", "plugins/newname.jar");
        assertThat(text).isEqualTo(file(DeployRecords.HEADER, DeployRecords.KEPT + HAND_EDITED, ODD_BUT_READABLE,
                "/ws/Lobby=plugins/newname.jar"));
        // Any properties reader now loads it, and every record that was readable is still there.
        Properties p = new Properties();
        p.load(new StringReader(text));
        assertThat(p).containsEntry("/ws/Lobby", "plugins/newname.jar").containsEntry("E:gamesThird", "plugins/third.jar")
                .hasSize(2);
        DeployRecords again = DeployRecords.parse(text);
        assertThat(again.unreadable()).isEmpty();
        assertThat(again.note(Path.of("x"))).isNull();
        assertThat(again.with("/ws/Lobby", "plugins/newname.jar")).as("stable").isEqualTo(text);
    }

    @Test
    void escapesContinuationsAndDuplicatesReadAsPropertiesReadsThem() throws IOException {
        String text = file("C" + BS + ":" + BS + BS + "Users" + BS + BS + "me" + BS + BS + "Lobby=plugins/a.jar",
                "/ws/long=plugins/" + BS, "    b.jar", "/ws/dup=plugins/one.jar", "/ws/dup=plugins/two.jar",
                "! a comment " + BS, "/ws/x=" + BS + "u00e9.jar");
        Properties p = new Properties();
        p.load(new StringReader(text));
        DeployRecords r = DeployRecords.parse(text);
        for (String key : p.stringPropertyNames()) {
            assertThat(r.get(key)).as(key).isEqualTo(p.getProperty(key));
        }
        assertThat(r.get("C:\\Users\\me\\Lobby")).isEqualTo("plugins/a.jar");
        assertThat(r.unreadable()).isEmpty();
        String written = r.with("/ws/dup", "plugins/three.jar");
        Properties after = new Properties();
        after.load(new StringReader(written));
        assertThat(after).containsEntry("/ws/dup", "plugins/three.jar").containsEntry("/ws/long", "plugins/b.jar")
                .containsEntry("/ws/x", "\u00e9.jar").hasSize(4);
    }

    @Test
    void aNewFileGetsAHeaderAndEscapedEntry() {
        assertThat(DeployRecords.parse("").with("C:\\w\u00e9\\Lobby", "plugins/lobby.jar"))
                .isEqualTo(file(DeployRecords.HEADER, "C\\:\\\\w\\u00E9\\\\Lobby=plugins/lobby.jar"));
    }
}
