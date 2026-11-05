# What is verified, and how

Minecraft 26.3, NeoForge 26.3.0.89, Fabric Loader 0.19.5 with Fabric API 0.161.0+26.3, Java 25. Checked 2026-11-05.

**V** verified by the named test, **U** built but not verified, **N** not done.

| System | NeoForge | Fabric | How |
|---|---|---|---|
| Upgraded-template cache | V | V | GameTest `template_cache_roundtrip`: a template at the current data version and one saved at an older version load the same through the cache as through vanilla. JUnit `CacheKeyTest`, `CacheIndexTest`. A second server session loads every template from the disk cache with no data-fixer upgrades, on both loaders |
| Background cache builder | V | U | GameTest `cache_builder_fills` (its upgrades are not counted as the game's); JUnit `SpareCpuTest` (it waits while the machine is busy, goes on when the load drops or is unknown) |
| Separate template loads | V | U | JUnit `SingleFlightTest`; GameTest `template_parallel_loads` (16 threads asking for one template: one load, one object) |
| Shared structure-start results | V | V | GameTest `start_cache_copy` (copies have equal NBT and their own references; failures are kept); world parity below |
| Starts computed ahead | V | U | The starts it computes are vanilla's (world parity below). JUnit `RoundRobinTest` (batch schedule), `BandTest` (which chunks: in every direction and from several places in the player's chunk, exactly the ones a straight flight needs, checked against a step-by-step flight); GameTest `lookahead_moving_player` (a player moved by its client, with no server-side velocity, gets a scan) |
| Vanilla-terrain check | V | U | GameTest `vanilla_terrain_check`: the vanilla overworld and a flat world pass; a mod density function buried in a sum behind a registry reference, a mod subclass of the noise generator and a mod biome source are each caught, and turn off starts computed ahead and the height cache |
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
- With UltraTerraForged and its world preset, mod off against mod off: 0 differences; mod off against mod on: 0 differences.
  UltraTerraForged's terrain answers heights differently off its own threads, so on its worlds the mod computes no starts
  ahead and keeps no height cache (with them, 20 starts differed by a block or two in height).

## Speed in a large pack

A NeoForge pack of about 340 mods with C2ME, many structure mods and UltraTerraForged, on a CPU held at 1.8 GHz. The client starts in a
fresh copy of one world (same seed, no chunks generated), waits 60 s, flies a straight line east at 11 blocks a second for 300 s and quits;
nobody touches the game. A JFR recording gives Minecraft's own chunk-generation and structure timings. Same world and path each time.
The world is UltraTerraForged's, so starts are not computed ahead and the height cache is off (see above); the gains come from
the template cache and the shared start results.

| 300 s flight | mod off | mod on, empty cache | mod on, second session |
|---|---|---|---|
| `structure_starts` step time | 159.8 s | 110.8 s | 52.5 s |
| per generated chunk | 22.3 ms | 16.0 ms | 7.7 ms |
| structure CPU on the chunk workers, first 2 minutes | 99 s | 52 s | 24 s |
| steps of 1 s or more / 5 s or more | 32 / 5 | 13 / 2 | 0 / 0 |
| slowest step | 11.2 s | 12.7 s | 0.73 s |
| server lag warnings during the flight | 1 (6 s) | 0 | 0 |
| data-fixer share of structure time on the chunk workers | 88% | 84% | 0% |

In the second session 26 templates were upgraded as the game asked for them, against 2,277 read from the cache. With an empty
cache the slowest steps are the first loads of large structures (an ocean monument, 12.6 s), as with the mod off; the cache
builder fills the cache in the background on spare CPU, so they are paid once.

## Not verified yet

- C2ME on Fabric.
- The height cache on generators other than vanilla's noise generator. None is on the allow list.
