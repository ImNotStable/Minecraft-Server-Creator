# Contributing

Thanks for your interest in Minecraft Server Creator (MSC).

## Where work happens

GitHub carries `main` and the releases. Day-to-day work (branches, pull requests, most CI) happens on a private
Gitea server that mirrors to GitHub, so pull requests on GitHub are turned off. To report a bug or propose a change,
open a [GitHub issue](https://github.com/ImNotStable/Minecraft-Server-Creator/issues) with what you saw, what you
expected and how to reproduce it (platform, Minecraft version, the tool call and its result). A change you want made
can link a branch or a patch in the issue.

Security problems go through [SECURITY.md](SECURITY.md), not a public issue.

## Building

Java 25 and the Gradle 9.9 wrapper (Gradle downloads Java 25 if it is missing):

```
./gradlew build                       # builds and tests everything
./gradlew :msc-protocol:test          # one module
```

`-Xlint:all -Werror` is on, so fix warnings instead of suppressing them. Versions live only in
`gradle/libs.versions.toml`.

## Rules for changes

- Every change comes with tests (JUnit 6 and AssertJ), and keeps test output short.
- **From scratch** ([ADR 0106](msc-docs/docs/adr/0106-from-scratch.md)): MSC gives the AI general primitives, guidance
  and quality review checks. It never adds prefabs, presets, one-line builders, canned templates or stock assets
  (builds, interiors, landforms, textures, models, themes, sounds, canned text, sample code). `FromScratchTest` fails
  on new ones.
- A decision gets an ADR in `msc-docs/docs/adr/` and a row in the Decisions table of `msc-docs/docs/plan.md`.
- A new library needs an entry in the version catalog and a row in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)
  with its license and where it ends up (`LicenseNoticesTest` checks this). Its license must be compatible with
  Apache-2.0 for the way MSC uses it.
- Never ship Mojang code or assets, and never make MSC accept the Minecraft EULA by default ([TERMS.md](TERMS.md)).

More conventions, for people and AI alike: [CLAUDE.md](CLAUDE.md).

## License of contributions

MSC is licensed under the [Apache License 2.0](LICENSE). Unless you say otherwise, anything you submit for
inclusion is licensed under the same terms (section 5 of the license), and you confirm you have the right to submit it.
