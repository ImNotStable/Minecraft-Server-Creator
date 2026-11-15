package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.agent.AgentHub;
import io.github.imnotstable.msc.core.texture.PackProperties;
import io.github.imnotstable.msc.core.texture.ResourcePack;
import io.github.imnotstable.msc.core.tool.Args;
import io.github.imnotstable.msc.core.tool.Budget;
import io.github.imnotstable.msc.core.tool.PathArg;
import io.github.imnotstable.msc.core.tool.Schema;
import io.github.imnotstable.msc.core.tool.Tool;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.tool.ToolRegistry;
import io.github.imnotstable.msc.process.ServerPropertiesFile;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.transport.CoreServer;
import io.github.imnotstable.msc.textures.Animation;
import io.github.imnotstable.msc.textures.Colors;
import io.github.imnotstable.msc.textures.Edits;
import io.github.imnotstable.msc.textures.Models;
import io.github.imnotstable.msc.textures.PackLint;
import io.github.imnotstable.msc.textures.PackSource;
import io.github.imnotstable.msc.textures.PaletteText;
import io.github.imnotstable.msc.textures.Patterns;
import io.github.imnotstable.msc.textures.Ramp;
import io.github.imnotstable.msc.textures.ResourceId;
import io.github.imnotstable.msc.textures.Shapes;
import io.github.imnotstable.msc.textures.Stats;
import io.github.imnotstable.msc.textures.Texture;
import io.github.imnotstable.msc.textures.TextureException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pixel-art textures drawn as text, and the resource pack that carries them. */
public final class TextureTools {
    /**
     * Fewest frames texture animates: a one-frame animation is the still texture plus an mcmeta that changes nothing
     * (#511). msc-textures still reads one-frame strips, which a pack may hold.
     */
    static final int MIN_FRAMES = 2;

    private TextureTools() {
    }

