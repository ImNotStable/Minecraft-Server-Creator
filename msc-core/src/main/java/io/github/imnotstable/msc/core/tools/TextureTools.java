package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.agent.AgentHub;
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
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pixel-art textures drawn as text, and the resource pack that carries them. */
public final class TextureTools {
    private TextureTools() {
    }

    public static void register(ToolRegistry registry, AgentHub hub, ResourcePack pack) {
        PackSource source = new PackSource(pack.root());
        registry.add("texture_create",
                "Draw a pixel-art texture from text and add it to the project's resource pack. "
                        + "Give a palette of single characters and one string per pixel row (16x16 is standard).",
                Schema.of().req("name", "string", "Texture name such as item/ruby or myplugin:block/ruby_ore")
                        .req("palette", "strings", "Entries symbol=#rrggbb or symbol=#rrggbbaa, for example \"r=#d4263a\". \".\" is transparent")
                        .req("rows", "strings", "Pixel rows, top first, each the same length")
                        .opt("model", "string", "Also write a model and item definition: item (flat, default), block (cube) or none"),
                args -> {
                    ResourcePack.TextureId id = ResourcePack.id(args.str("name"));
                    PaletteTexture.Parsed parsed = PaletteTexture.parse(args.strings("palette"), args.strings("rows"));
                    String itemModel = pack.writeTexture(id, parsed.image(), args.str("model", "item"));
                    StringBuilder out = new StringBuilder();
                    out.append("wrote ").append(id).append(' ').append(parsed.image().getWidth()).append('x')
                            .append(parsed.image().getHeight());
                    if (itemModel != null) {
                        out.append("; use it by setting an item's item_model component to ").append(itemModel);
                    }
                    for (String warning : parsed.warnings()) {
                        out.append("\nwarning: ").append(warning);
                    }
                    out.append("\nnext: pack_build");
                    return out.toString();
                });
        registry.add("texture_generate",
                "Generate a texture from a pattern (noise, ore, planks, bricks) or a 16x16 shape (gem, ingot, orb) "
                        + "shaded from one color. Returns stats and the pixels as text.",
                Schema.of().req("name", "string", "Texture name such as block/ruby_ore")
                        .req("kind", "string", "noise, ore, planks, bricks, gem, ingot or orb")
                        .req("color", "string", "Base color #rrggbb")
                        .opt("accent", "string", "Second color: the ore in ore, the mortar in bricks")
                        .opt("size", "integer", "16 (default), 32 or 64; shapes are 16")
                        .opt("seed", "integer", "Pattern seed. Default 1")
                        .opt("contrast", "number", "0.2 to 1.5. Default 1 for shapes, 0.6 for patterns")
                        .opt("model", "string", "As in texture_create; default item for shapes, block for patterns"),
                textures(args -> {
                    String kind = args.str("kind");
                    boolean shape = Shapes.NAMES.contains(kind);
                    if (!shape && !Patterns.NAMES.contains(kind)) {
                        throw new ToolException("kind must be noise, ore, planks, bricks, gem, ingot or orb");
                    }
                    double contrast = number(args, "contrast", shape ? 1.0 : 0.6);
                    Ramp ramp = Ramp.from(Colors.parse(args.str("color")), 5, contrast);
                    Ramp accent = args.has("accent") ? Ramp.from(Colors.parse(args.str("accent")), 4, 1.0) : null;
                    Texture t;
                    if (shape) {
                        if (args.integer("size", 16) != 16) {
                            throw new ToolException("shapes are 16x16; leave out size");
                        }
                        t = Shapes.draw(kind, ramp, null);
                    } else {
                        t = Patterns.generate(kind, args.integer("size", 16), ramp, accent, args.integer("seed", 1));
                    }
                    return written(pack, args.str("name"), t, args.str("model", shape ? "item" : "block")) + "\n"
                            + Budget.truncate(PaletteText.render(t), Budget.DEFAULT_CHARS, "Use texture_view.")
                            + "\nnext: texture_create to touch up, pack_build";
                }));
        registry.add("texture_recolor",
                "Copy a pack texture (not a vanilla one) onto new shades of one color, keeping its shading.",
                Schema.of().req("from", "string", "Pack texture such as item/ruby")
                        .req("name", "string", "New texture name")
                        .req("color", "string", "Base color #rrggbb")
                        .opt("model", "string", "As in texture_create. Default item"),
                textures(args -> {
                    Texture in = source.readTexture(ResourceId.parse(args.str("from")));
                    Texture t = Edits.recolor(in, Ramp.from(Colors.parse(args.str("color")), 5));
                    return written(pack, args.str("name"), t, args.str("model", "item")) + "\nnext: pack_build";
                }));
        registry.add("texture_animate",
                "Turn a still pack texture into an animation: scroll (flowing) or pulse (glowing).",
                Schema.of().req("name", "string", "Texture name")
                        .req("mode", "string", "scroll or pulse")
                        .req("frames", "integer", "Frame count, 2 to 64")
                        .req("frametime", "integer", "Ticks per frame")
                        .opt("dx", "integer", "Scroll pixels per frame across. Default 0")
                        .opt("dy", "integer", "Scroll pixels per frame down. Default 1"),
                textures(args -> {
                    ResourceId id = ResourceId.parse(args.str("name"));
                    Texture base = source.readTexture(id);
                    int frames = args.integer("frames");
                    int frametime = args.integer("frametime");
                    Animation a = switch (args.str("mode")) {
                        case "scroll" -> Animation.scroll(base, args.integer("dx", 0), args.integer("dy", 1), frames, frametime);
                        case "pulse" -> {
                            List<Integer> shades = new ArrayList<>(base.colors());
                            shades.sort(Comparator.comparingDouble(Colors::luminance));
                            yield Animation.pulse(base, new Ramp(shades), frames, frametime);
                        }
                        default -> throw new ToolException("mode must be scroll or pulse");
                    };
                    source.writeAnimation(id, a);
                    return "animated " + id + ": " + a.frames().size() + " frames, " + frametime
                            + " ticks each\nnext: pack_build";
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
                    String item = source.writeModel(model, Models.Kind.parse(args.str("kind")), slots,
                            args.bool("item", true));
                    return "wrote model " + model + (item == null ? "" : "; item_model " + item) + "\nnext: pack_build";
                }));
        registry.add("texture_view", "Read a texture from the project's resource pack back as a palette and pixel rows.",
                Schema.of().req("name", "string", "Texture name such as item/ruby"),
                args -> Budget.truncate(PaletteTexture.render(pack.readTexture(ResourcePack.id(args.str("name")))),
                        Budget.MAX_CHARS, "Texture too large to show."));
        registry.add("pack_check", "Check the project's resource pack for missing textures, bad models and other mistakes.",
                Schema.of().opt("limit", "integer", "Most findings to list. Default 30"),
                args -> {
                    if (!Files.isDirectory(pack.root())) {
                        throw new ToolException("the pack is empty. Create a texture first.");
                    }
                    return check(pack, Math.max(0, args.integer("limit", 30)));
                });
        registry.add("pack_build",
                "Zip the project's resource pack, serve it over HTTP and send it to the server's players.",
                Schema.of().opt("description", "string", "Pack description")
                        .opt("apply", "boolean", "Send to the running server. Default true")
                        .opt("required", "boolean", "Kick players who decline. Default false"),
                args -> {
                    ResourcePack.Built built = pack.build(args.str("description", "Minecraft Server Creator dev pack"));
                    StringBuilder out = new StringBuilder();
                    out.append("pack built: ").append(built.files()).append(" files, ").append(built.bytes())
                            .append(" bytes\nurl: ").append(built.url()).append("\nsha1: ").append(built.sha1());
                    if (args.bool("apply", true)) {
                        if (hub.current() == null) {
                            out.append("\nnot applied: no server agent connected");
                        } else {
                            Ops.PackResult r = hub.call(Ops.PACK_APPLY,
                                    new Ops.PackParams(built.url(), built.sha1(), args.bool("required", false)));
                            out.append("\napplied: sent to ").append(r.playersSent()).append(" players");
                        }
                    }
                    return out.append('\n').append(check(pack, 0).lines().findFirst().orElseThrow()).toString();
                });
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

    /** Lint the pack sources. pack.mcmeta is written by pack_build, so its absence before a build is not reported. */
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

    private static String written(ResourcePack pack, String name, Texture t, String model) throws IOException {
        ResourcePack.TextureId id = ResourcePack.id(name);
        String itemModel = pack.writeTexture(id, t.toImage(), model);
        return "wrote " + id + ": " + Stats.of(t) + (itemModel == null ? "" : "; item_model " + itemModel);
    }
}
