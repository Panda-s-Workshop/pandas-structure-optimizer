# How Panda's Structure Optimizer works

Minecraft 26.3, NeoForge 26.3.0.89 and Fabric (Fabric API 0.161.0). All code is in `common/`; the loader folders only
forward events.

Every speed-up reuses or precomputes something vanilla computes anyway, so a seed generates the same structures with the
mod as without it. The one exception is the coordinator, which is off unless a pack switches it on for new worlds.

## Where structure time goes

In a large pack most structure time goes into three things:

- **Upgrading templates.** Every structure piece is an `.nbt` template. Older templates go through the game's data fixers
  each time they load, and some mods add another fixer pass on every template. This happens again in every session.
- **Duplicate starts.** Some mods work out the same structure start again (on their own threads), and the chunk worker then
  does it once more.
- **Terrain height.** Structures ask for the terrain height of the same columns many times.

## The systems

### Upgraded-template cache

- The first time a template from a mod or data pack loads, its NBT after every data fixer is written to
  `<game folder>/.cache/pandas_structure_optimizer/templates/<pack fingerprint>/`. Later loads read that copy and skip the fixers.
- An entry's key is the template id plus the SHA-256 of the file, so a changed file is a new entry.
- The folder's name is a fingerprint of the game's data version and every loaded mod id and version. Adding, removing or
  updating any mod starts a fresh folder. A folder unused for more than two sessions is deleted.
- Files are found exactly as vanilla finds them; the cache only sees their bytes. Templates saved by structure blocks in the
  world, and test templates, are never cached.
- Entries are written to a temporary file and then moved into place. An unreadable entry is deleted and the template loads
  the vanilla way. A template that fails to load is never cached.
- The cache is capped at `templateCacheMaxGigabytes`; past it, the least recently used entries are removed at start.

### Background cache builder

At server start one low-priority thread walks every template the pack can place: the template pools first, then every
other `structure/*.nbt` in the data packs. It upgrades each one that isn't cached yet, writes only to disk, and pauses
while structures are being generated. It stops at shutdown and carries on at the next start.

### Separate template loads

Vanilla loads a template inside a `ConcurrentHashMap.computeIfAbsent` call, which holds that part of the map while the
template upgrades. The mod loads first, outside the map, with one shared load per template id. Threads asking for
different templates no longer wait for each other. The template map itself is never replaced, so mods that swap it keep
working.

### Shared structure-start results

- The result of each structure attempt inside `ChunkGenerator.tryGenerateStructure` is kept for the session. A success is
  kept as the start's NBT, exactly as a chunk saves it; a failure is kept as a marker.
- A second request with the same inputs gets a new start loaded from that NBT, so two chunks never share pieces or
  reference counts.
- The key is every input that can change the result: dimension, structure, chunk, seed, reference count, generator,
  random state and height range.
- The size is capped at `startCacheMaxMegabytes`; the least recently used results go first.

### Starts computed ahead of the players

- Each player is looked at once every `lookaheadCycleTicks` ticks. The players are split round-robin into that many
  batches, one batch per tick (50 players and 10 ticks: 5 players a tick), so the work is spread out instead of landing on
  one tick.
- For a moving player, the mod looks at the band of chunks just past the ones the chunk system already needs. That is the
  view distance plus the radius at which full chunks need structure starts, extended `lookaheadExtraChunks` along the
  direction of travel.
- In that band it finds each structure set's candidate chunks from the seed. A random-spread set has one per spacing
  region. Only candidates that pass the set's own placement check are kept, at most `lookaheadScanCapPerTick` checks per
  tick. Chunks already past the structure step, loaded or saved, are skipped.
- The candidates run on the mod's own low-priority threads, nearest first, with the queue capped at `lookaheadQueue`.
  There the set loop runs exactly as vanilla's: one structure, or weighted picks with a retry after each failure.
- Results only reach the world through the shared start results, when the chunk worker asks for them. The mod never
  schedules chunks or takes tickets.
- Before a start is computed, the templates of its start pool are loaded on the same thread (`lookaheadPrefetchTemplates`).
- The scan stops when no player has moved for 5 seconds.

### Height cache

Terrain-height answers of the vanilla noise generator (`getBaseHeight`) are kept in a fixed-size cache per dimension,
keyed by column and heightmap type. It is on only for generator types in `heightCacheGenerators`. These must return the
same answer for the same column; only `minecraft:noise` is listed.