    public static void register(ToolRegistry registry, AgentHub hub, ResourcePack pack, CoreConfig config) {
        Path serverDir = config.serverDir();
        PackSource source = pack.source();
        registry.add("texture",
                "Add a pixel-art texture to the project's resource pack: draw it as text "
                        + "(palette, rows), generate it (kind, color) or recolor a pack texture (copy, color). "
                        + "animate animates it; with only name and animate, the texture already there.",
                Schema.of().req("name", "string", "Texture name such as item/ruby or myplugin:block/ruby_ore, or a .png path for a file outside the pack")
                        .opt("palette", "strings", "Draw: symbol=#rrggbb or #rrggbbaa entries, for example \"r=#d4263a\". \".\" is transparent")
                        .opt("rows", "strings", "Draw: pixel rows, top first, all the same length (16 is standard)")
                        .opt("kind", "string", "Generate: pattern noise, ore, planks or bricks, or 16x16 shape gem, ingot, orb, "
                                + "sword, pickaxe or food")
                        .opt("copy", "string", "Recolor: the pack texture to copy onto shades of color, keeping its shading")
                        .opt("color", "string", "Base color #rrggbb, for kind and copy")
                        .opt("accent", "string", "kind's second color: ore's ore, bricks' mortar, a tool's handle, food's bone")
                        .opt("size", "integer", "Patterns: 16 (default), 32 or 64")
                        .opt("seed", "integer", "Pattern seed. Default 1")
                        .range("contrast", Ramp.MIN_CONTRAST, Ramp.MAX_CONTRAST, "Default 1 for shapes, 0.6 for patterns")
                        .opt("animate", "string", "scroll (flows down), \"scroll dx,dy\" or pulse (glows)")
                        .range("frames", MIN_FRAMES, Animation.MAX_FRAMES, "Default: one full loop for scroll, 8 for pulse")
                        .opt("frametime", "integer", "Ticks per frame. Default 2")
                        .opt("model", "string", "Also write a model and item definition: item (flat), handheld (tools), "
                                + "block (cube) or none. Default block for patterns, handheld for sword and pickaxe, none for minecraft:, else item"),
                textures(args -> texture(args, pack, source, config)));
        registry.add("texture_view",
                "Show a pack texture as a palette and pixel rows, the format texture draws. Animations show frame 1.",
                Schema.of().req("name", "string", "Texture name such as item/ruby")
                        .maxChars(),
                textures(args -> {
                    int maxChars = Budget.of(args);
                    ResourceId id = ResourceId.parse(args.str("name"));
                    Texture t = source.readTexture(id);
                    Texture frame = firstFrame(source, id, t);
                    String note = frame == t ? "" : "frame 1 of " + (t.height() / t.width()) + "\n";
                    String hint = Budget.raise(maxChars, "Not all rows fit.");
                    return note + Budget.truncate(PaletteText.view(frame), maxChars - note.length(), hint);
                }));
        registry.add("model_create",
                "Write a block or item model from pack textures, and optionally an item definition.",
                Schema.of().req("name", "string", "Model name such as block/ruby_log")
                        .req("kind", "string", "cube_all, cube_column, cube_bottom_top, orientable, cross, item or handheld")
                        .req("textures", "strings", "slot=texture, for example side=block/ruby_log; one bare name fills every slot")
                        .opt("item", "boolean", "Also write an item definition. Default true"),
                textures(args -> {
                    ResourceId model = ResourceId.parse(args.str("name"));
                    Map<String, ResourceId> slots = new LinkedHashMap<>();
                    for (String entry : args.strings("textures")) {
                        int eq = entry.indexOf('=');
                        slots.put(eq < 0 ? "*" : entry.substring(0, eq).trim(),
                                ResourceId.parse(entry.substring(eq + 1)));
                    }
                    Models.Kind kind = Models.Kind.parse(args.str("kind"));
                    boolean withItem = args.bool("item", true);
                    OutputFile.check(source.modelPath(model), "model");
                    String item = new Steps("model_create").then(WRITE_MODEL).run(WRITE_MODEL,
                            () -> source.writeModel(model, kind, slots, withItem));
                    return "wrote model " + model + (item == null ? "" : "; item_model " + item) + "\nnext: pack";
                }));
        registry.add("pack",
                "Zip the project's resource pack, serve it over HTTP, send it to the server's players and list "
                        + "its problems. check=true: only list problems.",
                Schema.of().opt("check", "boolean", "Only check, do not build")
                        .range("limit", 0, Integer.MAX_VALUE, "Problems to list. Default 30 with check, 5 on a build")
                        .opt("description", "string", "Pack description, up to two lines")
                        .opt("file", "string", "Also save the zip here, such as ruby-pack.zip")
                        .opt("apply", "boolean", "Send to the running server. Default true")
                        .opt("required", "boolean", "Kick players who decline"),
                args -> {
                    boolean check = args.bool("check", false);
                    WayArgs.check(args, Set.of(check ? "check" : "build"), WayArgs.PACK);
                    if (check) {
                        if (!Files.isDirectory(pack.root())) {
                            throw new ToolException("the pack is empty. Add a texture first.");
                        }
                        return check(pack, args.integer("limit", 30));
                    }
                    return pack(args, hub, pack, config, serverDir.resolve(ServerPropertiesFile.NAME));
                });
    }

    static final String BUILD = "build the pack and serve it";
    static final String OFFER = "point " + ServerPropertiesFile.NAME + " at this build";
    static final String APPLY = "send it to the server's players";
    static final String SAVE = "save the zip";

