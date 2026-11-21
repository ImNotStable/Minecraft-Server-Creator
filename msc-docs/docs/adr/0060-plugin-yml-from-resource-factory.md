# ADR 0060: plugin.yml from resource-factory in the Paper templates

Status: accepted, 2026-11-21, decided by Claude on Jeremiah's request.

## Context

The Paper templates shipped a hand-written `src/main/resources/plugin.yml`, with `version` filled in by
`processResources`. A mistake in it (a wrong `main`, a typo'd key, an unquoted `Yes`) only showed when the server
loaded the plugin. ADR 0057 had left resource-factory out as a replacement for a six-line file; Jeremiah asked for it.

## Decision

- `paper-plugin` and `paper-game` apply `xyz.jpenilla.resource-factory-bukkit-convention` (1.4.0) and declare the
  metadata in a `bukkitPluginYaml { }` block of `build.gradle.kts`: `name`, `main`, `apiVersion` from the catalog's
  Minecraft version, the author when one is given, and in `paper-game` the `<projectId>.admin` permission. Version and
  description come from the project. resource-factory writes `plugin.yml` at build time and fails the build on a bad
  value, so the mistakes above surface in `dev_cycle`'s build report.
- The `plugin.yml` file and its `processResources` expansion are gone; the template CLAUDE.md files say where metadata
  and dependencies (`depend.add(...)`) go and not to add the file back.
- MSC already recognises a Paper project by resource-factory's plugin ids (ADR 0045's signs), so platform detection is
  unchanged; `checkUpstreamVersions` tracks resource-factory.

## Consequences

- Generated `plugin.yml` checked on the verify builds: the same keys as before, YAML quoting done by the plugin.
- `TemplateNameCasesTest` reads `name` and `main` from `build.gradle.kts`; `TemplateVariables` already keeps `"`, `\`
  and `$` out of the values written into Kotlin strings.
