package io.github.imnotstable.msc.core.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.core.tool.ToolException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupsTest {
    @TempDir
    Path root;

    private Path server() throws IOException {
        return Files.createDirectories(root.resolve("run"));
    }

    private Backups backups(String time) {
        return new Backups(root.resolve(".msc/backups"), Clock.fixed(Instant.parse(time), ZoneOffset.UTC));
    }

    private static void file(Path p, String text) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    @Test
    void findsEveryPlatformsWorldFoldersAndNothingElse() throws IOException {
        Path run = server();
        // Paper: one folder per dimension, each with level.dat.
        file(run.resolve("world/level.dat"), "a");
        file(run.resolve("world_nether/DIM-1/region/r.0.0.mca"), "n");
        file(run.resolve("world_nether/level.dat"), "b");
        // Minestom's AnvilLoader: region files, no level.dat.
        file(run.resolve("arena/region/r.0.0.mca"), "m");
        file(run.resolve("plugins/Demo/region/x"), "not a world");
        file(run.resolve("logs/latest.log"), "log");
        file(run.resolve(".msc-restore/new/world/level.dat"), "stage");
        assertThat(WorldFolders.in(run)).containsExactly("arena", "world", "world_nether");
        assertThat(WorldFolders.in(root.resolve("missing"))).isEmpty();
    }

    @Test
    void saveCopiesWorldsWithoutTheSessionLockAndRestoreSwapsThemBack() throws Exception {
        Path run = server();
        file(run.resolve("world/level.dat"), "v1");
        file(run.resolve("world/session.lock"), "lock");
        file(run.resolve("world/dimensions/minecraft/the_nether/region/r.0.0.mca"), "nether v1");
        Backups b = backups("2026-11-14T10:00:00Z");
        Backups.Backup saved = b.save(run, "Big Castle!", null);
        assertThat(saved.name()).isEqualTo("20261114-100000-big-castle");
        assertThat(saved.worlds()).containsExactly("world");
        Path copy = root.resolve(".msc/backups/20261114-100000-big-castle/world");
        assertThat(copy.resolve("level.dat")).hasContent("v1");
        assertThat(copy.resolve("session.lock")).doesNotExist();
        assertThat(b.list()).extracting(Backups.Backup::name).containsExactly(saved.name());

        // A later change, and a dimension folder made after the backup.
        file(run.resolve("world/level.dat"), "v2");
        file(run.resolve("world/dimensions/minecraft/the_nether/region/r.0.0.mca"), "nether v2");
        file(run.resolve("world_the_end/level.dat"), "end");
        b.restore(b.find("castle"), run);
        assertThat(run.resolve("world/level.dat")).hasContent("v1");
        assertThat(run.resolve("world/dimensions/minecraft/the_nether/region/r.0.0.mca")).hasContent("nether v1");
        assertThat(run.resolve("world_the_end")).doesNotExist();
        assertThat(run.resolve(Backups.STAGE)).doesNotExist();
        assertThat(b.list()).hasSize(1);
    }

    @Test
    void refusesWithoutWorldsAndNamesKeptBackupsWhenNoneMatches() throws Exception {
        Path run = server();
        Backups b = backups("2026-11-14T10:00:00Z");
        assertThatThrownBy(() -> b.save(run, "x", null)).isInstanceOf(ToolException.class).hasMessageContaining("no world folder");
        assertThatThrownBy(() -> b.find("x")).hasMessage("no backup x; none taken yet");
        file(run.resolve("world/level.dat"), "v1");
        b.save(run, "one", null);
        assertThatThrownBy(() -> b.find("two")).hasMessage("no backup two; kept: 20261114-100000-one");
        assertThat(b.newestAgeSeconds()).isZero();
    }

    @Test
    void keepsTheNewestFiveAndTheOneAskedFor() throws Exception {
        Path run = server();
        file(run.resolve("world/level.dat"), "v");
        for (int i = 0; i < 6; i++) {
            backups("2026-11-14T10:0" + i + ":00Z").save(run, "b" + i, null);
        }
        Backups late = backups("2026-11-14T11:00:00Z");
        assertThat(late.list()).extracting(Backups.Backup::name).containsExactly("20261114-100500-b5",
                "20261114-100400-b4", "20261114-100300-b3", "20261114-100200-b2", "20261114-100100-b1");
        late.save(run, "rollback", "20261114-100100-b1");
        assertThat(late.list()).extracting(Backups.Backup::name).containsExactly("20261114-110000-rollback",
                "20261114-100500-b5", "20261114-100400-b4", "20261114-100300-b3", "20261114-100100-b1");
        // A crash mid-copy leaves a .part folder, which is never listed and goes with the next save.
        Files.createDirectories(root.resolve(".msc/backups/20261114-120000-auto.part/world"));
        assertThat(late.list()).hasSize(5);
        assertThat(late.newestAgeSeconds()).isZero();
        backups("2026-11-14T12:30:00Z").save(run, "again", null);
        assertThat(root.resolve(".msc/backups/20261114-120000-auto.part")).doesNotExist();
        assertThat(late.list()).extracting(Backups.Backup::name).first().isEqualTo("20261114-123000-again");
    }

    @Test
    void backupsFromOneSecondSortByWhenTakenAndAmbiguousNamesAreRefused() throws Exception {
        Path run = server();
        file(run.resolve("world/level.dat"), "v");
        Backups b = backups("2026-11-14T10:00:00Z");
        b.save(run, "zeta", null);
        b.save(run, "alpha", null);
        b.save(run, "alpha", null);
        // By name, zeta would be newest; it was taken first.
        assertThat(b.list()).extracting(Backups.Backup::name).containsExactly("20261114-100000-alpha-2",
                "20261114-100000-alpha", "20261114-100000-zeta");
        assertThat(b.find("zeta").name()).isEqualTo("20261114-100000-zeta");
        assertThat(b.find("20261114-100000-alpha").name()).isEqualTo("20261114-100000-alpha");
        assertThatThrownBy(() -> b.find("alpha")).hasMessage("backup alpha matches 2: 20261114-100000-alpha-2, "
                + "20261114-100000-alpha; give the whole name");
        assertThatThrownBy(() -> b.find(" ")).hasMessageStartingWith("backup takes list, save [label], or a backup name; kept: ");
        // A backup from before the order file sorts after every ordered one.
        Files.createDirectories(root.resolve(".msc/backups/20261114-110000-legacy/world"));
        assertThat(b.list()).extracting(Backups.Backup::name).last().isEqualTo("20261114-110000-legacy");
    }

    @Test
    void recoverPutsAnInterruptedRestoreBackAndFinishesAComplete() throws Exception {
        Path run = server();
        Path stage = run.resolve(Backups.STAGE);
        // Interrupted after "world" was moved in, before "world_nether" was: the originals go back.
        file(stage.resolve("old/world/level.dat"), "old world");
        file(stage.resolve("old/world_nether/level.dat"), "old nether");
        file(run.resolve("world/level.dat"), "restored world");
        file(stage.resolve("new/world_nether/level.dat"), "restored nether");
        Files.write(stage.resolve("worlds.txt"), List.of("world", "world_nether"));
        Backups.recover(run);
        assertThat(run.resolve("world/level.dat")).hasContent("old world");
        assertThat(run.resolve("world_nether/level.dat")).hasContent("old nether");
        assertThat(stage).doesNotExist();

        // Every folder moved in: only the old ones are left to delete.
        file(stage.resolve("old/world/level.dat"), "older");
        Files.write(stage.resolve("worlds.txt"), List.of("world"));
        Backups.recover(run);
        assertThat(run.resolve("world/level.dat")).hasContent("old world");
        assertThat(stage).doesNotExist();

        // Interrupted while staging: nothing was moved.
        file(stage.resolve("new/world/level.dat"), "half");
        Backups.recover(run);
        assertThat(run.resolve("world/level.dat")).hasContent("old world");
        assertThat(stage).doesNotExist();
    }
}
