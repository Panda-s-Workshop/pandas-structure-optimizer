# Panda's Structure Optimizer

[![Build](https://github.com/Panda-s-Workshop/pandas-structure-optimizer/actions/workflows/build.yml/badge.svg)](https://github.com/Panda-s-Workshop/pandas-structure-optimizer/actions/workflows/build.yml)

A Minecraft 26.3 mod for NeoForge (26.3.0.89) and Fabric (Fabric API 0.161.0), Java 25. It runs on the server, which
in single player is the game itself; players joining a server don't need it.
The store page link will go here at release.

Structures generate several times faster in big modpacks, and the world stays the same. Most of the time a pack spends
on structures goes into upgrading structure templates to the current game version, every session again, and into
working out the same structure starts more than once. This mod keeps what it has already worked out and computes the
rest before the chunks are needed. It needs no datapack changes, and it runs with and without C2ME.

Work in progress: nothing is released yet.

## What it does

- Keeps every structure template after its data-fixer upgrade on disk, so later sessions skip the upgrade. A background
  thread fills the cache on spare CPU.
- Loads templates one at a time per template, without blocking threads that want other templates.
- Shares structure-start results, so a start worked out twice (by another mod, or ahead of time) is only computed once.
- Computes structure starts ahead of moving players, spread over ticks, on its own low-priority threads.
- Caches terrain-height answers for the vanilla noise generator.
- Lets spawn checks skip chunks that are still generating instead of waiting for them.
- Shows its numbers: `/structopt stats`, a periodic log line, and a warning for structures that cost a lot and rarely
  place.
- Optional, for new worlds only: a structure coordinator that keeps structures from different mods from overlapping.

With default settings a seed generates the same structures as without the mod; `docs/CONTRACT.md` says how that is
checked. `docs/DESIGN.md` explains each system, the settings in `config/pandas_structure_optimizer.json`, and the
commands.

## Build and run

JDK 25 is pinned in `gradle.properties` (`org.gradle.java.home`). CI overrides it.

```bash
./gradlew build
```

```bash
./gradlew :neoforge:runGameTestServer
```

- `build` writes `neoforge/build/libs/pandas_structure_optimizer-<version>-neoforge.jar` and
  `fabric/build/libs/pandas_structure_optimizer-<version>-fabric.jar`. The Fabric jar needs Fabric API.
- `./gradlew :neoforge:runGameTestServer -PoptTests=mod_loaded,lang_*` runs only the GameTests named.
- The dev servers listen on port 25619.

License: All Rights Reserved.
