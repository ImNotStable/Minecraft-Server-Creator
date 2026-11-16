package io.github.imnotstable.msc.process;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** The test oracle: how the 26.3 server reads {@code server.properties}, independent of the class under test. */
final class ServerReads {
    private ServerReads() {
    }

    /** Settings.loadFromFile of the 26.3 server: a strict UTF-8 reader, then ISO-8859-1. */
    static Properties load(byte[] bytes) throws IOException {
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
}