    /**
     * The pack build. Checked first: the arguments, the file= target and server.properties. Then, in this order:
     * build and serve (the pack host then serves only this build), point server.properties at it (so what the file
     * offers is always what is served), send it to the players, save the zip copy (last: it is the least important,
     * and a failed apply then writes nothing into the workspace).
     */
    private static String pack(Args args, AgentHub hub, ResourcePack pack, CoreConfig config, Path properties)
            throws IOException, InterruptedException {
        int limit = args.integer("limit", 5);
        String description = args.lines("description", LifecycleTools.DEFAULT_PACK_DESCRIPTION, 2);
        boolean required = args.bool("required", false);
        Path copy = args.has("file") ? OutputFile.check(args.path("file", config.inWorkspace()), "file") : null;
        boolean offered = pack.ours(PackProperties.url(properties));
        if (offered) {
            OutputFile.check(properties, ServerPropertiesFile.NAME);
        }
        boolean apply = args.bool("apply", true);
        boolean connected = apply && hub.current() != null;
        Steps steps = new Steps("pack").then(BUILD);
        if (offered) {
            steps.then(OFFER);
        }
        if (connected) {
            steps.then(APPLY);
        }
        if (copy != null) {
            steps.then(SAVE).ifFails(SAVE, "no file was written at " + copy + "; run pack file=... again to save it");
        }
        ResourcePack.Snapshot before = pack.snapshot();
        byte[] offeredBefore = offered ? Files.readAllBytes(properties) : null;
        ResourcePack.Built built = steps.run(BUILD, () -> {
            ResourcePack.Built b = pack.build(description);
            if (apply && !connected) {
                // The workspace's pack to send: the next start that connects an agent sends it (#655).
                pack.applied(new ResourcePack.Applied(description, required));
            }
            return b;
        });
        StringBuilder out = new StringBuilder();
        out.append("pack built: ").append(built.files()).append(" files, ").append(built.bytes())
                .append(" bytes, pack_format ").append(pack.packFormat()).append("\nurl: ").append(built.url())
                .append("\nsha1: ").append(built.sha1());
        // Until the players have it, a failure takes the build back: what they were sent, what the host serves and
        // what server.properties offers then agree again.
        try {
            if (offered && steps.run(OFFER, () -> PackProperties.write(properties, built.url(), built.sha1(), required))) {
                out.append("\n").append(ServerPropertiesFile.NAME).append(" updated to offer this build");
            }
            if (connected) {
                Ops.PackResult r = steps.run(APPLY, () -> {
                    CoreServer.AgentSession to = hub.current();
                    Ops.PackResult sent = hub.call(Ops.PACK_APPLY,
                            new Ops.PackParams(built.url(), built.sha1(), required));
                    // So dev_cycle does not send this connection the same pack again (#667).
                    pack.sent(to, built, required);
                    pack.applied(new ResourcePack.Applied(description, required));
                    return sent;
                });
                out.append("\napplied: sent to ").append(r.playersSent())
                        .append(" players; players who join later get it too, also after a restart");
            } else if (apply) {
                String never = hub.noAgentReason();
                out.append(never != null ? "\nnot sent: " + never
                        : "\nnot sent yet: no server agent connected; the next server_start or dev_cycle sends it");
            }
        } catch (ToolException e) {
            throw new ToolException(e.getMessage() + "\n" + takeBack(pack, before, properties, offeredBefore));
        }
        if (copy != null) {
            steps.run(SAVE, () -> OutputFile.copy(built.zip(), copy));
            out.append("\nsaved: ").append(copy);
        }
        try {
            return out.append('\n').append(check(pack, limit)).toString();
        } catch (IOException e) {
            return out.append("\nproblems not listed (").append(Steps.reason(e)).append("); pack check=true lists them")
                    .toString();
        }
    }

    /**
     * After a build whose send failed: serve the earlier build again and put server.properties back, so the URL the
     * agent hands joining players, the pack host and server.properties agree again. Returns what it did.
     */
    static String takeBack(ResourcePack pack, ResourcePack.Snapshot before, Path properties, byte[] offeredBefore) {
        try {
            pack.restore(before);
            if (offeredBefore != null) {
                OutputFile.write(properties, to -> Files.write(to, offeredBefore));
            }
            return "taken back: the pack host serves the earlier build again"
                    + (offeredBefore == null ? "" : " and " + ServerPropertiesFile.NAME + " offers it again")
                    + ", as the players have it; run pack again to send this one";
        } catch (IOException | RuntimeException e) {
            return "taking it back failed too (" + Steps.reason(e) + "): the pack host serves the new build, which the "
                    + "players were not sent; run pack again";
        }
    }

