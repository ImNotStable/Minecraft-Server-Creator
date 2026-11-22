package io.github.imnotstable.msc.stdlib.event;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Your own game events, separate from the platform's: {@code GameStarted}, {@code PlayerScored}, {@code RoundEnded}.
 * Handlers of a type also get its subtypes; higher priority runs first; a {@link Cancellable} event that a handler
 * cancels skips the handlers after it unless they ask for cancelled events.
 *
 * <pre>{@code
 * record PlayerScored(UUID player, int points) {}
 * bus.on(PlayerScored.class, e -> scoreboard.add(e.player(), e.points()));
 * bus.post(new PlayerScored(id, 5));
 * }</pre>
 */
public final class EventBus {
    private record Handler(Class<?> type, int priority, boolean receiveCancelled, Consumer<Object> action) {}

    private final List<Handler> handlers = new CopyOnWriteArrayList<>();
    private final Map<Class<?>, List<Handler>> byType = new ConcurrentHashMap<>();

    /** Events that a handler can stop. */
    public interface Cancellable {
        boolean cancelled();

        void cancelled(boolean cancel);
    }

    /** A {@link Cancellable} base class. */
    public abstract static class CancellableEvent implements Cancellable {
        private boolean cancelled;

        @Override
        public boolean cancelled() {
            return cancelled;
        }

        @Override
        public void cancelled(boolean cancel) {
            this.cancelled = cancel;
        }
    }

    /** Registration of one handler; close it to remove the handler. */
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    public <E> Subscription on(Class<E> type, Consumer<E> action) {
        return on(type, 0, false, action);
    }

    @SuppressWarnings("unchecked")
    public <E> Subscription on(Class<E> type, int priority, boolean receiveCancelled, Consumer<E> action) {
        Handler h = new Handler(type, priority, receiveCancelled, e -> action.accept((E) e));
        handlers.add(h);
        byType.clear();
        return () -> {
            handlers.remove(h);
            byType.clear();
        };
    }

    /** Runs the handlers of {@code event} and returns it (check {@link Cancellable#cancelled} after). */
    public <E> E post(E event) {
        List<Handler> hs = byType.computeIfAbsent(event.getClass(), c -> {
            List<Handler> list = new ArrayList<>();
            for (Handler h : handlers) {
                if (h.type().isAssignableFrom(c)) {
                    list.add(h);
                }
            }
            list.sort(Comparator.comparingInt(Handler::priority).reversed());
            return list;
        });
        for (Handler h : hs) {
            if (event instanceof Cancellable c && c.cancelled() && !h.receiveCancelled()) {
                continue;
            }
            h.action().accept(event);
        }
        return event;
    }

    public void clear() {
        handlers.clear();
        byType.clear();
    }
}
