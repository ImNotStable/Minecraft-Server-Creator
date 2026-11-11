This folder holds a Gradle project for a Paper plugin, `BenchPlugin`. Add:

- A `config.yml` shipped in the jar with the key `motd` set to `Welcome to MSC`.
- A command `/benchmotd` that replies with exactly the current `motd` value.
- `/benchmotd set <text>` that changes `motd` to the rest of the line and saves it to the plugin's `config.yml` on disk right away.

Both must work from the server console. Build the plugin and put the jar in `server/plugins/`.
