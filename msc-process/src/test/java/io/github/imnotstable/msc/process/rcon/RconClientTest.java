package io.github.imnotstable.msc.process.rcon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.process.rcon.FakeRconServer.Fault;
import io.github.imnotstable.msc.process.rcon.FakeRconServer.Other;
import io.github.imnotstable.msc.process.rcon.FakeRconServer.Quirks;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD) // a hang fails
class RconClientTest {

    @Test
    void logsInAndRunsCommands() throws IOException {
        try (FakeRconServer server = new FakeRconServer("secret", cmd -> "ran " + cmd);
                RconClient client = RconClient.connect("127.0.0.1", server.port(), "secret", Duration.ofSeconds(5))) {
            assertThat(client.command("list")).isEqualTo("ran list");
            assertThat(client.command("say é")).isEqualTo("ran say é");
            assertThat(server.commands()).containsExactly("list", "say é");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {FakeRconServer.CHUNK - 1, FakeRconServer.CHUNK, FakeRconServer.CHUNK + 1, 3 * FakeRconServer.CHUNK + 7})
    void readsAReplySplitOverSeveralPackets(int length) throws IOException {
        // Only the first 4096 characters came back: a long "data get" or block list was cut and checks misread it.
        String longReply = "x".repeat(length - 1) + "!";
        try (FakeRconServer server = new FakeRconServer("secret", cmd -> cmd.equals("long") ? longReply : "short");
                RconClient client = RconClient.connect("127.0.0.1", server.port(), "secret", Duration.ofSeconds(30))) {
            assertThat(client.command("long")).isEqualTo(longReply);
            assertThat(client.command("next")).as("no packet of the long reply is left for the next command")
                    .isEqualTo("short");
            assertThat(server.commands()).containsExactly("long", "next");
        }
    }

    @ParameterizedTest
    @CsvSource({
        "x, 1",
        "x, 4095",
        "x, 4096",
        "x, 12295",
        "é, 4097", // two bytes each: the first packet is 8206 bytes
        "€, 9000", // three bytes each, so the halves of a body cut characters in two
    })
    void readsAReplyWhosePacketsArriveInPieces(String unit, int count) throws IOException {
        // #442: a real server's long reply reaches the client in several TCP reads; reading each packet with one
        // read() took part of it as the whole and read body bytes as the next length.
        String reply = unit.repeat(count - 1) + "!";
        try (FakeRconServer server = new FakeRconServer("pässwort", cmd -> cmd.equals("long") ? reply : "ran " + cmd,
                        new Quirks(false, Other.ANSWER, true));
                // A generous end wait: the pieces after the first packet arrive while the client waits it.
                RconClient client = RconClient.connect("127.0.0.1", server.port(), "pässwort",
                        Duration.ofSeconds(30), Duration.ofSeconds(30))) {
            assertThat(client.command("long")).isEqualTo(reply);
            assertThat(client.command("next")).as("the stream is still in step").isEqualTo("ran next");
            assertThat(client.command("long")).isEqualTo(reply);
            assertThat(server.commands()).containsExactly("long", "next", "long");
            assertThat(server.connections()).isEqualTo(1);
        }
    }

    @Test
    void readsAnEmptyReply() throws IOException {
        try (FakeRconServer server = new FakeRconServer("secret", cmd -> "");
                RconClient client = RconClient.connect("127.0.0.1", server.port(), "secret", Duration.ofSeconds(30))) {
            assertThat(client.command("say hi")).isEmpty();
            assertThat(client.command("say again")).isEmpty();
        }
    }

    @Test
    void refusesAWrongPassword() throws IOException {
        try (FakeRconServer server = new FakeRconServer("secret", cmd -> "")) {
            // #393: its own type, so a caller can tell "refused" from "not up yet".
            assertThatThrownBy(() -> RconClient.connect("127.0.0.1", server.port(), "nope", Duration.ofSeconds(5)))
                    .isInstanceOf(RconRefusedException.class)
                    .hasMessageContaining("refused");
        }
    }

    @Test
    void neverSendsAPacketBehindTheCommandBeforeTheReply() throws IOException {
        // #379: the end marker went out right behind the command; a busy vanilla server read both in one read,
        // closed the connection without running the command, and the client got EOFException.
        String longReply = "y".repeat(2 * FakeRconServer.CHUNK + 5);
        try (FakeRconServer server = new FakeRconServer("pw", cmd -> cmd.equals("long") ? longReply : "ran " + cmd,
                        new Quirks(true, Other.ANSWER));
                RconClient client = RconClient.connect("127.0.0.1", server.port(), "pw", Duration.ofSeconds(30))) {
            for (int i = 0; i < 3; i++) {
                assertThat(client.command("say " + i)).isEqualTo("ran say " + i);
                assertThat(client.command("long")).isEqualTo(longReply);
            }
            assertThat(server.connections()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @EnumSource(Other.class)
    void sendsNoEndMarkerForAReplyThatFitsOnePacket(Other other) throws IOException {
        String reply = "z".repeat(FakeRconServer.CHUNK - 1);
        try (FakeRconServer server = new FakeRconServer("pw", cmd -> reply, new Quirks(false, other));
                RconClient client = RconClient.connect("127.0.0.1", server.port(), "pw", Duration.ofSeconds(30))) {
            for (int i = 0; i < 5; i++) {
                assertThat(client.command("c" + i)).isEqualTo(reply);
            }
            assertThat(server.connections()).as("no end marker, so nothing for the server to drop").isEqualTo(1);
        }
    }

    @ParameterizedTest
    @EnumSource(value = Other.class, names = {"IGNORE", "CLOSE"})
    void returnsALongReplyWhenTheServerDoesNotAnswerTheEndMarker(Other other) throws IOException {
        // A server that ignores the end marker, or hangs up on it, must not fail the command or the next one.
        String longReply = "w".repeat(FakeRconServer.CHUNK) + "é".repeat(FakeRconServer.CHUNK + 3) + "!";
        try (FakeRconServer server = new FakeRconServer("pw", cmd -> cmd.equals("long") ? longReply : "ran " + cmd,
                        new Quirks(false, other));
                // A short end wait: IGNORE is waited out once per long reply (#427). The fake writes a reply's
                // packets in one write, so they are all read before the wait starts and it cannot cut the reply.
                RconClient client = RconClient.connect("127.0.0.1", server.port(), "pw", Duration.ofSeconds(30),
                        Duration.ofMillis(200))) {
            assertThat(client.command("long")).isEqualTo(longReply);
            assertThat(client.command("next")).isEqualTo("ran next");
            assertThat(client.command("long")).isEqualTo(longReply);
            assertThat(client.command("last")).isEqualTo("ran last");
            assertThat(server.commands()).containsExactly("long", "next", "long", "last");
            assertThat(server.connections()).as("a new connection after each unanswered end marker").isEqualTo(3);
        }
    }

    @Test
    void refusesCommandsOnceClosed() throws IOException {
        try (FakeRconServer server = new FakeRconServer("pw", cmd -> "ok")) {
            RconClient client = RconClient.connect("127.0.0.1", server.port(), "pw", Duration.ofSeconds(30));
            client.close();
            assertThatThrownBy(() -> client.command("list")).isInstanceOf(IOException.class);
            assertThat(server.commands()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"pässwörd", "密码", "pw€\u00a0🙂"})
    void logsInWithANonAsciiPassword(String password) throws IOException {
        // #380: msc-core's client sent the password as US-ASCII, so 'ä' went out as '?' and the login was refused.
        try (FakeRconServer server = new FakeRconServer(password, cmd -> "ran " + cmd);
                RconClient client = RconClient.connect("127.0.0.1", server.port(), password, Duration.ofSeconds(30))) {
            assertThat(client.command("say ö")).isEqualTo("ran say ö");
        }
    }

    @Test
    void theLimitIsWhatOneVanillaReadHolds() {
        assertThat(RconClient.READ).isEqualTo(FakeRconServer.READ);
        assertThat(RconClient.MAX_BODY).isEqualTo(FakeRconServer.READ - 14).isEqualTo(1446);
    }

    @ParameterizedTest
    @CsvSource({
        "x, 1446, true",
        "x, 1447, false",
        "é, 723, true", // two bytes each in UTF-8: 1446 bytes
        "é, 724, false",
        "€, 482, true", // three bytes each: 1446 bytes
        "€, 483, false",
    })
    void refusesACommandOneVanillaReadCannotHoldBeforeSendingIt(String unit, int count, boolean fits)
            throws IOException {
        String command = unit.repeat(count);
        try (FakeRconServer server = new FakeRconServer("pw", cmd -> "ran " + cmd.length());
                RconClient client = RconClient.connect("127.0.0.1", server.port(), "pw", Duration.ofSeconds(30))) {
            if (fits) {
                assertThat(client.command(command)).isEqualTo("ran " + count);
                assertThat(server.commands()).containsExactly(command);
            } else {
                assertThatThrownBy(() -> client.command(command)).isInstanceOf(IOException.class)
                        .hasMessageContaining("at most 1446 bytes").hasMessageContaining("not sent");
                assertThat(server.commands()).isEmpty();
            }
            assertThat(client.command("list")).as("the connection still works").isEqualTo("ran 4");
            assertThat(server.connections()).isEqualTo(1);
        }
    }

    @Test
    void refusesAPasswordOneVanillaReadCannotHold() throws IOException {
        String password = "p".repeat(RconClient.MAX_BODY + 1);
        try (FakeRconServer server = new FakeRconServer(password, cmd -> "")) {
            assertThatThrownBy(() -> RconClient.connect("127.0.0.1", server.port(), password, Duration.ofSeconds(30)))
                    .isInstanceOf(RconRefusedException.class).hasMessageContaining("password").hasMessageContaining("1446");
            assertThat(server.connections()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void refusesATimeoutThatWouldMeanWaitingForever(long millis) throws IOException {
        // A socket timeout of 0 waits forever; a client stuck on a silent server would hang the core or the bench.
        try (FakeRconServer server = new FakeRconServer("pw", cmd -> "")) {
            assertThatThrownBy(() -> RconClient.connect("127.0.0.1", server.port(), "pw", Duration.ofMillis(millis)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
            assertThatThrownBy(() -> RconClient.connect("127.0.0.1", server.port(), "pw", Duration.ofSeconds(5),
                    Duration.ofMillis(millis))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @Timeout(120)
    void givesUpOnAServerThatNeverAnswersTheLogin() throws IOException {
        // The listener's backlog completes the connection, but nothing ever reads or answers it.
        try (ServerSocket silent = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
            assertThatThrownBy(() -> RconClient.connect("127.0.0.1", silent.getLocalPort(), "pw",
                    Duration.ofMillis(200))).isInstanceOf(SocketTimeoutException.class)
                    .isNotInstanceOf(RconRefusedException.class);
        }
    }

    /**
     * #455: one bad reply must not poison later commands. The failed command ends within its timeout (plus a margin
     * for a loaded machine) with a message, the next command connects afresh and succeeds, and the broken connection
     * is closed, so the server's thread for it ends.
     */
    @ParameterizedTest
    @EnumSource(Fault.class)
    void aBadReplyClosesTheConnectionAndTheNextCommandWorks(Fault fault) throws Exception {
        String longReply = "x".repeat(FakeRconServer.CHUNK + 10);
        Duration timeout = Duration.ofSeconds(1);
        try (FakeRconServer server = new FakeRconServer("secret", cmd -> cmd.equals("bad") ? longReply : "ran " + cmd);
                RconClient client = RconClient.connect("127.0.0.1", server.port(), "secret", timeout, timeout)) {
            server.fault("bad", fault);
            long start = System.nanoTime();
            switch (fault) {
                case JUNK_AFTER_END_ANSWER -> assertThat(client.command("bad")).isEqualTo(longReply);
                case CLOSE_MID_PACKET -> assertThatThrownBy(() -> client.command("bad"))
                        .isInstanceOf(IOException.class).hasMessage("server closed the RCON connection");
                case STALL_MID_PACKET, ENDLESS_OTHER_IDS -> assertThatThrownBy(() -> client.command("bad"))
                        .isInstanceOf(SocketTimeoutException.class).hasMessage("no RCON reply within 1000 ms");
            }
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(timeout.plusSeconds(5));
            assertThat(client.command("good")).isEqualTo("ran good");
            assertThat(server.connections()).isEqualTo(2);
            awaitOpen(server, 1);
        }
    }

    private static void awaitOpen(FakeRconServer server, int open) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (server.openConnections() != open && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(server.openConnections()).as("connections still open").isEqualTo(open);
    }
}