### Spawn checks skip unfinished chunks

While the level's custom spawners run (cats, patrols, phantoms, the wandering trader, village sieges and any mod's),
"is this chunk loaded" means "is it a full, loaded chunk". A spawner then skips a chunk that is still generating instead
of making the server thread wait for it, and tries again on a later tick. Generation is not changed.

### Stats and slow-structure warnings

- `/structopt stats` lists the template cache, shared loads, the builder, kept starts, starts computed ahead (used and
  not used), height-cache hits, spawn checks skipped, and the ten most expensive structures this session. For each
  structure it shows attempts, how often it placed, mean and longest time, and what took most of its time.
- A dedicated server logs a one-line summary every `statsLogEveryMinutes` minutes.
- A structure with at least 20 attempts that places in under 5% of them and costs more than 10 s of CPU an hour gets one
  log line per session (`warningsSlowStructures`). The line names its set and where its time went.

### Structure coordinator (opt-in)

- With `coordinatorNewWorlds` on, a newly created world records that the coordinator is on (in
  `data/pandas_structure_optimizer_coordinator.json`). An existing world never switches.
- Two possible starts from different structure sets conflict when their centres are closer than the sum of their radii.
  The radius is a jigsaw structure's horizontal reach clamped to 16-128 blocks, 48 blocks for other types, or a value from
  `coordinatorFootprintOverrides`. A larger `coordinatorMinDistances` value between two priorities also counts.
- The higher-ranked start keeps its spot: the higher priority (`coordinatorPriorities`, by structure id, `#tag`, set id or
  namespace), then the rarer set (larger spacing), then a seed-mixed hash.
- The other start never runs its generation.
- The decision reads only possible starts worked out from the seed, plus their biome at the candidate point. So it is the
  same in any generation order and with any number of threads.

## Settings

`config/pandas_structure_optimizer.json`, the same file on both loaders, read when the server starts. A missing key is
written back with its default. Not synced to clients.

| Key | Default | Meaning |
|---|---|---|
| `templateCacheEnabled` | true | the upgraded-template cache |
| `templateCacheMaxGigabytes` | 4 | its disk cap |
| `templateCacheBuilder` | true | the background cache builder |
| `templateCacheBuilderThreads` | 1 | its threads |
| `templateLoadsSeparate` | true | one shared load per template, outside the map |
| `startCacheEnabled` | true | shared structure-start results |
| `startCacheMaxMegabytes` | 64 | their memory cap |
| `lookaheadEnabled` | true | starts computed ahead (needs the start results) |
| `lookaheadThreads` | 0 | 0 means a quarter of the cores, at least 1 |
| `lookaheadExtraChunks` | 8 | how far past the needed chunks, along the direction of travel |
| `lookaheadCycleTicks` | 10 | each player once per this many ticks, in as many batches |
| `lookaheadScanCapPerTick` | 256 | candidate checks per tick |
| `lookaheadQueue` | 256 | most candidates waiting |
| `lookaheadPrefetchTemplates` | true | load a start's first templates before it is computed |
| `heightCacheEnabled` | true | the height cache |
| `heightCacheEntriesPerDimension` | 262144 | its size |
| `heightCacheGenerators` | `["minecraft:noise"]` | generator types it may serve |
| `spawnProbesSkipUnfinishedChunks` | true | spawn checks skip unfinished chunks |
| `statsLogEveryMinutes` | 5 | periodic summary on dedicated servers (0: off) |
| `warningsSlowStructures` | true | slow-structure warnings |
| `coordinatorNewWorlds` | false | switch the coordinator on for worlds created from now on |
| `coordinatorPriorities` | `{}` | id, `#tag`, set id or namespace to priority |
| `coordinatorFootprintOverrides` | `{}` | structure or set id to radius in blocks |
| `coordinatorMinDistances` | `{}` | `"low,high"` priority pair to minimum distance in blocks |

## Commands

| Command | Permission | What it does |
|---|---|---|
| `/structopt stats` | level 2 | this session's numbers, in chat and in the log |
| `/structopt cache clear` | level 3 | deletes this pack version's template cache at the next start |
| `/structopt cache rebuild` | level 3 | the builder checks every template again, now |
| `/structopt coordinator status` | level 2 | whether the coordinator is on for this world, and its structure sets |
