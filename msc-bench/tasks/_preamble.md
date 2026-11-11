You are being benchmarked. Work only inside `{workspace}`. Finish the task below on your own: nobody will answer questions. Stop as soon as the task is done.

Environment:
- Minecraft {minecraftVersion} with a Paper server in `server/`. Start it from that folder with `java -jar paper.jar --nogui` if you need it. For world tasks it is already running.
- Server console over RCON: `127.0.0.1:{rconPort}`, password `{rconPassword}`. Game port {serverPort}.
- Java 25 and the Gradle wrapper (`gradlew`) are available for Gradle projects.
- When you finish, leave a server you started stopped. The checker then inspects files, and the world and plugins through RCON.
