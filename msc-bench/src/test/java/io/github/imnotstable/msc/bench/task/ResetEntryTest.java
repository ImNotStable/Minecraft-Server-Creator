package io.github.imnotstable.msc.bench.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * #502: a reset path whose real path is another entry is a link of some kind (a Windows directory junction, which Java
 * reports as a plain directory), so the reset deletes the entry and never walks into what it points to.
 */
class ResetEntryTest {

    @ParameterizedTest(name = "{0} -> {1}: {2}")
    @CsvSource(delimiter = '|', value = {
            "/w/server/cache | /w/server/cache | true",
            "/w/server/cache | /w/server/Cache | true",
            "/w/server/cache | /w/keep         | false",
            "/w/server/cache | /outside/cache  | false",
            "/w/server/cache | /w/server/other | false",
            "/w/server/cache | /               | false"})
    void onlyTheEntryItselfIsTheSameEntry(String expected, String real, boolean same) {
        assertThat(Reset.sameEntry(Path.of(expected), Path.of(real))).isEqualTo(same);
    }
}
