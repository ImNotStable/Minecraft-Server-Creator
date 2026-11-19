package io.github.imnotstable.msc.core.upstream;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Every upstream answer here is canned: no test reaches the network. */
class UpstreamCheckTest {
    static final String CATALOG = """
            # comment
            [versions]
            paper = "26.3.build.+"   # Paper's recommended form
            minecraft = "26.3"
            fabric-loader = "0.20.1"
            fabric-api = "0.166.0+26.3"
            neoforge = "26.3.0.102"
            forge = "26.3-66.1.5"
            quilt-loader = "0.30.2"
            minestom = "2026.10.07-26.2"
            velocity = "4.3.1"

            [libraries]
            paper-api = { module = "io.papermc.paper:paper-api", version.ref = "paper" }
            """;

    /** What each upstream answers today, with one newer stable version for most components. */
    static Map<String, String> answers() {
        Map<String, String> a = new HashMap<>();
        a.put(UpstreamCheck.MOJANG, """
                {"latest": {"release": "26.4", "snapshot": "26.5-snapshot-1"}, "versions": []}""");
        a.put(UpstreamCheck.PAPER, """
                {"versions": {"26.5": ["26.5-rc-1"], "26.4": ["26.4", "26.4-rc-2"], "26.3": ["26.3", "26.3-rc-3"],
                 "1.21": ["1.21.11"]}}""");
        a.put(UpstreamCheck.PAPER + "/versions/26.4/builds/latest", """
                {"id": 12, "channel": "STABLE"}""");
        a.put(UpstreamCheck.FABRIC_LOADER, """
                [{"version": "0.21.0", "stable": false}, {"version": "0.20.1", "stable": true},
                 {"version": "0.20.0", "stable": false}]""");
        a.put(UpstreamCheck.FABRIC_API, maven("0.166.0+26.3", "0.166.2+26.3", "0.170.0+26.4", "0.171.0+26.5"));
        a.put(UpstreamCheck.NEOFORGE, maven("26.3.0.102", "26.3.0.103", "21.1.266", "26.4.0.1-beta"));
        a.put(UpstreamCheck.FORGE, """
                {"promos": {"1.21.11-recommended": "61.2.4", "26.3-latest": "66.1.9",
                 "26.3-recommended": "66.1.8", "26.4-latest": "67.0.1"}}""");
        a.put(UpstreamCheck.QUILT_LOADER, """
                [{"version": "0.31.0-beta.6"}, {"version": "0.30.2"}, {"version": "0.30.1"}]""");
        a.put(UpstreamCheck.MINESTOM, maven("2026.10.07-26.2", "2025.07.10b-1.21.7"));
        a.put(UpstreamCheck.VELOCITY, """
                {"versions": {"4.4.0": ["4.4.0-SNAPSHOT"], "4.3.0": ["4.3.2", "4.3.1"]}}""");
        a.put(UpstreamCheck.VELOCITY + "/versions/4.3.2/builds/latest", """
                {"id": 40, "channel": "STABLE"}""");
        return a;
    }

    static String maven(String... versions) {
        StringBuilder xml = new StringBuilder("<metadata><versioning><versions>");
        for (String v : versions) {
            xml.append("\n  <version>").append(v).append("</version>");
        }
        return xml.append("</versions></versioning></metadata>").toString();
    }

    static UpstreamCheck.Fetcher fetcher(Map<String, String> answers) {
        return url -> {
            String body = answers.get(url);
            if (body == null) {
                throw new IOException("HTTP 404 from test");
            }
            return body;
        };
    }

    private static List<String> report(Map<String, String> answers, String coreMinecraft) {
        var components = UpstreamCheck.components(UpstreamCheck.catalogVersions(CATALOG), coreMinecraft);
        return UpstreamCheck.report(UpstreamCheck.check(components, fetcher(answers), 5));
    }

    @Test
    void readsOnlyTheVersionsTable() {
        assertThat(UpstreamCheck.catalogVersions(CATALOG))
                .containsEntry("paper", "26.3.build.+").containsEntry("minestom", "2026.10.07-26.2")
                .doesNotContainKey("paper-api").hasSize(9);
    }

    @Test
    void aPinnedPaperBuildIsCheckedAsItsMinecraftVersion() {
        var pinned = UpstreamCheck.components(Map.of("paper", "26.3.build.252-stable"), "26.3");
        assertThat(pinned).filteredOn(c -> c.name().equals("paper"))
                .extracting(UpstreamCheck.Component::pinned).containsExactly("26.3");
    }

    @Test
    void listsEachPinBesideTheNewestStableUpstream() {
        assertThat(report(answers(), "26.3")).containsExactly(
                "component         pinned               newest stable",
                "minecraft         26.3                 26.4                 NEWER",
                "paper             26.3                 26.4                 NEWER",
                "fabric-loader     0.20.1               0.20.1               up to date",
                "fabric-api        0.166.0+26.3         0.166.2+26.3         NEWER",
                "neoforge          26.3.0.102           26.3.0.103           NEWER",
                "forge             26.3-66.1.5          26.3-66.1.8          NEWER",
                "quilt-loader      0.30.2               0.30.2               up to date",
                "minestom          2026.10.07-26.2      2026.10.07-26.2      up to date",
                "velocity          4.3.1                4.3.2                NEWER",
                "6 newer, 0 could not check; pins live in gradle/libs.versions.toml and CoreConfig.DEFAULT_MINECRAFT");
    }

    @Test
    void aPaperVersionWithoutAStableBuildIsNotNewest() {
        Map<String, String> a = answers();
        a.put(UpstreamCheck.PAPER + "/versions/26.4/builds/latest", """
                {"id": 3, "channel": "BETA"}""");
        assertThat(report(a, "26.3")).contains(
                "paper             26.3                 26.3                 up to date");
    }

    @Test
    void aCoreDefaultApartFromTheCatalogGetsItsOwnLine() {
        assertThat(report(answers(), "26.2")).contains(
                "msc-core default  26.2                 26.4                 NEWER");
    }

    @Test
    void offlineEveryLineSaysCouldNotCheck() {
        List<String> lines = report(Map.of(), "26.3");
        assertThat(lines).hasSize(11);
        assertThat(lines.subList(1, 10)).allMatch(l -> l.endsWith("could not check (HTTP 404 from test)"));
        assertThat(lines.getLast()).isEqualTo("0 newer, 9 could not check");
    }

    @Test
    void anOddAnswerFailsOnlyItsOwnLine() {
        Map<String, String> a = answers();
        a.put(UpstreamCheck.MOJANG, "[1, 2]");
        a.put(UpstreamCheck.FORGE, "not json {");
        a.put(UpstreamCheck.QUILT_LOADER, "[]");
        assertThat(report(a, "26.3")).contains(
                "minecraft         26.3                 -                    could not check (unexpected answer)",
                "forge             26.3-66.1.5          -                    could not check (unreadable answer)",
                "quilt-loader      0.30.2               -                    could not check (no stable version found)",
                "fabric-loader     0.20.1               0.20.1               up to date");
    }

    @Test
    void aSlowUpstreamTimesOut() {
        UpstreamCheck.Component slow = new UpstreamCheck.Component("slow", "1", f -> {
            Thread.sleep(10_000);
            return "2";
        });
        assertThat(UpstreamCheck.check(List.of(slow), fetcher(Map.of()), 1))
                .singleElement().extracting(UpstreamCheck.Result::failure).isEqualTo("timed out");
    }
}
