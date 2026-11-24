package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.core.tool.Args;
import io.github.imnotstable.msc.core.tool.ToolException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * An argument that only some ways of a tool read, and the one-line refusal for a call that gives it to another way.
 * A tool that picks a way (texture draws, generates or recolors; pack builds or only checks) refuses such an argument
 * instead of dropping it with an OK (#517, #619). The check runs before the tool does anything, so the refusal is the
 * whole answer. Every tool that picks a way (by a mode, a kind, a flag or the platform) declares its table here.
 */
public record WayArgs(String arg, Set<String> readers, String refusal) {
    /** The texture tool. Ways: rows, kind or copy; animate; pack (the texture goes into the pack, not a .png path). */
    static final List<WayArgs> TEXTURE = List.of(
            new WayArgs("palette", Set.of("rows"), "palette is for rows; copy takes color"),
            new WayArgs("color", Set.of("copy"), "color is for copy; rows take their colors from palette"),
            new WayArgs("frames", Set.of("animate"), "frames is for animate; leave it out or add animate"),
            new WayArgs("frametime", Set.of("animate"), "frametime is for animate; leave it out or add animate"),
            new WayArgs("model", Set.of("pack"),
                    "model is written only with rows or copy into the pack; leave it out"));

    /** The pack tool. Ways: check (only list problems) or build. */
    static final List<WayArgs> PACK = List.of(
            new WayArgs("description", Set.of("build"), "description is for a build; leave it out with check=true"),
            new WayArgs("file", Set.of("build"), "file is for a build; leave it out with check=true"),
            new WayArgs("apply", Set.of("build"), "apply is for a build; leave it out with check=true"),
            new WayArgs("required", Set.of("build"), "required is for a build; leave it out with check=true"));

    /** server_logs' modes, in the order its mode argument lists them. */
    static final List<String> LOG_MODES = List.of("errors", "tail", "grep", "triage");

    /** The server_logs tool (#619). Ways: its modes; all and maxChars are read by every mode. */
    static final List<WayArgs> LOGS = List.of(
            new WayArgs("pattern", Set.of("grep"), "pattern is for mode=grep; add mode=grep or leave it out"),
            new WayArgs("lines", Set.of("tail", "grep"), "lines is for mode=tail and mode=grep; leave it out"));

    /** The build_batch tool. Ways: write, or dryRun (places nothing). */
    static final List<WayArgs> BUILD = List.of(
            new WayArgs("physics", Set.of("write"), "physics is for a build that places blocks; leave it out with "
                    + "dryRun=true"));

    /** The msc_workspace tool. Ways: switch (a path is given) or show. */
    public static final List<WayArgs> WORKSPACE = List.of(
            new WayArgs("create", Set.of("switch"), "create is for a path to switch to; leave it out"));

    /** The platforms server_setup installs as a Minecraft server: not Minestom (the project) or a proxy. */
    private static final Set<String> MINECRAFT = Arrays.stream(Platform.values()).filter(Platform::needsEula)
            .map(Platform::id).collect(Collectors.toUnmodifiableSet());
    /** The way of a proxy setup or start that has a network of Paper backends (ADR 0088), which take the EULA. */
    static final String NETWORK = "network";
    private static final Set<String> PROXIES = Arrays.stream(Platform.values()).filter(Platform::proxy)
            .map(Platform::id).collect(Collectors.toUnmodifiableSet());

    /**
     * acceptEula, read only by a platform with a Minecraft EULA. One row for every tool that takes it, each picking the
     * platform its own way: server_setup the one it sets up, server_start the workspace's, project_scaffold the
     * template's ({@link #checkEula}, #625).
     */
    private static final WayArgs EULA = new WayArgs("acceptEula", with(MINECRAFT, NETWORK), "acceptEula is for a "
            + "server with a Minecraft EULA; minestom and a proxy without servers= have none, leave it out");

    /** The server_setup tool. Ways: the platform it sets up. */
    static final List<WayArgs> SETUP = List.of(
            new WayArgs("version", with(MINECRAFT, NETWORK), "version is for the Minecraft servers MSC installs; "
                    + "minestom and a proxy without servers= do not take one, leave it out"),
            new WayArgs("mode", MINECRAFT, "mode is for a Minecraft server's world; minestom sets it in its own "
                    + "code and a proxy has no world, leave it out"),
            new WayArgs("servers", PROXIES, "servers is for a proxy (velocity or waterfall): its backends; set up "
                    + "a backend with proxy= instead"),
            EULA);

    private static Set<String> with(Set<String> set, String more) {
        return java.util.stream.Stream.concat(set.stream(), java.util.stream.Stream.of(more))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Refuses acceptEula for {@code platform} when it has no Minecraft EULA: the shared rule of {@link #EULA}. */
    static void checkEula(Args args, Platform platform) {
        check(args, Set.of(platform.id()), List.of(EULA));
    }

    /** Refuses the first argument in {@code table} that {@code args} gives and none of {@code ways} reads. */
    public static void check(Args args, Set<String> ways, List<WayArgs> table) {
        for (WayArgs w : table) {
            if (args.has(w.arg) && Collections.disjoint(w.readers, ways)) {
                throw new ToolException(w.refusal);
            }
        }
    }
}
