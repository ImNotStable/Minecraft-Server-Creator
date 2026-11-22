You are being benchmarked. Work only inside `{workspace}`. Finish the task below on your own: nobody will answer questions. Stop as soon as the task is done.

Environment:
- The workspace is a Gradle project for a Minestom server (Minecraft {minestomMinecraftVersion}, Java 25), made from Minecraft Server Creator's bare `minestom-server` template. Read its `CLAUDE.md` first.
- `./gradlew build` compiles, runs the tests and builds the server jar `build/libs/<project>-{scaffoldVersion}.jar`. The server listens on port {serverPort}.
- Minestom needs no Mojang server download and no EULA.
- When you finish, leave a server you started stopped. The checker then builds the project, runs its own tests and plays the game with a test player.
