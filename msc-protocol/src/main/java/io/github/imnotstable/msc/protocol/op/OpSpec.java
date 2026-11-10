package io.github.imnotstable.msc.protocol.op;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.imnotstable.msc.protocol.message.MessageCodec;
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

    public JsonObject encodeParams(P params) {
        return MessageCodec.gson().toJsonTree(params, paramsType).getAsJsonObject();
    }

    public P decodeParams(JsonObject json) {
        return MessageCodec.gson().fromJson(json, paramsType);
    }

    public JsonElement encodeResult(R result) {
        return MessageCodec.gson().toJsonTree(result, resultType);
    }

    public R decodeResult(JsonElement json) {
        return MessageCodec.gson().fromJson(json, resultType);
    }
}
