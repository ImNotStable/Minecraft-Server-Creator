# Sky Sprint

Turn the project in the workspace (package `bench.sprint`, main class `SprintServer`) into "Sky Sprint", a short timed race.

Keep the template's classes and these public methods and constructors, because the checker's tests call them: `SprintServer.createWorld(InstanceManager)`, `GameConfig.game()`, `GameConfig.stages()`, `Stage.name()`, `Stage.spawn()`, `new Game(GameConfig, Instance, Progress, Runnable)`, `Game.applyWorldRules()`, `Game.stage(Player)`, `GameEvents.register(EventNode<Event>, Game)`, `new GameCommand(Game, Function<String, Player>)` and `GameCommand.handle(Player, String[])` with its `Reply(ok, text)`. Add anything else you like. The checker builds the world and game the way the template's `GameTest` does, without `main`, so everything below must work that way too.

## Course

Replace the template's stages with these three, in this order:

1. `lobby`: spawn 0.5, -60, 0.5. Goal: blocks x 0, y -60 to -58, z 4 to 5. Gold blocks under it at x 0, y -61, z 4 and 5.
2. `run`: spawn 0.5, -60, 10.5. Goal: blocks x -1 to 1, y -60 to -58, z 30 to 31.
3. `done`: spawn 0.5, -60, 40.5. No goal.

Coin pads: emerald blocks at x 0, y -61 (in place of the grass), z 15, 20 and 25. The gold and emerald blocks must be there in a fresh world built by `createWorld` (or placed by `Game` when it is created). Keep x 0 to 1 from z 0 to z 32 free of blocks at y -60 and -59: the test player walks straight along x 0.5.

## Rules

- Count time in server ticks (20 per second), never with the clock, so results do not depend on the machine.
- A run starts when a player reaches stage `run` (through the lobby goal or `game stage run <player>`). A new run starts with 0 coins.
- During a run, a player whose position is in a coin pad's column (x 0, y -60 to -58, that z) collects that coin, once per run, and is sent `Coin! (N/3)`, N being their coins this run.
- Reaching `run`'s goal ends the run. The time is the run's ticks / 20 seconds, minus 1.0 second per coin, never below 0, shown with one decimal (like `3.0s`). Send the player `Finished in 3.0s with 2 coins`. The player then moves on to `done` as usual.
- A run that reaches 60.0 seconds (1200 ticks) without finishing: send `Time's up!`, teleport the player to the `run` spawn and start a new run.
- Keep each player's best (lowest) time by player name, so the commands below also work for a player who left. Keep it in `Progress`, which saves to the file the game was given, so it survives a restart.

## Admin commands

Add these `/game` subcommands, with exactly these replies (the player is named Steve here):

- `game best Steve`: `Steve best: 3.0s`, or `Steve has no time yet`.
- `game top`: up to three lines, best first, like `1. Steve 3.0s`, or `no times yet`.
- `game coins Steve`: `Steve has 2 coins` (in the current or last run; this one may need Steve online).

Write tests for the rules and commands, and make `./gradlew build` pass.
