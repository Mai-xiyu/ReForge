# ReForge

[中文](README_CN.md) | English

An experimental compatibility layer for running NeoForge mods on Minecraft Forge, covering loading, bytecode conversion, API shims, events, and resources.

**This is a vibecoding project.** Much of the code is generated or modified with AI assistance. Maintainers are responsible for validation and maintenance; passing builds do not establish mod compatibility or long-term stability.

## Players

Current baseline: **Minecraft 1.21 / Forge 51.0.33 / Java 21**. Forge 1.20.1 is unsupported; support for Minecraft 1.21.1 is not established.

1. Use a separate game instance and back up worlds.
2. Build from source and install only `build/libs/reforge-1.0.0.jar` in `mods/`.
3. Add the target NeoForge mods and their dependencies, then check startup logs and gameplay.

The single installation JAR embeds its runtime and MixinExtras. Other JARs in `build/intermediates/jars/` are build inputs or test fixtures and should not be installed.

Startup preserves original NeoForge JARs and generates discovery copies in `.reforged/discovery/`. Manual `patchNeoForgeMods` replaces inputs and retains `.neoforge-original` backups. An author's incompatible dependency on the internal Mod ID `reforged` excludes that mod from bridge loading.

Known limits:

- Automatic attachment network synchronization is unsupported.
- Real multiplayer, Jade gameplay, complex modpacks, and long-running worlds remain unverified.
- Conflicting embedded dependencies and required loading or registration failures block startup.

Report reproducible bridge failures in [Issues](https://github.com/Mai-xiyu/ReForge/issues), with exact versions, reproduction steps, logs, and client/server scope. Redact private information. Report to original mod authors when the failure also reproduces on native NeoForge.

## Developers

Use JDK 21 and the Gradle wrapper:

```sh
git clone https://github.com/Mai-xiyu/ReForge.git
cd ReForge
./gradlew build verifyReleaseJar
```

On Windows, use `.\gradlew.bat`. The installable JAR and its SHA-256 file are in `build/libs/`.

| Command | Purpose |
| --- | --- |
| `./gradlew test` | Contract and failure-path regression tests |
| `./gradlew runClient` | Development client using the packaged bootstrap |
| `./gradlew runClient -PreforgedRunDir=build/smoke/client -PclientSmoke=true` | Minimal mod fixtures reach the title screen and exit |
| `./gradlew runGameTestServer -PreforgedRunDir=build/smoke/gametest -PpersistencePhase=write` | Attachment tests and persistence sample write |
| Same command with `-PpersistencePhase=read` | Read the sample in a new process using the same world |

Dedicated servers require Minecraft EULA acceptance. The bootstrap and runtime use separate packages to satisfy Forge discovery and Java module boundaries; both are delivered in one installation file. Internal IDs, packages, and cache paths retain `reforged` for compatibility.

[Stabilization record](docs/STABILIZATION.md) tracks implemented fixes, test evidence, historical Issues, feature work, and remaining acceptance criteria. Next work covers real client/server fixtures, attachment lifecycle cases, JAR transaction boundaries, embedded dependency version selection, and performance baselines.

## Origin and license

Continued from [ReForged](https://github.com/Arc-Stuido/ReForged), with source history and existing copyright notices retained. Licensed under [LGPL-2.1-only](LICENSE). ReForge is an independent experimental project.
