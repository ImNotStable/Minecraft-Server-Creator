package io.github.imnotstable.msc.core.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildLedgerTest {
    private static final String PASS = "score 80\nno number below the bar";
    private static final String FAIL = "score 40\nfix first: 1) bare base: trim it; 2) empty rooms: furnish";

    @TempDir
    Path dir;

    private BuildLedger ledger() {
        return new BuildLedger(() -> dir.resolve(BuildLedger.FILE),
                Clock.fixed(Instant.parse("2026-11-25T10:00:00Z"), ZoneOffset.UTC));
    }

    private static Region box(int x1, int y1, int z1, int x2, int y2, int z2) {
        return Region.of("main", x1, y1, z1, x2, y2, z2);
    }

    private static BlockGrid grid(Region r, String... blocks) {
        int[] cells = new int[(int) r.volume()];
        Arrays.fill(cells, 0);
        return new BlockGrid(r, List.of(blocks), cells);
    }

    @Test
    void aReviewAddsItsBuildAndAReviewOfTheSameBuildUpdatesIt() {
        BuildLedger ledger = ledger();
        Region smithy = box(0, 0, 0, 9, 8, 9);
        assertThat(ledger.reviewed(smithy, FAIL, grid(smithy, "minecraft:stone"), "Smithy", "the forge by the gate"))
                .isEqualTo("\nledger: b1 Smithy");
        // A wider box under the same name, then an unnamed one over most of it, are the same build.
        Region wider = box(-1, 0, -1, 10, 8, 10);
        assertThat(ledger.reviewed(wider, PASS, grid(wider, "minecraft:stone"), "smithy", null))
                .isEqualTo("\nledger: b1 Smithy");
        assertThat(ledger.reviewed(smithy, PASS, grid(smithy, "minecraft:stone"), null, null))
                .isEqualTo("\nledger: b1 Smithy");
        Region well = box(30, 0, 30, 33, 3, 33);
        assertThat(ledger.reviewed(well, PASS, grid(well, "minecraft:stone"), null, null))
                .isEqualTo("\nledger: b2 (name= on the next review names it)");
        assertThat(ledger.list()).isEqualTo("""
                2 builds:
                - b1 Smithy (passed, reviewed 2026-11-25): the forge by the gate, main 0,0,0 9,8,9
                - b2 (passed, reviewed 2026-11-25), main 30,0,30 33,3,33""");
    }

    @Test
    void statusFollowsTheLastReviewAndEditsAfterAPass() {
        BuildLedger ledger = ledger();
        Region house = box(0, 0, 0, 5, 5, 5);
        ledger.reviewed(house, FAIL, grid(house, "minecraft:stone"), "house", null);
        assertThat(ledger.at(box(2, 2, 2, 2, 2, 2))).isEqualTo("""
                builds here:
                - b1 house (failing: bare base, empty rooms, reviewed 2026-11-25)""");
        ledger.reviewed(house, PASS, grid(house, "minecraft:stone"), "house", null);
        ledger.edited(box(5, 0, 5, 7, 1, 7));
        assertThat(ledger.at(house)).contains("b1 house (edited since review");
        assertThat(ledger.at(box(20, 0, 20, 21, 1, 21))).isEmpty();
        assertThat(ledger.at(Region.of("msc:other", 0, 0, 0, 5, 5, 5))).isEmpty();
    }

    @Test
    void aLookupListsAFewBuildsThenPointsAtTheFullList() {
        BuildLedger ledger = ledger();
        for (int i = 0; i < BuildLedger.SHOWN + 2; i++) {
            Region r = box(i * 10, 0, 0, i * 10 + 5, 5, 5);
            ledger.reviewed(r, PASS, grid(r, "minecraft:stone"), "house " + i, null);
        }
        assertThat(ledger.at(box(0, 0, 0, 100, 5, 5)).lines()).hasSize(BuildLedger.SHOWN + 2)
                .last().isEqualTo("- 2 more (msc_status builds=true)");
    }

    @Test
    void aPlannedBuildShowsItsLineAndTheBlocksItLacks() {
        BuildLedger ledger = ledger();
        assertThat(ledger.plan("""
                A river hamlet on the east bank.
                - smithy: by the ford, 2 storeys, minecraft:spruce_planks with cobbled_deepslate, gable roof
                - inn: on the rise, L-shaped, stone_bricks, hip roof""")).endsWith("2 builds named (smithy, inn); "
                + "review each with world_summary review=true name=<its name>");
        Region smithy = box(0, 0, 0, 4, 4, 4);
        assertThat(ledger.reviewed(smithy, PASS, grid(smithy, "minecraft:spruce_planks", "minecraft:oak_log"),
                "Smithy", null)).isEqualTo("""

                ledger: b1 Smithy
                plan: by the ford, 2 storeys, minecraft:spruce_planks with cobbled_deepslate, gable roof
                plan names cobbled_deepslate; none here""");
        assertThat(ledger.list()).startsWith("plan:\nA river hamlet").endsWith("planned, never reviewed: inn");
        // A new plan keeps the builds.
        ledger.plan("smithy: by the ford");
        assertThat(ledger.list()).contains("b1 Smithy").doesNotContain("planned, never reviewed");
    }

    @Test
    void refusesAnEmptyOrHugePlan() {
        assertThatThrownBy(() -> ledger().plan(" ")).hasMessageStartingWith("plan is empty");
        assertThatThrownBy(() -> ledger().plan("x".repeat(BuildLedger.MAX_PLAN + 1))).hasMessageContaining("under");
    }

    @Test
    void anUnreadableLedgerIsNeverOverwritten() throws IOException {
        Path file = Files.writeString(dir.resolve(BuildLedger.FILE), "{not json");
        BuildLedger ledger = ledger();
        Region r = box(0, 0, 0, 1, 1, 1);
        assertThat(ledger.reviewed(r, PASS, grid(r, "minecraft:stone"), "a", null))
                .startsWith("\nledger: not updated, ").contains("could not be read");
        ledger.edited(r);
        assertThat(ledger.at(r)).isEmpty();
        assertThat(ledger.plan("a: b")).startsWith("plan not saved: ");
        assertThat(Files.readString(file)).isEqualTo("{not json");
    }

    @Test
    void noWorkspaceKeepsNothing() {
        Region r = box(0, 0, 0, 1, 1, 1);
        BuildLedger none = BuildLedger.none();
        assertThat(none.reviewed(r, PASS, grid(r, "minecraft:stone"), "a", null)).isEmpty();
        assertThat(none.at(r)).isEmpty();
        assertThat(none.list()).startsWith("build ledger is empty");
    }

    @Test
    void namesAndWhyLinesAreKeptShortAndOnOneLine() {
        BuildLedger ledger = ledger();
        Region r = box(0, 0, 0, 1, 1, 1);
        ledger.reviewed(r, PASS, grid(r, "minecraft:stone"), "n".repeat(60), "first\nsecond " + "w".repeat(300));
        String line = ledger.at(r).lines().toList().get(1);
        assertThat(line).startsWith("- b1 " + "n".repeat(BuildLedger.MAX_NAME) + " (")
                .contains(": first second w").doesNotContain("\n");
        assertThat(line.substring(line.indexOf("): ") + 3)).hasSize(BuildLedger.MAX_WHY);
    }
}
