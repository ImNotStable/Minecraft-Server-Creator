package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.agent.AgentHub;
import io.github.imnotstable.msc.core.texture.PackProperties;
import io.github.imnotstable.msc.core.texture.PaletteTexture;
import io.github.imnotstable.msc.core.texture.ResourcePack;
import io.github.imnotstable.msc.core.tool.Args;
import io.github.imnotstable.msc.core.tool.Budget;
import io.github.imnotstable.msc.core.tool.Schema;
import io.github.imnotstable.msc.core.tool.Tool;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.tool.ToolRegistry;
import io.github.imnotstable.msc.protocol.op.Ops;
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
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pixel-art textures drawn as text, and the resource pack that carries them. */
public final class TextureTools {
    private TextureTools() {
    }

    public static void register(ToolRegistry registry, AgentHub hub, ResourcePack pack, CoreConfig config) {
        Path serverDir = config.serverDir();
        PackSource source = new PackSource(pack.root());
        registry.add("texture",
                "Add a pixel-art texture to the project's resource pack, in one of three ways: draw it as text "
                        + "(palette, rows), generate it (kind, color) or recolor a pack texture (copy, color). "
                        + "animate makes it an animation; with only name and animate, animates the texture already there.",
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
                        .opt("contrast", "number", "0.2 to 1.5. Default 1 for shapes, 0.6 for patterns")
                        .opt("animate", "string", "scroll (flows down), \"scroll dx,dy\" or pulse (glows)")
                        .opt("frames", "integer", "Animation frames, 2 to 64. Default: one full loop for scroll, 8 for pulse")
                        .opt("frametime", "integer", "Ticks per frame. Default 2")
                        .opt("model", "string", "Also write a model and item definition: item (flat), handheld (tools), "
                                + "block (cube) or none. Default block for patterns, handheld for sword and pickaxe, none for minecraft:, else item"),
                textures(args -> texture(args, pack, source, config)));
        registry.add("texture_view",
                "Show a pack texture as a palette and pixel rows, the format texture draws. Animations show frame 1.",
                Schema.of().req("name", "string", "Texture name such as item/ruby"),
                args -> {
                    ResourcePack.TextureId id = ResourcePack.id(args.str("name"));
                    BufferedImage image = pack.readTexture(id);
                    int frames = image.getHeight() / image.getWidth();
                    String note = "";
                    if (frames > 1 && image.getHeight() % image.getWidth() == 0 && animated(source, id)) {
                        image = image.getSubimage(0, 0, image.getWidth(), image.getWidth());
                        note = "frame 1 of " + frames + "\n";
                    }
                    return note + Budget.truncate(PaletteTexture.render(image), Budget.MAX_CHARS, "Texture too large to show.");
                });
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
                    String item = source.writeModel(model, Models.Kind.parse(args.str("kind")), slots,
                            args.bool("item", true));
                    return "wrote model " + model + (item == null ? "" : "; item_model " + item) + "\nnext: pack";
                }));
        registry.add("pack",
                "Zip the project's resource pack, serve it over HTTP, send it to the server's players and list "
                        + "its problems. check=true: only list problems.",
                Schema.of().opt("check", "boolean", "Only check the pack, do not build it. Default false")
                        .opt("limit", "integer", "Most problems to list. Default 30 with check, 5 on a build")
                        .opt("description", "string", "Pack description")
                        .opt("file", "string", "Also save the zip here, such as ruby-pack.zip")
                        .opt("apply", "boolean", "Send to the running server. Default true")
                        .opt("required", "boolean", "Kick players who decline. Default false"),
                args -> {
                    if (args.bool("check", false)) {
                        if (!Files.isDirectory(pack.root())) {
                            throw new ToolException("the pack is empty. Add a texture first.");
                        }
                        return check(pack, Math.max(0, args.integer("limit", 30)));
                    }
                    int limit = Math.max(0, args.integer("limit", 5));
                    String description = args.str("description", LifecycleTools.DEFAULT_PACK_DESCRIPTION);
                    ResourcePack.Built built = pack.build(description);
                    StringBuilder out = new StringBuilder();
                    out.append("pack built: ").append(built.files()).append(" files, ").append(built.bytes())
                            .append(" bytes, pack_format ").append(pack.packFormat()).append("\nurl: ").append(built.url())
                            .append("\nsha1: ").append(built.sha1());
                    if (args.has("file")) {
                        Path copy = config.resolve(args.str("file"));
                        Files.createDirectories(copy.toAbsolutePath().getParent());
                        Files.copy(built.zip(), copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        out.append("\nsaved: ").append(copy);
                    }
                    if (args.bool("apply", true)) {
                        if (hub.current() == null) {
                            out.append("\nnot applied: no server agent connected");
                        } else {
                            Ops.PackResult r = hub.call(Ops.PACK_APPLY,
                                    new Ops.PackParams(built.url(), built.sha1(), args.bool("required", false)));
                            pack.applied(new ResourcePack.Applied(description, args.bool("required", false)));
                            out.append("\napplied: sent to ").append(r.playersSent())
                                    .append(" players; players who join later get it too, also after a restart");
                        }
                    }
                    // A detached session made server.properties offer the pack: keep it pointing at this build.
                    Path properties = serverDir.resolve("server.properties");
                    if (pack.ours(PackProperties.url(properties)) && PackProperties.write(properties, built.url(),
                            built.sha1(), args.bool("required", false))) {
                        out.append("\nserver.properties updated to offer this build");
                    }
                    return out.append('\n').append(check(pack, limit)).toString();
                });
    }

    /** The texture tool: one of draw, generate or recolor, then an optional animation; or only animate. */
    private static String texture(Args args, ResourcePack pack, PackSource source, CoreConfig config) throws IOException {
        if (args.str("name").toLowerCase(Locale.ROOT).endsWith(".png")) {
            return looseFile(args, source, config.resolve(args.str("name")));
        }
        List<String> ways = new ArrayList<>();
        for (String way : List.of("rows", "kind", "copy")) {
            if (args.has(way)) {
                ways.add(way);
            }
        }
        if (ways.size() > 1) {
            throw new ToolException("give one of rows, kind or copy, not " + String.join(" and ", ways));
        }
        if (ways.isEmpty() && !args.has("animate")) {
            throw new ToolException("give rows and palette to draw, kind and color to generate, copy and color to "
                    + "recolor, or animate to animate the texture already there");
        }
        ResourcePack.TextureId id = ResourcePack.id(args.str("name"));
        StringBuilder out = new StringBuilder();
        String kind = args.str("kind", null);
        if (ways.contains("rows")) {
            if (!args.has("palette")) {
                throw new ToolException("rows need a palette");
            }
            PaletteTexture.Parsed parsed = PaletteTexture.parse(args.strings("palette"), args.strings("rows"));
            String itemModel = pack.writeTexture(id, parsed.image(), model(args, id, "item"));
            out.append("wrote ").append(id).append(' ').append(parsed.image().getWidth()).append('x')
                    .append(parsed.image().getHeight());
            if (itemModel != null) {
                out.append("; item_model ").append(itemModel);
            }
            for (String warning : parsed.warnings()) {
                out.append("\nwarning: ").append(warning);
            }
        } else if (kind != null) {
            boolean shape = Shapes.NAMES.contains(kind);
            Texture t = generated(args);
            String model = shape ? (kind.equals("sword") || kind.equals("pickaxe") ? "handheld" : "item") : "block";
            out.append(written(pack, id, t, model(args, id, model)));
            if (!args.has("animate")) {
                out.append('\n').append(Budget.truncate(PaletteText.render(t), Budget.DEFAULT_CHARS, "Use texture_view."));
            }
        } else if (ways.contains("copy")) {
            Texture in = firstFrame(source, ResourceId.parse(args.str("copy")));
            out.append(written(pack, id, Edits.recolor(in, Ramp.from(color(args), 5)), model(args, id, "item")));
        }
        if (args.has("animate")) {
            out.append(out.isEmpty() ? "" : "\n").append(animate(args, source, ResourceId.parse(id.toString())));
        }
        return out.append("\nnext: pack").toString();
    }

    /**
     * A texture written as a plain PNG at a workspace path, outside the pack: no model, no animation. Saves the
     * write-then-copy round trip when a task wants the image itself.
     */
    private static String looseFile(Args args, PackSource source, Path file) throws IOException {
        if (args.has("animate")) {
            throw new ToolException("animate needs a pack texture name such as item/ruby, not a .png path");
        }
        Texture t;
        List<String> warnings = List.of();
        if (args.has("rows")) {
            if (!args.has("palette")) {
                throw new ToolException("rows need a palette");
            }
            PaletteTexture.Parsed parsed = PaletteTexture.parse(args.strings("palette"), args.strings("rows"));
            t = Texture.of(parsed.image());
            warnings = parsed.warnings();
        } else if (args.has("kind")) {
            t = generated(args);
        } else if (args.has("copy")) {
            t = Edits.recolor(firstFrame(source, ResourceId.parse(args.str("copy"))), Ramp.from(color(args), 5));
        } else {
            throw new ToolException("give rows and palette to draw, kind and color to generate, or copy and color to recolor");
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        javax.imageio.ImageIO.write(t.toImage(), "png", file.toFile());
        StringBuilder out = new StringBuilder("wrote ").append(file).append(": ").append(Stats.of(t));
        warnings.forEach(w -> out.append("\nwarning: ").append(w));
        if (args.has("kind")) {
            out.append('\n').append(Budget.truncate(PaletteText.render(t), Budget.DEFAULT_CHARS, "Too large to show."));
        }
        return out.toString();
    }

    /** The texture of {@code kind}: a 16x16 shape or a tiling pattern. */
    private static Texture generated(Args args) {
        String kind = args.str("kind");
        boolean shape = Shapes.NAMES.contains(kind);
        if (!shape && !Patterns.NAMES.contains(kind)) {
            throw new ToolException("kind must be noise, ore, planks, bricks, gem, ingot, orb, sword, pickaxe or food");
        }
        Ramp ramp = Ramp.from(color(args), 5, number(args, "contrast", shape ? 1.0 : 0.6));
        Integer accent = args.has("accent") ? Colors.parse(args.str("accent")) : null;
        if (shape) {
            if (args.integer("size", 16) != 16) {
                throw new ToolException("shapes are 16x16; leave out size");
            }
            return Shapes.draw(kind, ramp, accent, null);
        }
        return Patterns.generate(kind, args.integer("size", 16), ramp,
                accent == null ? null : Ramp.from(accent, 4, 1.0), args.integer("seed", 1));
    }

    private static String animate(Args args, PackSource source, ResourceId id) throws IOException {
        Texture base = firstFrame(source, id);
        String[] mode = args.str("animate").trim().split("\\s+", 2);
        int frametime = args.integer("frametime", 2);
        Animation a = switch (mode[0]) {
            case "scroll" -> {
                int dx = 0;
                int dy = 1;
                if (mode.length > 1) {
                    String[] d = mode[1].split(",");
                    try {
                        dx = Integer.parseInt(d[0].trim());
                        dy = d.length > 1 ? Integer.parseInt(d[1].trim()) : 0;
                    } catch (NumberFormatException e) {
                        throw new ToolException("animate: write scroll dx,dy, for example \"scroll 0,1\"");
                    }
                }
                int step = Math.max(1, Math.max(Math.abs(dx), Math.abs(dy)));
                int loop = Math.clamp(base.width() / step, 2, Animation.MAX_FRAMES);
                yield Animation.scroll(base, dx, dy, args.integer("frames", loop), frametime);
            }
            case "pulse" -> {
                List<Integer> shades = new ArrayList<>(base.colors());
                shades.sort(Comparator.comparingDouble(Colors::luminance));
                yield Animation.pulse(base, new Ramp(shades), args.integer("frames", 8), frametime);
            }
            default -> throw new ToolException("animate must be scroll, \"scroll dx,dy\" or pulse");
        };
        source.writeAnimation(id, a);
        return "animated " + id + ": " + a.frames().size() + " frames, " + frametime + " ticks each";
    }

    /** A pack texture, or the first frame when it is already animated, so it can be animated or copied again. */
    private static Texture firstFrame(PackSource source, ResourceId id) throws IOException {
        Texture t = source.readTexture(id);
        if (t.height() > t.width() && t.height() % t.width() == 0
                && Files.exists(source.texturePath(id).resolveSibling(source.texturePath(id).getFileName() + ".mcmeta"))) {
            return Animation.fromStrip(t, 1, false).frames().getFirst();
        }
        return t;
    }

    private static boolean animated(PackSource source, ResourcePack.TextureId id) {
        Path png = source.texturePath(ResourceId.parse(id.toString()));
        return Files.exists(png.resolveSibling(png.getFileName() + ".mcmeta"));
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

    private static double number(Args args, String name, double fallback) {
        if (!args.has(name)) {
            return fallback;
        }
        try {
            return Double.parseDouble(args.str(name).trim());
        } catch (NumberFormatException e) {
            throw new ToolException("argument " + name + " must be a number");
        }
    }

    /**
     * The model to write: the one asked for, else {@code fallback}; but a vanilla texture ({@code minecraft:block/...})
     * is a retexture, and a written model would replace vanilla's (a stem's cube_column with a cube_all), so none.
     */
    static String model(Args args, ResourcePack.TextureId id, String fallback) {
        return args.str("model", id.namespace().equals("minecraft") ? "none" : fallback);
    }

    private static String written(ResourcePack pack, ResourcePack.TextureId id, Texture t, String model) throws IOException {
        String itemModel = pack.writeTexture(id, t.toImage(), model);
        return "wrote " + id + ": " + Stats.of(t) + (itemModel == null ? "" : "; item_model " + itemModel);
    }
}
