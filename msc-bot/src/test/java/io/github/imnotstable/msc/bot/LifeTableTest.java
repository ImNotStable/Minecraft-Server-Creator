package io.github.imnotstable.msc.bot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The test player's life-cycle on the wire (#319): every (state, server packet) pair, duplicates, packets out of
 * order and packets for another entity id included, with what the player sends and the state it ends in. The same
 * table holds on both protocol versions the bot speaks; {@link LifeTest} holds the bare transition table.
 */
class LifeTableTest {
    static final String EXPECTED = """
            | state (reached by) | server sends | bot answers | next state |
            |---|---|---|---|
            | SPAWNING (logged in) | Login | - | SPAWNING |
            | SPAWNING (logged in) | Respawn | - | SPAWNING |
            | SPAWNING (logged in) | PlayerPosition | AcceptTeleportation, PlayerLoaded | ALIVE |
            | SPAWNING (logged in) | SetHealth 0 | - | SPAWNING_DEAD |
            | SPAWNING (logged in) | SetHealth 20 | - | SPAWNING |
            | SPAWNING (logged in) | PlayerCombatKill, own id | - | SPAWNING_DEAD |
            | SPAWNING (logged in) | PlayerCombatKill, other id | - | SPAWNING |
            | SPAWNING (logged in) | GameEvent WIN_GAME SEEN_BEFORE | ClientCommand(PERFORM_RESPAWN) | SPAWNING |
            | SPAWNING (logged in) | GameEvent WIN_GAME FIRST_TIME | ClientCommand(PERFORM_RESPAWN) | SPAWNING |
            | SPAWNING (logged in) | GameEvent IMMEDIATE_RESPAWN | - | SPAWNING |
            | SPAWNING (logged in) | StartConfiguration | ConfigurationAcknowledged | CONFIGURING |
            | SPAWNING (logged in) | (disconnect) | - | DISCONNECTED |
            | SPAWNING (logged in) | (1 tick) | - | SPAWNING |
            | SPAWNING (logged in) | (61 ticks) | - | SPAWNING |
            | SPAWNING_DEAD (died before its first position) | Login | - | SPAWNING |
            | SPAWNING_DEAD (died before its first position) | Respawn | - | SPAWNING |
            | SPAWNING_DEAD (died before its first position) | PlayerPosition | AcceptTeleportation, PlayerLoaded | DEAD |
            | SPAWNING_DEAD (died before its first position) | SetHealth 0 | - | SPAWNING_DEAD |
            | SPAWNING_DEAD (died before its first position) | SetHealth 20 | - | SPAWNING_DEAD |
            | SPAWNING_DEAD (died before its first position) | PlayerCombatKill, own id | - | SPAWNING_DEAD |
            | SPAWNING_DEAD (died before its first position) | PlayerCombatKill, other id | - | SPAWNING_DEAD |
            | SPAWNING_DEAD (died before its first position) | GameEvent WIN_GAME SEEN_BEFORE | ClientCommand(PERFORM_RESPAWN) | SPAWNING_DEAD |
            | SPAWNING_DEAD (died before its first position) | GameEvent WIN_GAME FIRST_TIME | ClientCommand(PERFORM_RESPAWN) | SPAWNING_DEAD |
            | SPAWNING_DEAD (died before its first position) | GameEvent IMMEDIATE_RESPAWN | - | SPAWNING_DEAD |
            | SPAWNING_DEAD (died before its first position) | StartConfiguration | ConfigurationAcknowledged | CONFIGURING |
            | SPAWNING_DEAD (died before its first position) | (disconnect) | - | DISCONNECTED |
            | SPAWNING_DEAD (died before its first position) | (1 tick) | - | SPAWNING_DEAD |
            | SPAWNING_DEAD (died before its first position) | (61 ticks) | - | SPAWNING_DEAD |
            | ALIVE (placed) | Login | - | SPAWNING |
            | ALIVE (placed) | Respawn | - | SPAWNING |
            | ALIVE (placed) | PlayerPosition | AcceptTeleportation | ALIVE |
            | ALIVE (placed) | SetHealth 0 | - | DEAD |
            | ALIVE (placed) | SetHealth 20 | - | ALIVE |
            | ALIVE (placed) | PlayerCombatKill, own id | - | DEAD |
            | ALIVE (placed) | PlayerCombatKill, other id | - | ALIVE |
            | ALIVE (placed) | GameEvent WIN_GAME SEEN_BEFORE | ClientCommand(PERFORM_RESPAWN) | CREDITS |
            | ALIVE (placed) | GameEvent WIN_GAME FIRST_TIME | ClientCommand(PERFORM_RESPAWN) | CREDITS |
            | ALIVE (placed) | GameEvent IMMEDIATE_RESPAWN | - | ALIVE |
            | ALIVE (placed) | StartConfiguration | ConfigurationAcknowledged | CONFIGURING |
            | ALIVE (placed) | (disconnect) | - | DISCONNECTED |
            | ALIVE (placed) | (1 tick) | MovePlayerPosRot, ClientTickEnd | ALIVE |
            | ALIVE (placed) | (61 ticks) | MovePlayerPosRot, ClientTickEnd | ALIVE |
            | DEAD (died) | Login | - | SPAWNING |
            | DEAD (died) | Respawn | - | SPAWNING |
            | DEAD (died) | PlayerPosition | AcceptTeleportation | DEAD |
            | DEAD (died) | SetHealth 0 | - | DEAD |
            | DEAD (died) | SetHealth 20 | - | DEAD |
            | DEAD (died) | PlayerCombatKill, own id | - | DEAD |
            | DEAD (died) | PlayerCombatKill, other id | - | DEAD |
            | DEAD (died) | GameEvent WIN_GAME SEEN_BEFORE | ClientCommand(PERFORM_RESPAWN) | RESPAWN_ASKED |
            | DEAD (died) | GameEvent WIN_GAME FIRST_TIME | ClientCommand(PERFORM_RESPAWN) | RESPAWN_ASKED |
            | DEAD (died) | GameEvent IMMEDIATE_RESPAWN | - | DEAD |
            | DEAD (died) | StartConfiguration | ConfigurationAcknowledged | CONFIGURING |
            | DEAD (died) | (disconnect) | - | DISCONNECTED |
            | DEAD (died) | (1 tick) | - | DEAD |
            | DEAD (died) | (61 ticks) | ClientCommand(PERFORM_RESPAWN) | RESPAWN_ASKED |
            | RESPAWN_ASKED (died, then 21 ticks) | Login | - | SPAWNING |
            | RESPAWN_ASKED (died, then 21 ticks) | Respawn | - | SPAWNING |
            | RESPAWN_ASKED (died, then 21 ticks) | PlayerPosition | AcceptTeleportation | RESPAWN_ASKED |
            | RESPAWN_ASKED (died, then 21 ticks) | SetHealth 0 | - | RESPAWN_ASKED |
            | RESPAWN_ASKED (died, then 21 ticks) | SetHealth 20 | - | RESPAWN_ASKED |
            | RESPAWN_ASKED (died, then 21 ticks) | PlayerCombatKill, own id | - | RESPAWN_ASKED |
            | RESPAWN_ASKED (died, then 21 ticks) | PlayerCombatKill, other id | - | RESPAWN_ASKED |
            | RESPAWN_ASKED (died, then 21 ticks) | GameEvent WIN_GAME SEEN_BEFORE | ClientCommand(PERFORM_RESPAWN) | RESPAWN_ASKED |
            | RESPAWN_ASKED (died, then 21 ticks) | GameEvent WIN_GAME FIRST_TIME | ClientCommand(PERFORM_RESPAWN) | RESPAWN_ASKED |
            | RESPAWN_ASKED (died, then 21 ticks) | GameEvent IMMEDIATE_RESPAWN | - | RESPAWN_ASKED |
            | RESPAWN_ASKED (died, then 21 ticks) | StartConfiguration | ConfigurationAcknowledged | CONFIGURING |
            | RESPAWN_ASKED (died, then 21 ticks) | (disconnect) | - | DISCONNECTED |
            | RESPAWN_ASKED (died, then 21 ticks) | (1 tick) | - | RESPAWN_ASKED |
            | RESPAWN_ASKED (died, then 21 ticks) | (61 ticks) | - | RESPAWN_ASKED |
            | SPAWNING (respawned) | Login | - | SPAWNING |
            | SPAWNING (respawned) | Respawn | - | SPAWNING |
            | SPAWNING (respawned) | PlayerPosition | AcceptTeleportation, PlayerLoaded | ALIVE |
            | SPAWNING (respawned) | SetHealth 0 | - | SPAWNING_DEAD |
            | SPAWNING (respawned) | SetHealth 20 | - | SPAWNING |
            | SPAWNING (respawned) | PlayerCombatKill, own id | - | SPAWNING_DEAD |
            | SPAWNING (respawned) | PlayerCombatKill, other id | - | SPAWNING |
            | SPAWNING (respawned) | GameEvent WIN_GAME SEEN_BEFORE | ClientCommand(PERFORM_RESPAWN) | SPAWNING |
            | SPAWNING (respawned) | GameEvent WIN_GAME FIRST_TIME | ClientCommand(PERFORM_RESPAWN) | SPAWNING |
            | SPAWNING (respawned) | GameEvent IMMEDIATE_RESPAWN | - | SPAWNING |
            | SPAWNING (respawned) | StartConfiguration | ConfigurationAcknowledged | CONFIGURING |
            | SPAWNING (respawned) | (disconnect) | - | DISCONNECTED |
            | SPAWNING (respawned) | (1 tick) | - | SPAWNING |
            | SPAWNING (respawned) | (61 ticks) | - | SPAWNING |
            | CREDITS (sent WIN_GAME) | Login | - | SPAWNING |
            | CREDITS (sent WIN_GAME) | Respawn | - | SPAWNING |
            | CREDITS (sent WIN_GAME) | PlayerPosition | AcceptTeleportation | ALIVE |
            | CREDITS (sent WIN_GAME) | SetHealth 0 | - | DEAD |
            | CREDITS (sent WIN_GAME) | SetHealth 20 | - | CREDITS |
            | CREDITS (sent WIN_GAME) | PlayerCombatKill, own id | - | DEAD |
            | CREDITS (sent WIN_GAME) | PlayerCombatKill, other id | - | CREDITS |
            | CREDITS (sent WIN_GAME) | GameEvent WIN_GAME SEEN_BEFORE | ClientCommand(PERFORM_RESPAWN) | CREDITS |
            | CREDITS (sent WIN_GAME) | GameEvent WIN_GAME FIRST_TIME | ClientCommand(PERFORM_RESPAWN) | CREDITS |
            | CREDITS (sent WIN_GAME) | GameEvent IMMEDIATE_RESPAWN | - | CREDITS |
            | CREDITS (sent WIN_GAME) | StartConfiguration | ConfigurationAcknowledged | CONFIGURING |
            | CREDITS (sent WIN_GAME) | (disconnect) | - | DISCONNECTED |
            | CREDITS (sent WIN_GAME) | (1 tick) | - | CREDITS |
            | CREDITS (sent WIN_GAME) | (61 ticks) | MovePlayerPosRot, ClientTickEnd | ALIVE |
            | CONFIGURING (sent StartConfiguration) | FinishConfiguration, Login | FinishConfiguration | SPAWNING |
            | CONFIGURING (sent StartConfiguration) | (disconnect) | - | DISCONNECTED |
            | CONFIGURING (sent StartConfiguration) | (1 tick) | - | CONFIGURING |
            | CONFIGURING (sent StartConfiguration) | (61 ticks) | - | CONFIGURING |
            | DISCONNECTED (disconnected) | Login | - | DISCONNECTED |
            | DISCONNECTED (disconnected) | Respawn | - | DISCONNECTED |
            | DISCONNECTED (disconnected) | PlayerPosition | - | DISCONNECTED |
            | DISCONNECTED (disconnected) | SetHealth 0 | - | DISCONNECTED |
            | DISCONNECTED (disconnected) | SetHealth 20 | - | DISCONNECTED |
            | DISCONNECTED (disconnected) | PlayerCombatKill, own id | - | DISCONNECTED |
            | DISCONNECTED (disconnected) | PlayerCombatKill, other id | - | DISCONNECTED |
            | DISCONNECTED (disconnected) | GameEvent WIN_GAME SEEN_BEFORE | - | DISCONNECTED |
            | DISCONNECTED (disconnected) | GameEvent WIN_GAME FIRST_TIME | - | DISCONNECTED |
            | DISCONNECTED (disconnected) | GameEvent IMMEDIATE_RESPAWN | - | DISCONNECTED |
            | DISCONNECTED (disconnected) | StartConfiguration | ConfigurationAcknowledged | DISCONNECTED |
            | DISCONNECTED (disconnected) | (disconnect) | - | DISCONNECTED |
            | DISCONNECTED (disconnected) | (1 tick) | - | DISCONNECTED |
            | DISCONNECTED (disconnected) | (61 ticks) | - | DISCONNECTED |
            """;

    @Test
    void everyPairOnTheBuiltInProtocol() {
        assertThat(Wire.lifeTable()).isEqualTo(EXPECTED);
    }

    @Test
    void everyPairOnThePreviousProtocol() throws Exception {
        assertThat(ProtocolTableTest.onThePreviousProtocol("lifeTable")).isEqualTo(EXPECTED);
    }
}
