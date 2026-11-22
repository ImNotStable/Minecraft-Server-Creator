package io.github.imnotstable.msc.stdlib.db;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** {@link Store#jsonFiles}: each value in its own file, written to a temporary file first so a crash never leaves half. */
final class FileStore<V> implements Store<V> {
    private static final String EXT = ".json";
    private final Path dir;
    private final Codec<V> codec;

    FileStore(Path dir, Codec<V> codec) {
        this.dir = dir;
        this.codec = codec;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String fileName(String key) {
        String safe = key.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isEmpty() || safe.startsWith(".")) {
            safe = "_" + safe;
        }
        return safe + EXT;
    }

    @Override
    public synchronized Optional<V> get(String key) {
        Path f = dir.resolve(fileName(key));
        try {
            return Files.isRegularFile(f) ? Optional.of(codec.decode(Files.readString(f, StandardCharsets.UTF_8)))
                    : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void put(String key, V value) {
        Path f = dir.resolve(fileName(key));
        Path tmp = dir.resolve(fileName(key) + ".tmp");
        try {
            Files.writeString(tmp, codec.encode(value), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized boolean remove(String key) {
        try {
            return Files.deleteIfExists(dir.resolve(fileName(key)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized List<String> keys() {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(EXT))
                    .map(n -> n.substring(0, n.length() - EXT.length())).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized Map<String, V> all() {
        Map<String, V> out = new LinkedHashMap<>();
        keys().forEach(k -> get(k).ifPresent(v -> out.put(k, v)));
        return out;
    }
}
