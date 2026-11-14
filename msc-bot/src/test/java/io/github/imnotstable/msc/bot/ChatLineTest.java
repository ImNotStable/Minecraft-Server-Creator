package io.github.imnotstable.msc.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Chat a server would kick for is an error of the action line, before anything is sent. */
class ChatLineTest {
    private static String words(int length) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; b.length() < length; i++) {
            b.append(b.isEmpty() ? "" : " ").append("word").append(i);
        }
        return b.substring(0, length).strip();
    }

    @Test
    void aShortSayIsOneMessageAndALongOneSplitsAtSpaces() {
        assertThat(ChatLine.say("  hello there ")).containsExactly("hello there");
        assertThat(ChatLine.say("x".repeat(256))).containsExactly("x".repeat(256));
        String text = words(700);
        List<String> parts = ChatLine.say(text);
        assertThat(parts).hasSize(3).allSatisfy(part -> assertThat(part).hasSizeLessThanOrEqualTo(256)
                .doesNotStartWith(" ").doesNotEndWith(" "));
        assertThat(String.join(" ", parts)).isEqualTo(text); // cut only at spaces, nothing lost
        assertThat(ChatLine.say("y".repeat(300))).containsExactly("y".repeat(256), "y".repeat(44));
        assertThatThrownBy(() -> ChatLine.say(words(1100))).hasMessageStartingWith("say text is at most about 1024");
        assertThatThrownBy(() -> ChatLine.say(" ")).hasMessage("say needs text: say hello");
        assertThatThrownBy(() -> ChatLine.say("/help")).hasMessageStartingWith("say text cannot start with /");
    }

    @Test
    void aLongWordOfEmojiSplitsBetweenPairsAndPreviewsKeepPairsWhole() {
        for (int pad = 0; pad < 2; pad++) {
            String text = "x".repeat(pad) + "\uD83D\uDE00".repeat(200);
            List<String> parts = ChatLine.say(text);
            assertThat(String.join("", parts)).isEqualTo(text);
            assertThat(parts).hasSize(2).allSatisfy(p -> {
                assertThat(p.length()).isLessThanOrEqualTo(ChatLine.MAX);
                assertThat(Character.isLowSurrogate(p.charAt(0))).isFalse();
                assertThat(Character.isHighSurrogate(p.charAt(p.length() - 1))).isFalse();
            });
            assertThatThrownBy(() -> ChatLine.command("/" + text)).hasMessageNotContaining("\uD83D...");
        }
    }

    @Test
    void aCommandIsNeverSplit() {
        String command = "/say " + "z".repeat(251);
        assertThat(ChatLine.command(command)).isEqualTo(command);
        assertThatThrownBy(() -> ChatLine.command(command + "z")).hasMessageStartingWith(
                "a command is at most 256 characters, as a player's chat box holds no more (this one has 257)");
    }

    @ParameterizedTest
    @ValueSource(strings = {"say §ahello", "/give MscTester diamond_sword[custom_name='§cBlade']",
            "say tab\there", "say bell\u0007", "say del\u007f"})
    void sectionSignsAndControlCharactersAreRefused(String line) {
        assertThatThrownBy(() -> Script.parse(line))
                .hasMessageStartingWith("chat and commands cannot contain § or control characters");
    }

    @Test
    void theStepSendsEachPartAndSaysItSplit() {
        List<Packet> sent = new ArrayList<>();
        TestPlayer p = Players.at(new FlatWorld(), null, 0.5, 1, 0.5, sent);
        String text = words(400);
        assertThat(Players.drive(p, Script.parse("say " + text), 100))
                .isEqualTo("sent as 2 messages, split at spaces (a chat message is at most 256 characters)");
        List<String> messages = sent.stream().filter(ServerboundChatPacket.class::isInstance)
                .map(m -> ((ServerboundChatPacket) m).getMessage()).toList();
        assertThat(messages).isEqualTo(ChatLine.say(text));
        assertThat(Players.drive(p, Script.parse("/time set day"), 100)).isEqualTo("sent");
        assertThat(sent).contains(new ServerboundChatCommandPacket("time set day"));
        assertThatThrownBy(() -> p.chat("§")).isInstanceOf(IllegalArgumentException.class);
    }
}
