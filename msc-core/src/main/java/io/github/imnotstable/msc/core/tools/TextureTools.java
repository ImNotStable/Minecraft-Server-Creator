package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.agent.AgentHub;
import io.github.imnotstable.msc.core.texture.PaletteTexture;
import io.github.imnotstable.msc.core.texture.ResourcePack;
import io.github.imnotstable.msc.core.tool.Budget;
import io.github.imnotstable.msc.core.tool.Schema;
import io.github.imnotstable.msc.core.tool.ToolRegistry;
import io.github.imnotstable.msc.protocol.op.Ops;
import java.util.List;

/** Pixel-art textures drawn as text, and the resource pack that carries them. */
public final class TextureTools {
    private TextureTools() {
    }

    public static void register(ToolRegistry registry, AgentHub hub, ResourcePack pack) {
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
        registry.add("texture_view", "Read a texture from the project's resource pack back as a palette and pixel rows.",
                Schema.of().req("name", "string", "Texture name such as item/ruby"),
                args -> Budget.truncate(PaletteTexture.render(pack.readTexture(ResourcePack.id(args.str("name")))),
                        Budget.MAX_CHARS, "Texture too large to show."));
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
                    return out.toString();
                });
    }
}