    /**
     * After any build that changed what the pack host serves: keep a server.properties that offers our pack pointing
     * at that build. Returns true when it did.
     */
    static boolean keepOffered(ResourcePack pack, Path properties, ResourcePack.Built built, boolean required)
            throws IOException {
        return pack.ours(PackProperties.url(properties)) && PackProperties.write(properties, built.url(), built.sha1(),
                required);
    }

    /** The texture tool: one of draw, generate or recolor, then an optional animation; or only animate. */
    static final String WRITE_TEXTURE = "write the texture and its model";
    static final String ANIMATE = "write the animation";
    static final String WRITE_PNG = "write the png";
    static final String WRITE_MODEL = "write the model and item definition";

    private static String texture(Args args, ResourcePack pack, PackSource source, CoreConfig config)
            throws IOException, InterruptedException {
        List<String> ways = new ArrayList<>();
        for (String way : List.of("rows", "kind", "copy")) {
            if (args.has(way)) {
                ways.add(way);
            }
        }
        if (ways.size() > 1) {
            throw new ToolException("give one of rows, kind or copy, not " + String.join(" and ", ways));
        }
        if (args.str("name").toLowerCase(Locale.ROOT).endsWith(".png")) {
            return looseFile(args, source, args.path("name", config.inWorkspace()), ways);
        }
        if (ways.isEmpty() && !args.has("animate")) {
            throw new ToolException("give rows and palette to draw, kind and color to generate, copy and color to "
                    + "recolor, or animate to animate the texture already there");
        }
        Set<String> read = new HashSet<>(ways);
        if (!ways.isEmpty()) {
            read.add("pack");
        }
        if (args.has("animate")) {
            read.add("animate");
        }
        WayArgs.check(args, read, WayArgs.TEXTURE);
        ResourceId id = ResourceId.parse(args.str("name"));
        // Everything that can refuse the arguments runs before the first write, so a refused call (a bad animate,
        // too many frames, an item that shows another model) leaves the pack as it was.
        String kind = args.str("kind", null);
        Texture t = null;
        String model = null;
        List<String> warnings = List.of();
        if (ways.contains("rows")) {
            if (!args.has("palette")) {
                throw new ToolException("rows need a palette");
            }
            t = PaletteText.parse(args.strings("palette"), args.strings("rows"));
            model = model(args, id, "item");
            warnings = drawWarnings(t);
        } else if (kind != null) {
            boolean shape = Shapes.NAMES.contains(kind);
            t = generated(args);
            model = model(args, id, shape ? (kind.equals("sword") || kind.equals("pickaxe") ? "handheld" : "item") : "block");
        } else if (ways.contains("copy")) {
            Texture in = firstFrame(source, ResourceId.parse(args.str("copy")));
            t = Edits.recolor(in, Ramp.from(color(args), 5));
            model = model(args, id, "item");
        }
        Animation animation = args.has("animate") ? animation(args, t == null ? firstFrame(source, id) : t) : null;
        OutputFile.check(source.texturePath(id), "texture");
        checkNameLength(id);
        Steps steps = new Steps("texture");
        if (t != null) {
            steps.then(WRITE_TEXTURE);
        }
        if (animation != null) {
            steps.then(ANIMATE).ifFails(ANIMATE, t == null ? "the texture is as it was"
                    : "the texture is written as a still image; texture name=" + id + " animate=... animates it");
        }
        StringBuilder out = new StringBuilder();
        Budget.Parts parts = new Budget.Parts();
        if (t != null) {
            Texture written = t;
            String finalModel = model;
            String itemModel = steps.run(WRITE_TEXTURE, () -> pack.writeTexture(id, written, finalModel));
            if (ways.contains("rows")) {
                out.append("wrote ").append(id).append(' ').append(t.width()).append('x').append(t.height());
            } else {
                out.append("wrote ").append(id).append(": ").append(Stats.of(t));
            }
            if (itemModel != null) {
                out.append("; item_model ").append(itemModel);
            }
            for (String warning : warnings) {
                out.append("\nwarning: ").append(warning);
            }
            if (kind != null && animation == null) {
                // One budget (#473): the drawing gets what the lines around it leave, "next: pack" stays last.
                parts.add(out.append('\n').toString())
                        .cut(n -> Budget.truncate(PaletteText.render(written), n, "Use texture_view."));
                out.setLength(0);
            }
        }
        if (animation != null) {
            steps.run(ANIMATE, () -> source.writeAnimation(id, animation));
            out.append(out.isEmpty() ? "" : "\n").append("animated ").append(id).append(": ")
                    .append(animation.frames().size()).append(" frames, ").append(args.integer("frametime", 2))
                    .append(" ticks each");
        }
        return parts.add(out.toString()).text(Budget.DEFAULT_CHARS, "next: pack", "Use texture_view.");
    }

