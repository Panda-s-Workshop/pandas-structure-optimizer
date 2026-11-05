# What is verified, and how

Minecraft 26.3, NeoForge 26.3.0.89, Fabric Loader 0.19.5 with Fabric API 0.161.0+26.3, Java 25. Checked 2026-11-05.

**V** verified by the named test, **U** built but not verified, **N** not done.

| System | NeoForge | Fabric | How |
|---|---|---|---|
| Upgraded-template cache | V | V | GameTest `template_cache_roundtrip`: a template at the current data version and one saved at an older version load the same through the cache as through vanilla. JUnit `CacheKeyTest`, `CacheIndexTest`. A second server session loads every template from the disk cache with no data-fixer upgrades, on both loaders |
| Background cache builder | V | U | GameTest `cache_builder_fills` |
| Separate template loads | V | U | JUnit `SingleFlightTest`; GameTest `template_parallel_loads` (16 threads asking for one template: one load, one object) |
| Shared structure-start results | V | V | GameTest `start_cache_copy` (copies have equal NBT and their own references; failures are kept); world parity below |
| Starts computed ahead | U | U | The starts it computes are vanilla's (world parity below). JUnit `RoundRobinTest` for the batch schedule. Its use rate with a moving player is not measured yet |
| Template prefetch | U | U | Runs before each start computed ahead; its effect is not measured yet |
| Height cache | V | U | GameTest `height_cache_equivalence`: 10,000 columns of a real overworld noise generator, every heightmap type, cached answers equal uncached ones |
| Spawn checks skip unfinished chunks | V | U | GameTest `spawn_probe_skips` |
| `/structopt stats` | V | U | GameTest `stats_command` |
| Slow-structure warning | V | V | JUnit `SlowStructureRuleTest` |
| Settings | V | U | GameTest `config_defaults` |
| Cache commands | V | U | GameTest `cache_commands` |
| Coordinator | V | U | JUnit `CoordinatorOrderTest` (same result in any order; kept starts never overlap); GameTest `coordinator_no_overlap` on a real overworld generator |
| Same world, mod off and on | V | V | World parity, below |
| Dedicated server | V | V | Every parity run is a dedicated server; the mod logs no warning or error |

## World parity

A dedicated server generates a 48×48-chunk square on seed 20261105 with the mod off and on, waits until every chunk in it is
full, and saves. Then every structure start saved in the square is compared: id, chunk, box and every piece's NBT, plus every
structure reference. Test mods: Seafaring, Epic Dungeons, ATi Structures, Grim Kingdoms, DDD and Repurposed Structures.

- Two runs with the mod off are identical, so the comparison is deterministic.
- Mod off against mod on, with a cold and with a warm template cache (NeoForge): 0 differences in 54 starts and 2036
  references.
- With C2ME, mod off against mod on (NeoForge): 0 differences.
- Fabric, mod off against mod on: 0 differences in 51 starts and 2018 references.
- In the runs with the mod on, every candidate start of the square is also computed ahead. So the world gets starts both as
  computed by the chunk worker and as copies of starts computed ahead.

## Not verified yet

- Speed in a large pack (template work in a second session, long structure steps, server lag warnings) and the use rate of
  starts computed ahead with a player flying.
- C2ME on Fabric.
- The height cache on generators other than vanilla's noise generator. None is on the allow list.
