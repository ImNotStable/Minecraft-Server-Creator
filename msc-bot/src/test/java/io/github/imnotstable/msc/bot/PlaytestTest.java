package io.github.imnotstable.msc.bot;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.Unpooled;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntryAction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.EntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.ObjectEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.level.sound.CustomSound;
import org.geysermc.mcprotocollib.protocol.data.game.level.sound.SoundCategory;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerInfoUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSoundPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetTitleTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundInteractPacket;
import org.junit.jupiter.api.Test;

/** What the test player checks of a generated game beyond moving: the served pack, NPCs, chat buttons and menus. */
class PlaytestTest {
    private final List<Packet> sent = new ArrayList<>();
    private final TestPlayer p = Players.at(new FlatWorld(), null, 0.5, 1, 0.5, sent);

    @Test
    void theServedPackNamesItsGlyphsSoundsAndLint() throws IOException {
        PackContents pack = PackContents.of(zip(Map.of(
                "pack.mcmeta", "{\"pack\":{\"pack_format\":97,\"description\":\"t\"}}",
                "assets/game/font/hud.json", "{\"providers\":[{\"type\":\"bitmap\",\"file\":\"game:font/coin.png\","
                        + "\"ascent\":8,\"chars\":[\"\"]},{\"type\":\"space\",\"advances\":{\"\":4}}]}",
                "assets/game/sounds.json", "{\"coin\":{\"sounds\":[\"game:coin\"]}}")));
        assertThat(pack.glyphs()).containsExactlyInAnyOrder(0xE001, 0xE0F0);
        assertThat(pack.sounds()).containsExactly("game:coin");
        assertThat(pack.missingGlyphs(Set.of(0xE001, 0xE002))).containsExactly(0xE002);
        assertThat(pack.missingSounds(Set.of("game:coin", "game:boom", "minecraft:entity.pig.ambient")))
                .containsExactly("game:boom");
        // The font's texture and the sound's .ogg are not in the pack: the lint the bot reports says so.
        assertThat(TestPlayer.lintSummary(pack.lint())).startsWith("lint: ").contains("coin");
    }

    @Test
    void anNpcIsFoundAndClickedByItsName() {
        UUID shop = UUID.randomUUID();
        p.receive(new ClientboundAddEntityPacket(40, UUID.randomUUID(), EntityType.VILLAGER, 2.5, 1, 0.5, 0, 0, 0));
        p.receive(new ClientboundAddEntityPacket(41, shop, EntityType.VILLAGER, 1.5, 1, 1.5, 0, 0, 0));
        p.receive(new ClientboundSetEntityDataPacket(41, new EntityMetadata<?, ?>[] {
                new ObjectEntityMetadata<>(2, MetadataTypes.OPTIONAL_COMPONENT, Optional.of(Component.text("Old Miner")))}));
        assertThat(p.nearby(5)).anyMatch(s -> s.startsWith("villager \"Old Miner\""));
        assertThat(p.interactNearest("old miner", 4.5)).startsWith("villager \"Old Miner\"");
        // A player-shaped NPC takes its name from the tab list.
        GameProfile profile = new GameProfile(UUID.randomUUID(), "Guide");
        p.receive(new ClientboundPlayerInfoUpdatePacket(EnumSet.of(PlayerListEntryAction.ADD_PLAYER),
                new PlayerListEntry[] {new PlayerListEntry(profile.getId(), profile, false, 0, GameMode.SURVIVAL, null,
                        false, 0, null, 0, null, null)}));
        p.receive(new ClientboundAddEntityPacket(42, profile.getId(), EntityType.PLAYER, 0.5, 1, 2.5, 0, 0, 0));
        assertThat(p.interactNearest("guide", 4.5)).startsWith("player \"Guide\"");
    }

    @Test
    void aChatButtonRunsItsCommand() {
        p.receive(new ClientboundSystemChatPacket(Component.text("Old Miner: Buy a pick? ")
                .append(Component.text("[Yes]").clickEvent(ClickEvent.runCommand("/npcchoice 1 yes")))
                .append(Component.text(" "))
                .append(Component.text("[No]").clickEvent(ClickEvent.runCommand("npcchoice 1 no"))), false));
        assertThat(p.clickChat("yes")).isEqualTo("\"[Yes]\" ran /npcchoice 1 yes");
        assertThat(p.clickChat("no")).isEqualTo("\"[No]\" ran /npcchoice 1 no");
        assertThat(p.clickChat("maybe")).isNull();
        assertThat(sent).contains(new ServerboundChatCommandPacket("npcchoice 1 yes"),
                new ServerboundChatCommandPacket("npcchoice 1 no"));
    }

    @Test
    void aMenuSlotIsClickedWithTheMenusState() {
        assertThat(p.clickSlot(0)).isFalse();
        p.receive(new ClientboundOpenScreenPacket(3, ContainerType.GENERIC_9X3, Component.text("Shop")));
        p.receive(new ClientboundContainerSetContentPacket(3, 7, new ItemStack[63], null));
        assertThat(p.clickSlot(13)).isTrue();
        ServerboundContainerClickPacket click = sent.stream().filter(ServerboundContainerClickPacket.class::isInstance)
                .map(ServerboundContainerClickPacket.class::cast).findFirst().orElseThrow();
        assertThat(click.getContainerId()).isEqualTo(3);
        assertThat(click.getStateId()).isEqualTo(7);
        assertThat(click.getSlot()).isEqualTo(13);
        // No carried item: it must still go on the wire.
        click.serialize(Unpooled.buffer());
    }

    @Test
    void theHudSaysWhichGlyphsAndSoundsThePackLacks() {
        p.receive(new ClientboundSetTitleTextPacket(Component.text(" Coins")));
        p.receive(new ClientboundSoundPacket(new CustomSound("game:boom", false, 16), SoundCategory.MASTER, 0, 0, 0,
                1, 1, 0));
        assertThat(p.hudReport()).contains("sounds heard: game:boom")
                .contains("SOUNDS NOT IN THE PACK (a client plays nothing): game:boom, no pack loaded")
                .contains("GLYPHS NO PACK FONT DRAWS (a client shows boxes): U+E001, no pack loaded");
        assertThat(p.interactNearest(null, 4.5)).isNull();
        assertThat(sent).noneMatch(ServerboundInteractPacket.class::isInstance);
    }

    private static byte[] zip(Map<String, String> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> f : files.entrySet()) {
                out.putNextEntry(new ZipEntry(f.getKey()));
                out.write(f.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
