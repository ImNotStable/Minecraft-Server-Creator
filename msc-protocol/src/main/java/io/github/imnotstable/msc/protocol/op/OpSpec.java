package io.github.imnotstable.msc.protocol.op;

import io.github.imnotstable.msc.protocol.message.MessageCodec;
import io.github.imnotstable.msc.protocol.message.RawJson;
import java.util.Objects;

/**
 * Name plus parameter and result types of one operation, so both sides convert JSON the same way.
 */
public record OpSpec<P, R>(String name, Class<P> paramsType, Class<R> resultType) {
    public OpSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(paramsType, "paramsType");
        Objects.requireNonNull(resultType, "resultType");
    }

    /**
     * The params as JSON text, written straight from the record with no tree in between: a {@code world.write} carries
     * a million blocks (ADR 0030).
     */
    public RawJson encodeParams(P params) {
        String json = MessageCodec.gson().toJson(params, paramsType);
        if (!json.startsWith("{")) {
            throw new IllegalArgumentException(name + " params must be a JSON object, not " + json);
        }
        return new RawJson(json);
    }

    /** The params read straight from their JSON text, which must hold one JSON object. */
    public P decodeParams(RawJson json) {
        return MessageCodec.gson().fromJson(json.json(), paramsType);
    }

    /** The result as JSON text, written straight from the record with no tree in between (ADR 0030). */
    public RawJson encodeResult(R result) {
        return new RawJson(MessageCodec.gson().toJson(result, resultType));
    }

    /** The result read straight from its JSON text; {@code null} for a missing result. */
    public R decodeResult(RawJson json) {
        return json == null ? null : MessageCodec.gson().fromJson(json.json(), resultType);
    }
}
