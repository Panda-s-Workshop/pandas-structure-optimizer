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
