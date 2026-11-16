package io.github.imnotstable.msc.protocol.transport;

import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.message.RawJson;
import io.github.imnotstable.msc.protocol.op.OpSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** A {@link RequestHandler} that dispatches typed handlers by operation name. */
public final class OpRouter implements RequestHandler {
    private final Map<String, RequestHandler> routes = new LinkedHashMap<>();

    /** Register a handler. Bad parameters map to {@code invalid_params}. */
    public <P, R> OpRouter on(OpSpec<P, R> op, Function<P, R> handler) {
        routes.put(op.name(), (name, json) -> {
            P params;
            try {
                params = op.decodeParams(json);
            } catch (RuntimeException e) {
                throw new OpException(ErrorCodes.INVALID_PARAMS, String.valueOf(e.getMessage()));
            }
            try {
                return op.encodeResult(handler.apply(params));
            } catch (IllegalArgumentException e) {
                throw new OpException(ErrorCodes.INVALID_PARAMS, String.valueOf(e.getMessage()));
            }
        });
        return this;
    }

    /** Operation names this router handles, for {@code AgentInfo.operations}. */
    public List<String> operations() {
        return List.copyOf(routes.keySet());
    }

    @Override
    public RawJson handle(String op, RawJson params) throws Exception {
        RequestHandler route = routes.get(op);
        if (route == null) {
            throw new OpException(ErrorCodes.UNKNOWN_OP, op);
        }
        return route.handle(op, params);
    }
}
