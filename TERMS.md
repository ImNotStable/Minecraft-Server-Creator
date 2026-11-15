# Minecraft Server Creator: terms of use for the Minecraft EULA option

Minecraft Server Creator (MSC) can run Paper, Purpur, Folia, Fabric, Quilt, NeoForge and Forge dev servers. They run Mojang's Minecraft server software. That software only starts after its operator agrees to the [Minecraft End User License Agreement (EULA)](https://aka.ms/MinecraftEULA), which is an agreement between you and Mojang. MSC is not part of it and can't agree to it for you.

## What you confirm when you turn on EULA acceptance

You turn it on with any of these:

- `./gradlew :msc-core:installMsc -Pmsc.acceptEula=true`
- the `acceptEula=true` option on `server_setup` or `server_start`, which the AI passes only after you said yes
- typing `yes` to `... io.github.imnotstable.msc.core.Main eula accept` in a terminal

By turning it on, you confirm that:

1. You have read the [Minecraft EULA](https://aka.ms/MinecraftEULA) and accept it, for yourself, as the operator of the servers MSC runs on this machine.
2. You let MSC write `eula=true` into the `eula.txt` of every Paper, Purpur, Folia, Fabric, Quilt, NeoForge and Forge dev server it sets up or starts on this machine, from now on, without asking again.
3. You are responsible for following the EULA and Mojang's [usage guidelines](https://www.minecraft.net/usage-guidelines) on those servers.

MSC records your acceptance, with your user name, host name and the time, in `~/.msc/eula.json`. That file stays in your home folder and is never put in a project or a git repository.

## What MSC never does

- Accept the EULA by default. Installing, downloading or using MSC alone does not turn it on.
- Accept it on someone else's behalf. Each person who runs servers with MSC on their own machine turns it on themselves.
- Apply to Minestom servers. They don't use Mojang's server software, so they have no EULA step.

## Taking it back

Run the line in `~/.msc/core/eula-accept.txt` with `revoke` in place of `accept`, or delete `~/.msc/eula.json`. New servers will ask again. Servers that already have `eula=true` in their `eula.txt` keep it until you change that file.
