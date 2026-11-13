package io.github.imnotstable.msc.templates;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import javax.lang.model.SourceVersion;

/**
 * The values a template is rendered with. Build with {@link #of(String)}, which derives every
 * other value from the project name, then override with the {@code withX} methods.
 *
 * @param name         plugin name, PascalCase letters and digits, for example {@code SpawnLobby}
 * @param projectId    Gradle project and jar name, kebab-case, 2-64 characters (it is also the mod id, and
 *                     Fabric allows no other length), for example {@code spawn-lobby}
 * @param packageName  Java package with no keyword segments and not under {@code java}, for example {@code com.example.spawnlobby}
 * @param mainClass    plugin main class simple name, for example {@code SpawnLobbyPlugin}
 * @param version      project version
 * @param description  one-line description
 * @param author       author shown in plugin.yml
 * @param serverIp     dev server bind address
 * @param serverPort   dev server port
 * @param rconPort     RCON port
 * @param rconPassword RCON password, random by default
 * @param levelType    world type, {@code minecraft:flat} by default
 * @param acceptEula   whether the user has accepted the Minecraft EULA; {@code false} by default.
 *                     Only set it when the user said so: it writes {@code eula=true}.
 */
public record TemplateVariables(
        String name,
        String projectId,
        String packageName,
        String mainClass,
        String version,
        String description,
        String author,
        String serverIp,
        int serverPort,
        int rconPort,
        String rconPassword,
        String levelType,
        boolean acceptEula) {

    private static final Pattern NAME = Pattern.compile("[A-Z][A-Za-z0-9]*");
    private static final Pattern PROJECT_ID = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");
    private static final Pattern PACKAGE = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*");
    private static final Pattern CLASS = Pattern.compile("[A-Z][A-Za-z0-9]*");
    private static final Pattern WORD = Pattern.compile("[A-Z]?[a-z0-9]+|[A-Z]+(?![a-z0-9])");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]*");
    private static final String FLAT_LAYERS = "{\"biome\":\"minecraft:plains\",\"layers\":["
            + "{\"block\":\"minecraft:bedrock\",\"height\":1},{\"block\":\"minecraft:dirt\",\"height\":2},"
            + "{\"block\":\"minecraft:grass_block\",\"height\":1}]}";
    private static final Pattern NON_SPACING_MARKS = Pattern.compile("\\p{Mn}+");
    private static final Pattern NON_ASCII_WORD_CHAR = Pattern.compile("[\\p{L}\\p{N}&&[^\\p{ASCII}]]");
    private static final SecureRandom RANDOM = new SecureRandom();

    public TemplateVariables {
        check(name, NAME, "name");
        check(projectId, PROJECT_ID, "projectId");
        if (projectId.length() < 2 || projectId.length() > 64) {
            throw new IllegalArgumentException("projectId must be 2-64 characters (the mod id rule): " + projectId);
        }
        check(packageName, PACKAGE, "packageName");
        for (String segment : packageName.split("\\.")) {
            if (SourceVersion.isKeyword(segment)) {
                throw new IllegalArgumentException(
                        "packageName segment '" + segment + "' is a Java keyword or literal: " + packageName);
            }
        }
        if (packageName.equals("java") || packageName.startsWith("java.")) {
            throw new IllegalArgumentException("packageName must not be java or below it, which only the JDK may"
                    + " define classes in: " + packageName);
        }
        check(mainClass, CLASS, "mainClass");
        check(version, VERSION, "version");
        requireQuotable(description, "description");
        requireQuotable(author, "author");
        requireLine(serverIp, "serverIp");
        requireLine(rconPassword, "rconPassword");
        requireLine(levelType, "levelType");
        if (rconPassword.isBlank()) {
            throw new IllegalArgumentException("rconPassword must not be blank");
        }
        checkPort(serverPort, "serverPort");
        checkPort(rconPort, "rconPort");
        if (serverPort == rconPort) {
            throw new IllegalArgumentException("serverPort and rconPort must differ");
        }
    }

    /**
     * Derives every value from a free-form project name such as {@code "spawn lobby"},
     * {@code "spawn-lobby"} or {@code "SpawnLobby"}.
     */
    public static TemplateVariables of(String projectName) {
        String ascii = ascii(projectName);
        List<String> words = words(ascii);
        if (words.isEmpty() || !Character.isLetter(words.getFirst().charAt(0))) {
            throw new IllegalArgumentException("Project name '" + projectName + "' cannot be used: it must start with"
                    + " a letter (A-Z, a-z)");
        }
        StringBuilder pascal = new StringBuilder();
        for (String w : words) {
            pascal.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        String name = pascal.toString();
        String lower = String.join("", words);
        try {
            return derived(name, words, lower);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Project name '" + projectName + "' cannot be used: " + e.getMessage(), e);
        }
    }

    private static TemplateVariables derived(String name, List<String> words, String lower) {
        return new TemplateVariables(
                name,
                String.join("-", words),
                "com.example." + lower,
                name.endsWith("Plugin") ? name : name + "Plugin",
                "0.1.0-SNAPSHOT",
                name + ", made with Minecraft Server Creator.",
                "",
                "127.0.0.1",
                25565,
                25575,
                randomPassword(),
                "minecraft:flat",
                false);
    }

    /**
     * The name with accents taken off ({@code Café} becomes {@code Cafe}), since ids, packages and class names
     * take ASCII only. A letter or digit with no ASCII form ({@code Ø}, {@code 日}) is refused, not dropped.
     */
    private static String ascii(String projectName) {
        String plain = NON_SPACING_MARKS.matcher(Normalizer.normalize(projectName == null ? "" : projectName,
                Normalizer.Form.NFD)).replaceAll("");
        var other = NON_ASCII_WORD_CHAR.matcher(plain);
        if (other.find()) {
            throw new IllegalArgumentException("Project name '" + projectName + "' cannot be used: '" + other.group()
                    + "' has no ASCII form, and ids, packages and class names take A-Z, a-z and 0-9 only");
        }
        return plain;
    }

    private static List<String> words(String projectName) {
        List<String> words = new ArrayList<>();
        var m = WORD.matcher(projectName);
        while (m.find()) {
            words.add(m.group().toLowerCase(Locale.ROOT));
        }
        return words;
    }

    private static String randomPassword() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public TemplateVariables withName(String v) { return new TemplateVariables(v, projectId, packageName, mainClass, version, description, author, serverIp, serverPort, rconPort, rconPassword, levelType, acceptEula); }
    public TemplateVariables withProjectId(String v) { return new TemplateVariables(name, v, packageName, mainClass, version, description, author, serverIp, serverPort, rconPort, rconPassword, levelType, acceptEula); }
    public TemplateVariables withPackageName(String v) { return new TemplateVariables(name, projectId, v, mainClass, version, description, author, serverIp, serverPort, rconPort, rconPassword, levelType, acceptEula); }
    public TemplateVariables withMainClass(String v) { return new TemplateVariables(name, projectId, packageName, v, version, description, author, serverIp, serverPort, rconPort, rconPassword, levelType, acceptEula); }
    public TemplateVariables withVersion(String v) { return new TemplateVariables(name, projectId, packageName, mainClass, v, description, author, serverIp, serverPort, rconPort, rconPassword, levelType, acceptEula); }
    public TemplateVariables withDescription(String v) { return new TemplateVariables(name, projectId, packageName, mainClass, version, v, author, serverIp, serverPort, rconPort, rconPassword, levelType, acceptEula); }
    public TemplateVariables withAuthor(String v) { return new TemplateVariables(name, projectId, packageName, mainClass, version, description, v, serverIp, serverPort, rconPort, rconPassword, levelType, acceptEula); }
    public TemplateVariables withServerIp(String v) { return new TemplateVariables(name, projectId, packageName, mainClass, version, description, author, v, serverPort, rconPort, rconPassword, levelType, acceptEula); }
    public TemplateVariables withServerPort(int v) { return new TemplateVariables(name, projectId, packageName, mainClass, version, description, author, serverIp, v, rconPort, rconPassword, levelType, acceptEula); }
    public TemplateVariables withRconPort(int v) { return new TemplateVariables(name, projectId, packageName, mainClass, version, description, author, serverIp, serverPort, v, rconPassword, levelType, acceptEula); }
    public TemplateVariables withRconPassword(String v) { return new TemplateVariables(name, projectId, packageName, mainClass, version, description, author, serverIp, serverPort, rconPort, v, levelType, acceptEula); }
    public TemplateVariables withLevelType(String v) { return new TemplateVariables(name, projectId, packageName, mainClass, version, description, author, serverIp, serverPort, rconPort, rconPassword, v, acceptEula); }

    public TemplateVariables withAcceptEula(boolean v) { return new TemplateVariables(name, projectId, packageName, mainClass, version, description, author, serverIp, serverPort, rconPort, rconPassword, levelType, v); }

    /** Placeholder values, including {@code packagePath} derived from the package. */
    Map<String, String> asMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("projectId", projectId);
        // NeoForge and Forge mod ids allow no hyphens.
        m.put("modId", projectId.replace('-', '_'));
        m.put("packageName", packageName);
        m.put("packagePath", packageName.replace('.', '/'));
        m.put("mainClass", mainClass);
        m.put("version", version);
        m.put("description", description);
        m.put("author", author);
        m.put("serverIp", serverIp);
        m.put("serverPort", Integer.toString(serverPort));
        m.put("rconPort", Integer.toString(rconPort));
        m.put("rconPassword", rconPassword);
        m.put("levelType", levelType);
        // A flat world needs explicit layers: an empty {} logs "No key layers" on world creation.
        m.put("generatorSettings", levelType.equals("minecraft:flat") || levelType.equals("flat") ? FLAT_LAYERS : "{}");
        m.put("eula", Boolean.toString(acceptEula));
        return m;
    }

    private static void check(String value, Pattern pattern, String field) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must match " + pattern.pattern() + ": " + value);
        }
    }

    private static void requireLine(String value, String field) {
        if (value == null || value.contains("\n") || value.contains("\r")) {
            throw new IllegalArgumentException(field + " must be a single line");
        }
    }

    /** Goes inside double quotes in Kotlin and YAML, so no quotes, backslashes or dollar signs. */
    private static void requireQuotable(String value, String field) {
        requireLine(value, field);
        if (value.contains("\"") || value.contains("\\") || value.contains("$")) {
            throw new IllegalArgumentException(field + " must not contain \", \\ or $: " + value);
        }
    }

    private static void checkPort(int port, String field) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(field + " must be 1-65535: " + port);
        }
    }
}