    /** The longest file name a texture call writes, the animation's {@code <name>.png.mcmeta}. */
    private static final String LONGEST_SUFFIX = ".png.mcmeta";

    /**
     * Refuses a texture name too long for the file system before anything is written, so a refused name leaves no
     * model, item or texture folders behind.
     */
    static void checkNameLength(ResourceId id) {
        int max = PathArg.MAX_NAME_BYTES - LONGEST_SUFFIX.length();
        int bytes = id.name().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes > max) {
            throw new ToolException("texture name " + id.name().substring(0, 24) + "... is " + bytes
                    + " bytes; at most " + max + " fit the file system. Use a shorter name.");
        }
    }

    /**
     * A texture written as a plain PNG at a workspace path, outside the pack: no model, no animation. Saves the
     * write-then-copy round trip when a task wants the image itself.
     */
    private static String looseFile(Args args, PackSource source, Path file, List<String> ways)
            throws IOException, InterruptedException {
        if (args.has("animate")) {
            throw new ToolException("animate needs a pack texture name such as item/ruby, not a .png path");
        }
        if (ways.isEmpty()) {
            throw new ToolException("give rows and palette to draw, kind and color to generate, or copy and color to recolor");
        }
        WayArgs.check(args, Set.copyOf(ways), WayArgs.TEXTURE);
        OutputFile.check(file, "png");
        Texture t;
        List<String> warnings = List.of();
        if (args.has("rows")) {
            if (!args.has("palette")) {
                throw new ToolException("rows need a palette");
            }
            t = PaletteText.parse(args.strings("palette"), args.strings("rows"));
            warnings = drawWarnings(t);
        } else if (args.has("kind")) {
            t = generated(args);
        } else if (args.has("copy")) {
            t = Edits.recolor(firstFrame(source, ResourceId.parse(args.str("copy"))), Ramp.from(color(args), 5));
        } else {
            throw new ToolException("give rows and palette to draw, kind and color to generate, or copy and color to recolor");
        }
        Texture image = t;
        new Steps("texture").then(WRITE_PNG).run(WRITE_PNG, () -> OutputFile.replace(file, to -> {
            if (!javax.imageio.ImageIO.write(image.toImage(), "png", to.toFile())) {
                throw new IOException("no png writer");
            }
        }));
        StringBuilder out = new StringBuilder("wrote ").append(file).append(": ").append(Stats.of(t));
        warnings.forEach(w -> out.append("\nwarning: ").append(w));
        Budget.Parts parts = new Budget.Parts().add(out.toString());
        if (args.has("kind")) {
            parts.add("\n").cut(n -> Budget.truncate(PaletteText.render(t), n, "Too large to show."));
        }
        return parts.text(Budget.DEFAULT_CHARS, null, "Too large to show.");
    }

    /** The texture of {@code kind}: a 16x16 shape or a tiling pattern. */
    static Texture generated(Args args) {
        String kind = args.str("kind");
        boolean shape = Shapes.NAMES.contains(kind);
        if (!shape && !Patterns.NAMES.contains(kind)) {
            throw new ToolException("kind must be noise, ore, planks, bricks, gem, ingot, orb, sword, pickaxe or food");
        }
        KindArgs.check(args, kind);
        Ramp ramp = Ramp.from(color(args), 5, args.number("contrast", shape ? 1.0 : 0.6));
        Integer accent = args.has("accent") ? Colors.parse(args.str("accent")) : null;
        if (shape) {
            return Shapes.draw(kind, ramp, accent, null);
        }
        return Patterns.generate(kind, args.integer("size", 16), ramp,
                accent == null ? null : Ramp.from(accent, 4, 1.0), args.integer("seed", 1));
    }

    /** The animation {@code animate} asks for, made from {@code base}; refuses a bad spec without writing anything. */
    private static Animation animation(Args args, Texture base) {
        AnimateSpec spec = AnimateSpec.parse(args.str("animate"));
        int frametime = args.integer("frametime", 2);
        if (spec.pulse()) {
            List<Integer> shades = new ArrayList<>(base.colors());
            shades.sort(Comparator.comparingDouble(Colors::luminance));
            return Animation.pulse(base, new Ramp(shades), args.integer("frames", 8), frametime);
        }
        return args.has("frames")
                ? Animation.scroll(base, spec.dx(), spec.dy(), args.integer("frames"), frametime)
                : Animation.scroll(base, spec.dx(), spec.dy(), frametime);
    }

    /** A pack texture, or the first frame when it is already animated, so it can be animated or copied again. */
    private static Texture firstFrame(PackSource source, ResourceId id) throws IOException {
        return firstFrame(source, id, source.readTexture(id));
    }

    /** {@code t} itself, or its first frame when it is an animation strip. */
    private static Texture firstFrame(PackSource source, ResourceId id, Texture t) {
        Path png = source.texturePath(id);
        if (t.height() > t.width() && t.height() % t.width() == 0
                && Files.exists(png.resolveSibling(png.getFileName() + ".mcmeta"))) {
            Texture frame = Texture.square(t.width());
            frame.draw(t, 0, 0);
            return frame;
        }
        return t;
    }

    /** Things that will look wrong in game but do not stop a drawn texture being written. */
    private static List<String> drawWarnings(Texture t) {
        int width = t.width();
        int height = t.height();
        List<String> warnings = new ArrayList<>();
        if (Integer.bitCount(width) != 1) {
            warnings.add("width " + width + " is not a power of two; use 16, 32 or 64");
        }
        if (width != height && height % width != 0) {
            warnings.add("height " + height + " is not a multiple of width " + width
                    + "; textures are square, or stacked square frames for animation");
        } else if (width != height) {
            warnings.add((height / width) + " stacked frames: this needs a .png.mcmeta animation file to display correctly");
        }
        int colors = t.colors().size();
        if (colors > 16) {
            warnings.add(colors + " colors; vanilla-style textures use 4 to 12");
        }
        return warnings;
    }

    private static int color(Args args) {
        if (!args.has("color")) {
            throw new ToolException("kind and copy need a color #rrggbb");
        }
        return Colors.parse(args.str("color"));
    }

    /** msc-textures reports bad input as TextureException; tools report it as a one-line error. */
    private static Tool.Handler textures(Tool.Handler handler) {
        return args -> {
            try {
                return handler.call(args);
            } catch (TextureException e) {
                throw new ToolException(e.getMessage());
            }
        };
    }

    /** Lint the pack sources. pack.mcmeta is written by a build, so its absence before a build is not reported. */
    private static String check(ResourcePack pack, int limit) throws IOException {
        PackLint.Report r = PackLint.checkDirectory(pack.root());
        if (!Files.exists(pack.root().resolve("pack.mcmeta"))) {
            r = new PackLint.Report(r.findings().stream().filter(f -> !f.path().equals("pack.mcmeta")).toList(), r.files());
        }
        return r.text(limit);
    }

    /**
     * The model to write: the one asked for, else {@code fallback}; but a vanilla texture ({@code minecraft:block/...})
     * is a retexture, and a written model would replace vanilla's (a stem's cube_column with a cube_all), so none.
     */
    static String model(Args args, ResourceId id, String fallback) {
        return args.str("model", id.namespace().equals("minecraft") ? "none" : fallback);
    }
}
