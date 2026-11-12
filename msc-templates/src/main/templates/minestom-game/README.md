# {{name}}

{{description}}

Minestom minigame server for Minecraft {{minestomMcVersion}} (Java {{javaVersion}}). Kit, stages and admins are set in `GameConfig.game()`; the console (and listed admins) can use `game status`, `game stage <name> [player]`, `game next`, `game reset` and `game kit`.

```
./gradlew build   # compile, test and build the server jar
./gradlew run     # dev server in run/ (type stop to stop)
```

Conventions for working on this project are in [CLAUDE.md](CLAUDE.md).
