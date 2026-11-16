package io.github.imnotstable.msc.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.imnotstable.msc.protocol.message.AgentInfo;
import io.github.imnotstable.msc.protocol.message.Challenge;
import io.github.imnotstable.msc.protocol.message.Event;
import io.github.imnotstable.msc.protocol.message.Goodbye;
import io.github.imnotstable.msc.protocol.message.Hello;
import io.github.imnotstable.msc.protocol.message.Message;
import io.github.imnotstable.msc.protocol.message.MessageCodec;
import io.github.imnotstable.msc.protocol.message.Ping;
import io.github.imnotstable.msc.protocol.message.Pong;
import io.github.imnotstable.msc.protocol.message.Proof;
import io.github.imnotstable.msc.protocol.message.RawJson;
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
                new Hello(2, "n-a", new AgentInfo("paper", "26.3.build.231-stable", "26.3", List.of("server.info"))),
                new Challenge("n-c"),
                new Proof("p-a"),
                new Welcome(2, "s-1", "p-c"),
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
    void decodesWhereverTheTypeStands() throws ProtocolException {
        assertThat(MessageCodec.decode("{\"nonce\":3,\"type\":\"ping\"}")).isEqualTo(new Ping(3));
        assertThat(MessageCodec.decode("{\"type\":\"ping\",\"nonce\":3}")).isEqualTo(new Ping(3));
        assertThat(MessageCodec.decode(" {\"type\" : \"pong\", \"nonce\":4}")).isEqualTo(new Pong(4));
        assertThat(MessageCodec.decode("{\"type\":\"ping\"}")).isEqualTo(new Ping(0));
        assertThat(MessageCodec.encode(new Response(2, null, null))).isEqualTo("{\"type\":\"response\",\"id\":2}");
    }

    @Test
    void missingParamsBecomeEmptyObject() throws ProtocolException {
        Message m = MessageCodec.decode("{\"type\":\"request\",\"id\":1,\"op\":\"server.info\"}");
        assertThat(((Request) m).params()).isEqualTo(new RawJson("{}"));
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
