package io.github.imnotstable.msc.core.upstream;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class VersionOrderTest {
    @Test
    void ordersTheShapesTheUpstreamsPublish() {
        assertThat(List.of("26.3", "26.2", "1.21.11", "26.3.1", "26.10").stream()
                .sorted(VersionOrder.NEWEST_LAST).toList())
                .containsExactly("1.21.11", "26.2", "26.3", "26.3.1", "26.10");
        assertThat(VersionOrder.compare("26.3.0.103", "26.3.0.102")).isPositive();
        assertThat(VersionOrder.compare("26.3.0.102", "21.1.266")).isPositive();
        assertThat(VersionOrder.compare("26.3-66.1.8", "26.3-66.1.5")).isPositive();
        assertThat(VersionOrder.compare("0.166.2+26.3", "0.166.0+26.3")).isPositive();
        assertThat(VersionOrder.compare("2026.11.02-26.3", "2026.10.07-26.2")).isPositive();
        assertThat(VersionOrder.compare("2025.07.10b-1.21.7", "2025.07.10-1.21.7")).isPositive();
        assertThat(VersionOrder.compare("26.3", "26.3")).isZero();
    }

    @Test
    void previewsAreNotStable() {
        assertThat(List.of("26.3", "26.3.0.103", "26.3-66.1.8", "2026.10.07-26.2", "0.30.2"))
                .allMatch(VersionOrder::isStable);
        assertThat(List.of("26.4-snapshot-8", "26.3-rc-3", "1.21.11-pre5", "0.31.0-beta.6", "24w14a", "1.0-alpha.2",
                "26.4.0.1-beta")).noneMatch(VersionOrder::isStable);
    }
}
