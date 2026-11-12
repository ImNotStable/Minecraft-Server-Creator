package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.core.Version;

/** Guards the security floor on the MCP SDK's transitive Jackson 3, which parses every MCP message. */
class DependencyFloorTest {

    /** First 3.1.x release without the advisories that affect 3.1.4 (for example GHSA-7hhh-6rmp-j9qf). */
    private static final int[] FLOOR = {3, 1, 7};

    @Test
    void jacksonCoreIsAtOrAboveTheFloor() {
        assertThat(atOrAboveFloor(tools.jackson.core.json.PackageVersion.VERSION)).isTrue();
    }

    @Test
    void jacksonDatabindIsAtOrAboveTheFloor() {
        assertThat(atOrAboveFloor(tools.jackson.databind.cfg.PackageVersion.VERSION)).isTrue();
    }

    // Version.compareTo also compares group and artifact ids, so compare the numbers only.
    private static boolean atOrAboveFloor(Version v) {
        int[] actual = {v.getMajorVersion(), v.getMinorVersion(), v.getPatchLevel()};
        return java.util.Arrays.compare(actual, FLOOR) >= 0;
    }
}
