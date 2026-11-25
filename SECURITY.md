# Security policy

## Supported versions

Only the newest release and `main` get security fixes.

## Reporting a vulnerability

Please do not open a public issue. Report it privately through GitHub's
[private vulnerability reporting](https://github.com/ImNotStable/Minecraft-Server-Creator/security/advisories/new)
with what is affected, how to reproduce it and what an attacker gains. Expect a first answer within a week.

In scope: msc-core (the MCP server), the agents it installs into dev servers, the agent link between them, the Claude
Code plugin and its launcher, and the standard library that scaffolded projects bundle. MSC runs local dev servers:
the agent link and dev servers are meant for your own machine or LAN, so findings that need them exposed to the
internet are still welcome but rated lower.

Known advisories in dependencies, and what was done about each, are tracked in
[msc-docs/docs/security-triage.md](msc-docs/docs/security-triage.md).
