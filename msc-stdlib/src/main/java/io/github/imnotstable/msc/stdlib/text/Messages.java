package io.github.imnotstable.msc.stdlib.text;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Player-facing text by key and language, out of the code so owners can reword and translate it. Files are
 * {@code messages_<lang>.properties} (UTF-8) holding MiniMessage: {@code shop.bought=<green>You bought <item>!}.
 * Missing keys fall back to the default language, then to the key itself.
 *
 * <pre>{@code
 * Messages msg = Messages.load(dataDir.resolve("lang"), "en", Map.of("shop.bought", "<green>You bought <item>!"));
 * player.sendMessage(Text.mm(msg.get(player.locale(), "shop.bought"), "item", "a sword"));
 * }</pre>
 */
public final class Messages {
    private final String fallback;
    private final Map<String, Map<String, String>> byLanguage;

    private Messages(String fallback, Map<String, Map<String, String>> byLanguage) {
        this.fallback = fallback;
        this.byLanguage = byLanguage;
    }

    /** Only {@code defaults}, in the fallback language: for tests and plugins without files yet. */
    public static Messages of(String language, Map<String, String> defaults) {
        Map<String, Map<String, String>> m = new HashMap<>();
        m.put(language, new HashMap<>(defaults));
        return new Messages(language, m);
    }

    /**
     * Reads every {@code messages_*.properties} in {@code dir}. The fallback language's file is created with
     * {@code defaults} when missing, and keys added to {@code defaults} later are appended to it.
     */
    public static Messages load(Path dir, String fallback, Map<String, String> defaults) {
        try {
            Files.createDirectories(dir);
            Path main = dir.resolve("messages_" + fallback + ".properties");
            Properties existing = read(main);
            StringBuilder append = new StringBuilder();
            defaults.forEach((k, v) -> {
                if (!existing.containsKey(k)) {
                    append.append(k).append('=').append(v.replace("\\", "\\\\").replace("\n", "\\n")).append('\n');
                }
            });
            if (!append.isEmpty()) {
                Files.writeString(main, append.toString(), StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            }
            Map<String, Map<String, String>> all = new HashMap<>();
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.toList()) {
                    String n = f.getFileName().toString();
                    if (n.startsWith("messages_") && n.endsWith(".properties")) {
                        String lang = n.substring("messages_".length(), n.length() - ".properties".length())
                                .toLowerCase(Locale.ROOT);
                        Map<String, String> map = new HashMap<>();
                        read(f).forEach((k, v) -> map.put(k.toString(), v.toString()));
                        all.put(lang, map);
                    }
                }
            }
            all.computeIfAbsent(fallback, l -> new HashMap<>());
            defaults.forEach((k, v) -> all.get(fallback).putIfAbsent(k, v));
            return new Messages(fallback, all);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Properties read(Path f) throws IOException {
        Properties p = new Properties();
        if (Files.isRegularFile(f)) {
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                p.load(r);
            }
        }
        return p;
    }

    /** The text in the fallback language. */
    public String get(String key) {
        return get(fallback, key);
    }

    /** The text for {@code locale} ({@code en_US} tries {@code en_us}, then {@code en}, then the fallback). */
    public String get(Locale locale, String key) {
        return get(locale.toString(), key);
    }

    public String get(String language, String key) {
        String lang = language.toLowerCase(Locale.ROOT).replace('-', '_');
        for (String l : new String[]{lang, lang.contains("_") ? lang.substring(0, lang.indexOf('_')) : lang, fallback}) {
            Map<String, String> m = byLanguage.get(l);
            if (m != null && m.containsKey(key)) {
                return m.get(key);
            }
        }
        return key;
    }

    /** Languages that have a file. */
    public java.util.Set<String> languages() {
        return byLanguage.keySet();
    }
}
