package io.github.imnotstable.msc.bot;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The test player's life-cycle as one state machine: whether it is in a world, alive, dead, waiting for the server
 * to respawn it or to place it. {@link TestPlayer} turns each server packet that changes it into an {@link Input},
 * and does what the {@link Transition} says; nothing else decides when the player moves or asks to respawn. Not
 * thread-safe: the player calls it under its own lock. The whole table is {@link #TABLE}; LifeTest prints it.
 */
final class Life {
    /** Where the player is in its life-cycle. */
    enum State {
        /** Logged in or respawned (also the start), waiting for the server's first position: it sends no moves. */
        SPAWNING,
        /** Died before that position came: it asks to respawn once it is placed. */
        SPAWNING_DEAD,
        /** In a world, placed: it sends a move and a tick end every tick. */
        ALIVE,
        /** Dead, on the death screen: it asks to respawn {@link #RESPAWN_DELAY} ticks later, or 1 tick without one. */
        DEAD,
        /** Dead and asked to respawn once: it waits for the server's Respawn, and does not ask again by itself. */
        RESPAWN_ASKED,
        /**
         * Sent the credits (WIN_GAME): it asked to respawn at once, as the vanilla client does when it skips them, and
         * holds its moves while the server takes it out of the End. Not every WIN_GAME is the exit portal (Paper's
         * Player#showWinScreen, Minestom's WIN_GAME): with no Respawn or position within {@link #CREDITS_WAIT} ticks
         * it plays on where it is, as the vanilla client does once the credits close (#319).
         */
        CREDITS,
        /** The server re-configures it: out of the world until the next login. */
        CONFIGURING,
        /** The connection is gone; nothing changes it any more. */
        DISCONNECTED
    }

    /** What happened, as far as the life-cycle cares. */
    enum Input {
        /** A Login packet: a new player in a new level. */
        LOGIN,
        /** A Respawn packet: a death's respawn, or a world change. */
        RESPAWN,
        /** A PlayerPosition packet: the server placed the player. */
        POSITION,
        /** Health 0, or the server's kill message for this player's entity id. */
        DIED,
        /** A GameEvent WIN_GAME, whatever its credits value. */
        WIN_GAME,
        /** A StartConfiguration packet. */
        CONFIGURE,
        /** The connection closed. */
        DISCONNECT,
        /** A client tick. */
        TICK,
        /** A client tick on which the state's wait ran out (see {@link Life#tick}). */
        WAIT_OVER
    }

    /** What the player does on a transition. */
    enum Action {
        /** Send ClientCommand PERFORM_RESPAWN. */
        ASK_RESPAWN,
        /** Send PlayerLoaded: the first position since a login or respawn came. */
        LOADED,
        /** Run the step and the physics, send the move and the tick end. */
        MOVE,
        /** End the running step (the player is out of it: dead, re-configured, gone, or out of the End). */
        END_STEP
    }

    /** One row of the table: from {@code state} on {@code input}, go to {@code next} and do {@code actions}. */
    record Transition(State state, Input input, State next, List<Action> actions) {
        boolean has(Action action) {
            return actions.contains(action);
        }
    }

    /** Ticks a dead player waits before it asks to respawn, as if a person read the death screen first. */
    static final int RESPAWN_DELAY = 20;
    /** Ticks a player holds its moves after the credits when no Respawn or position comes (the server's load wait). */
    static final int CREDITS_WAIT = 60;

    /** Every (state, input) pair; a pair that is not listed keeps the state and does nothing. */
    static final Map<State, Map<Input, Transition>> TABLE = table();

    private State state = State.SPAWNING;
    /** Ticks spent in the current state, for {@link State#DEAD} and {@link State#CREDITS}. */
    private int waited;
    /** The server shows no death screen (the doImmediateRespawn rule): the dead ask on their first tick. */
    private boolean immediateRespawn;

    State state() {
        return state;
    }

    /** The server has placed the player since its last login or respawn. */
    boolean positioned() {
        return switch (state) {
            case ALIVE, DEAD, RESPAWN_ASKED, CREDITS -> true;
            default -> false;
        };
    }

    boolean dead() {
        return switch (state) {
            case SPAWNING_DEAD, DEAD, RESPAWN_ASKED -> true;
            default -> false;
        };
    }

    void immediateRespawn(boolean immediate) {
        immediateRespawn = immediate;
    }

    /** Take in {@code input} and move to the next state. */
    Transition on(Input input) {
        Transition t = TABLE.get(state).get(input);
        if (t.next() != state || input == Input.WIN_GAME) {
            waited = 0; // a repeated WIN_GAME restarts the credits wait, as it reopens the vanilla credits
        }
        state = t.next();
        return t;
    }

    /** A client tick: {@link Input#WAIT_OVER} once the state's wait has run out, else {@link Input#TICK}. */
    Transition tick() {
        int wait = switch (state) {
            case DEAD -> immediateRespawn ? 1 : RESPAWN_DELAY;
            case CREDITS -> CREDITS_WAIT;
            default -> 0;
        };
        // At least, not exactly: the death screen rule can change while the player waits.
        return on(wait > 0 && ++waited >= wait ? Input.WAIT_OVER : Input.TICK);
    }

    private static Map<State, Map<Input, Transition>> table() {
        Map<State, Map<Input, Transition>> table = new EnumMap<>(State.class);
        for (State s : State.values()) {
            Map<Input, Transition> row = new EnumMap<>(Input.class);
            for (Input i : Input.values()) {
                row.put(i, rule(s, i));
            }
            table.put(s, row);
        }
        return table;
    }

    private static Transition rule(State s, Input i) {
        if (s == State.DISCONNECTED) {
            return to(s, i, s);
        }
        if (i == Input.DISCONNECT) {
            return to(s, i, State.DISCONNECTED, Action.END_STEP);
        }
        if (i == Input.LOGIN) {
            return to(s, i, State.SPAWNING);
        }
        if (s == State.CONFIGURING) {
            return to(s, i, s); // play packets cannot come; ticks send nothing until the login
        }
        return switch (i) {
            case RESPAWN -> to(s, i, State.SPAWNING);
            case CONFIGURE -> to(s, i, State.CONFIGURING, Action.END_STEP);
            case POSITION -> switch (s) {
                case SPAWNING -> to(s, i, State.ALIVE, Action.LOADED);
                case SPAWNING_DEAD -> to(s, i, State.DEAD, Action.LOADED);
                case CREDITS -> to(s, i, State.ALIVE); // the server placed it where it is: play on
                default -> to(s, i, s);
            };
            case DIED -> switch (s) {
                case SPAWNING -> to(s, i, State.SPAWNING_DEAD, Action.END_STEP);
                case ALIVE, CREDITS -> to(s, i, State.DEAD, Action.END_STEP);
                default -> to(s, i, s); // the kill message and health 0 of one death
            };
            // The vanilla client asks on every WIN_GAME, at once or when the credits close; so does the player.
            case WIN_GAME -> switch (s) {
                case ALIVE -> to(s, i, State.CREDITS, Action.ASK_RESPAWN, Action.END_STEP);
                case DEAD -> to(s, i, State.RESPAWN_ASKED, Action.ASK_RESPAWN);
                default -> to(s, i, s, Action.ASK_RESPAWN);
            };
            case TICK -> s == State.ALIVE ? to(s, i, s, Action.MOVE) : to(s, i, s);
            case WAIT_OVER -> switch (s) {
                case DEAD -> to(s, i, State.RESPAWN_ASKED, Action.ASK_RESPAWN);
                case CREDITS -> to(s, i, State.ALIVE, Action.MOVE);
                default -> to(s, i, s);
            };
            default -> throw new IllegalStateException(i.name());
        };
    }

    private static Transition to(State s, Input i, State next, Action... actions) {
        return new Transition(s, i, next, List.of(actions));
    }
}
