# ADR 0048: One server per workspace, and projects it does not run

Status: accepted, 2026-11-15, decided by Claude (#636).

## Context

A workspace has one platform, remembered in `.msc/platform` (ADR 0027), and one server folder. Projects of the same
agent family share it: Quilt runs a Fabric mod, Purpur and Folia run a Paper plugin, and `Platform.serving` names the
workspace's own platform for them (#631).

A project of another family is different. `project_scaffold` remembers the template's platform only for a scaffold
into the workspace root or a workspace with none, so `template=fabric-mod dir=foo` in a Paper workspace left it Paper.
Its next step then said `server_setup acceptEula=true` with no `platform=`, which sets up Paper again. `dev_cycle`
deployed the mod to `run/mods/` (the Fabric folder) and restarted Paper, which never loads it, and answered OK with no
error line. Every step looked right, and no Fabric server was ever set up.

The options were: the scaffold names `platform=` and says the workspace does not run the project; `dev_cycle` refuses
such a project; or both.

## Decision

Both, through one rule. `Platform.otherFamily(config, project)` is the workspace's remembered platform when
`Platform.serving` does not pick it for the project (another agent family), else null. With nothing remembered it is
null, as the first deploy or scaffold remembers the project's platform.

- `project_scaffold` into a subfolder of such a workspace keeps the workspace's platform: a second project does not
  silently move the server out from under the first. It adds one line,
  `the workspace stays Paper, which never loads this Fabric project: server_setup platform=fabric switches it (Paper
  projects then need server_setup platform=paper)`, and its `next:` step names `server_setup platform=fabric` in every
  variant (the EULA question, a machine EULA, no EULA), also when the loader's server jar is already in the folder.
- `dev_cycle` refuses such a project in one line before the build, changing nothing. The line names the project's
  family, the workspace's, `server_setup platform=<project's>` to switch, and `msc_workspace` to give the project its
  own workspace.

A scaffold into the workspace root still switches the workspace to the template's platform, as before.

## Consequences

- No call answers OK for a deploy the server never loads, and following the scaffold's steps sets up the server that
  runs the project.
- A workspace that holds a Paper plugin and a Fabric mod runs one of them at a time. Switching is one `server_setup`
  each way; the other server's files stay in the folder, so switching back downloads nothing new. Two servers at once
  take two workspaces.
- The scaffold's result can be one line longer (up to 8 lines).

## Amendment, 2026-11-15: what a project runs on (#642, #643)

The rule above asked about one platform, the one `Platform.detect` names first. A jar that is both a Paper and a
Velocity plugin (`plugin.yml` and `velocity-plugin.json`, as ViaVersion ships) is detected as Paper, so a Velocity
workspace refused it, although Velocity loads it and `dev_cycle` deployed it before.

- The rule now takes every platform the project runs on, `Platform.runsOn(project)`: each platform whose sign
  (descriptor, Gradle plugin or dependency) the project shows, the detected one first. A Minestom project's jar is the
  server, so Minestom counts only when nothing else shows. `Platform.serving(config, runsOn)` is the workspace's
  remembered platform when any of them is of its agent family, else the first; `otherFamily` is the remembered
  platform when `serving` does not pick it. `dev_cycle` passes what the project's files show, `project_scaffold` the
  template's platform, which is all a scaffolded template's files show (a test checks every template).
- `template=paper-server` writes only server files, which every platform uses. It is not a project: the scaffold never
  remembers its platform, never adds the "never loads" line, and its next step is for the workspace's own server
  (`Platform.current`), so a set-up Fabric workspace gets `next: edit the code, then dev_cycle`, not the EULA question
  and `server_setup platform=paper`.

## Amendment, 2026-11-15: a sign inside another platform's sign (#648)

Build signs match text anywhere in the build files, and ModDevGradle's Forge plugin id,
`net.neoforged.moddev.legacyforge`, contains NeoForge's sign `net.neoforged.moddev`. With every sign counted, a Forge mod
built with it ran on NeoForge too, so a NeoForge workspace deployed it with an OK although NeoForge never loads it.

- A build sign's text counts only where no longer sign of another platform covers the same text. The covered text is
  the longer sign's platform's alone, whatever the order of the signs, so `runsOn` of that mod is Forge only and a
  NeoForge workspace refuses it again. NeoForge's own plugin elsewhere in the same build still counts.
- Descriptors are whole file paths (`META-INF/mods.toml` is not `META-INF/neoforge.mods.toml`), so they cannot overlap.
- A test writes every sign's text into a build file, as a plugin id, a dependency, a catalog entry and a line of its
  own, and checks that the project runs on that sign's platform only; that legacyforge id was the only overlap.
