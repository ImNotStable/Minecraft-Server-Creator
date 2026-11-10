package io.github.imnotstable.msc.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.imnotstable.msc.protocol.message.AgentInfo;
import io.github.imnotstable.msc.protocol.message.Event;
import io.github.imnotstable.msc.protocol.message.Goodbye;
import io.github.imnotstable.msc.protocol.message.Hello;
import io.github.imnotstable.msc.protocol.message.Message;
import io.github.imnotstable.msc.protocol.message.MessageCodec;
import io.github.imnotstable.msc.protocol.message.Ping;
import io.github.imnotstable.msc.protocol.message.Pong;
import io.github.imnotstable.msc.protocol.message.Request;
import io.github.imnotstable.msc.protocol.message.Response;
import io.github.imnotstable.msc.protocol.message.Welcome;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageCodecTest {
    private static Message roundTrip(Message message) throws ProtocolException {
        String line = MessageCodec.encode(message);
        assertThat(line).doesNotContain("\n");
        return MessageCodec.decode(line);
    }

    @Test
    void everyMessageTypeRoundTrips() throws ProtocolException {
        JsonObject params = new JsonObject();
        params.addProperty("command", "say hi\nthere");
        List<Message> messages = List.of(
                new Hello(1, "tok", new AgentInfo("paper", "26.3.build.231-stable", "26.3", List.of("server.info"))),
                new Welcome(1, "s-1"),
                new Goodbye("shutdown", "bye"),
                new Request(7, "server.command", params),
                Response.ok(7, new JsonPrimitive(42)),
                Response.fail(8, "unknown_op", "nope"),
                new Event("server.ready", new JsonObject()),
                new Ping(5),
                new Pong(5));
        for (Message m : messages) {
            assertThat(roundTrip(m)).isEqualTo(m);
        }
    }

    @Test
    void typeFieldNamesTheMessage() {
        assertThat(MessageCodec.encode(new Ping(1))).isEqualTo("{\"type\":\"ping\",\"nonce\":1}");
    }

    @Test
    void missingParamsBecomeEmptyObject() throws ProtocolException {
        Message m = MessageCodec.decode("{\"type\":\"request\",\"id\":1,\"op\":\"server.info\"}");
        assertThat(((Request) m).params()).isEqualTo(new JsonObject());
    }

    @Test
    void rejectsBadFrames() {
        assertThatThrownBy(() -> MessageCodec.decode("not json")).isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> MessageCodec.decode("[1,2]")).isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> MessageCodec.decode("{\"id\":1}")).isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> MessageCodec.decode("{\"type\":\"nope\"}")).isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> MessageCodec.decode("{\"type\":\"hello\",\"protocolVersion\":1,\"agent\":{}}"))
                .isInstanceOf(ProtocolException.class);
    }
}
