# Backroom Roguelike Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 将 MDK 示例工程实现为具备区块房间、进入/撤离、独立计时、扫描定位和多人局重置能力的 Minecraft 1.20.1 Forge 后室模组。

**Architecture:** 以 mod id `backroomroguelike` 注册 Java 逻辑与物品，唯一支持的后室维度和资源使用 `backroomroguelike` 命名空间，不提供旧 KubeJS 维度桥接。正式维度使用注册到 Forge 的自定义 `ChunkGenerator`，在 `fillFromNoise` 生成回调中按区块坐标从递归池选模板并直接写入 `ChunkAccess`；玩家每次开局分配新的远距离起始区块，避免异步生成回调依赖会话 `SavedData`。玩家本局状态存档，HUD/交互通过 Forge 网络同步。

**Tech Stack:** Minecraft 1.20.1、Forge 47.4.6、Java 17、Gradle ForgeGradle、Minecraft NBT、Python 3 标准库（仅用于可重复生成房间资源）。

**Spec:** [2026-10-23-backroom-roguelike-design.md](../specs/2026-10-23-backroom-roguelike-design.md)

## Global Constraints

- 只修改 `E:\Server_mod\BackroomRoguelike` 主项目，不改参考 KubeJS 项目；KubeJS 仅供只读参考。
- 唯一支持的后室维度 ID 为 `backroomroguelike:backroom`；不注册 `backroom:backroom` 兼容维度或实现旧传送桥接。
- 目标版本为 Minecraft 1.20.1 / Forge 47.4.6 / Java 17；不增加未经授权的第三方依赖。
- mod id 为 `backroomroguelike`，Java 根包为 `com.servermod.backroomroguelike`，项目包路径中不出现 `example`。
- 运行时每个房间 NBT 的 `size` 为 `[16,16,16]`；房间主体为 15×15，y=0 地基为 16×16，从区块原点放置且不做偏移；房间门在主体中轴，地板贴合地表；正式维度最小 Y（默认 -64）只保留一层基岩边界。
- 39 个 lock 陶片的原始物品 ID 和方块实体 NBT不修改；保留 `create:placard`、门和 Lootr 箱子内容。
- 以 `structures_old/` 为房间模板源并保留 `rainbow:*` 方块 ID；运行时结构不得残留 Jigsaw 控制块。
- 房间池按目录递归：目录内直接 `.nbt` 与直接子目录等概率；选中子目录后递归至 `.nbt`。池根 18 个普通房，`password/` 子池有 39 个密码房和 1 个撤离结构；不保证出口生成。
- 玩家计时按现实经过时间持续，离线不停；重连时发现已到期则掉落背包并结束本局。后室内重复使用进入道具忽略。
- 撤离读条持续 10 秒；玩家受伤或离开撤离方块 4 格范围时中断。进入道具和扫描道具均可重复使用且不消耗。
- 本次不新增或运行自动化测试；完成阶段性资源检查和 `compileJava` / `processResources` 编译检查。

## Review Focus

- 房间资源缺失、损坏或引用未注册的模组方块时，加载应给出明确资源 ID 和错误，不应导致整服崩溃。
- 区块首次生成直接从自定义生成器获得唯一房间；重启读取原版保存的区块；新开局使用全新区域，不清理或叠加旧布局。
- 离线超时、重连和玩家死亡/撤离的状态切换必须只掉落一次，且多人中的其他玩家运行状态不被清除。
- 扫描目标预测必须使用和生成完全一致的池树/坐标哈希算法，且搜索不得加载目标区块。
- 15×15 房间主体及 16×16 地基修整后，四向通道、居中门、箱子、Create 展示框和陶片 NBT 必须保留，所有放置坐标限制在目标区块单元。

---

## File Map

| 文件/目录 | 职责 |
| --- | --- |
| `gradle.properties`、`build.gradle`、`settings.gradle` | mod identity、artifact/group 和 Gradle 项目名 |
| `src/main/java/com/servermod/backroomroguelike/BackroomRoguelike.java` | Forge mod 入口、事件与注册表挂接 |
| `.../registry/ModBlocks.java`、`ModItems.java`、`ModCreativeTabs.java` | 撤离方块、进入/扫描道具和创造栏注册 |
| `.../world/RoomPool.java`、`RoomSelector.java`、`RoomChunkGenerator.java` | 递归结构池、确定性选择和区块生成回调放置 |
| `.../world/RoomGeneratorRegistry.java` | 自定义 ChunkGenerator codec 注册 |
| `.../world/RoomPoolReloadListener.java` | 资源重载时解析 NBT 资源并发布不可变池快照 |
| `.../session/BackroomSessionData.java`、`PlayerRunState.java`、`BackroomRunManager.java` | 区域分配序号、玩家隔离区、个人计时与结算 |
| `.../block/ExtractionPointBlock.java` | 10 秒撤离交互与中断判定 |
| `.../item/BackroomEntryItem.java`、`RoomScannerItem.java` | 进入后室与查找出口 |
| `.../api/RoomLocator.java`、`RoomLocation.java` | 可供其他模组调用的房间定位接口和结果类型 |
| `.../network/BackroomNetwork.java`、`TimerSyncS2C.java` | HUD 倒计时同步 |
| `.../client/BackroomHudOverlay.java` | 屏幕上方居中毫秒倒计时 |
| `src/main/resources/data/backroomroguelike/` | 正式维度、维度类型、生物群系、战利品/房间 NBT |
| `src/main/resources/assets/backroomroguelike/` | 撤离点方块状态/模型、物品模型和语言 |
| `tools/build_room_templates.py` | 使用 Python 标准库构建 18 个普通房、39 个密码房和撤离结构 NBT |
| `structures/` | 20 个原始大房 NBT；`structures/locks/` 保存从只读 KubeJS 参考数据复制的 39 个原始锁 NBT，全部只读保留 |

## Task 1: 替换 MDK 示例身份并搭好 Forge 注册入口

**Files:**
- Modify: `gradle.properties`
- Modify: `settings.gradle`
- Modify: `src/main/resources/META-INF/mods.toml`
- Modify: `src/main/resources/pack.mcmeta`
- Delete/replace: `src/main/java/com/example/examplemod/ExampleMod.java`
- Delete/replace: `src/main/java/com/example/examplemod/Config.java`
- Create: `src/main/java/com/servermod/backroomroguelike/BackroomRoguelike.java`
- Create: `src/main/java/com/servermod/backroomroguelike/registry/ModBlocks.java`
- Create: `src/main/java/com/servermod/backroomroguelike/registry/ModItems.java`
- Create: `src/main/java/com/servermod/backroomroguelike/registry/ModCreativeTabs.java`

**Interfaces:**
- Produces: `BackroomRoguelike.MOD_ID = "backroomroguelike"` and mod-bus registrations for blocks, items, and the mod creative tab.

- [x] Change mod id, name, group and artifact identity to `backroomroguelike`, `Backroom Roguelike`, and `com.servermod.backroomroguelike`; remove example config/log/demo content.
- [x] Add minimal registries for `extraction_point`, reusable `backroom_entry`, and reusable `room_scanner`; expose the three entries under one mod creative tab.
- [x] Set the extraction block to use a vanilla block model/texture and provide item models and Chinese display names without introducing new external assets or libraries.
- [x] Declare runtime mod dependencies required by preserved structure IDs (KubeJS, Create, Lootr, Caverns & Chasms, Oreganized, Atmospheric, NetherExp, Windswept) as required metadata; do not add their artifacts to Gradle dependencies.
- [x] Run `.\gradlew.bat compileJava processResources -x test`; expect successful Java compilation and resource expansion.

## Task 2: Convert and generate the final room NBT set

**Files:**
- Create: `tools/build_room_templates.py`
- Create: `structures/locks/lock_1.nbt` through `structures/locks/lock_39.nbt` as unchanged copies from the read-only reference input.
- Create: 18 ordinary room NBTs under `src/main/resources/data/backroomroguelike/structures/rooms/`
- Create: 39 password room NBTs and one extraction NBT under `src/main/resources/data/backroomroguelike/structures/rooms/password/`
- Input only: `structures_old/*.nbt` and `structures/locks/lock_*.nbt`; leave the external KubeJS project untouched.

**Interfaces:**
- Produces: repeatable CLI `python tools/build_room_templates.py --input structures_old --locks structures/locks --output src/main/resources/data/backroomroguelike/structures/rooms`.
- Generated password names map deterministically from `lock_1.nbt` through `lock_39.nbt` to `password/lock_01.nbt` through `password/lock_39.nbt`.

- [x] Implement gzip/plain NBT read/write with Python standard-library `gzip` and `struct`; do not install packages.
- [x] Transform the 18 selected non-password room templates from `[17,16,17]` into a 15×15 room body with a 16×16 y=0 foundation and `[16,16,16]` NBT size; preserve source `rainbow:*` registry IDs.
- [x] Remove the source templates' 10-layer `minecraft:bedrock` filler (y=6–15); the canonical dimension supplies the single boundary layer at minY, leaving the 16-block room height open.
- [x] For all 39 password outputs, clone the base `password_room.nbt`, merge the matching lock blocks and block-entity NBT at the socket transform, and preserve each placard's `Item` payload byte-for-byte at the NBT value level.
- [x] Resolve every jigsaw to its unchanged `final_state` and remove the jigsaw block entity; trim the outer room ring while retaining the additional y=0 foundation row/column so the 15×15 room body has a 16×16 foundation and is placed from the chunk origin without offset.
- [x] Build `password/extraction_room.nbt` from the approved room basis with the registered extraction-point block; retain 16×16×16 dimensions and keep the exit structure as a direct candidate in the password folder.
- [x] Keep original input files and copied lock inputs untouched; generated NBT must retain Create placard, vanilla/mod doors, Lootr chest NBT and all 39 pottery item IDs.
- [x] Inspect generated resource counts, dimensions, palette IDs and the 39 pottery payloads before integrating them with runtime placement.

## Task 3: Add canonical dimension data

**Files:**
- Create: `src/main/resources/data/backroomroguelike/dimension/backroom.json`
- Create: `src/main/resources/data/backroomroguelike/dimension_type/backroom.json`
- Create: `src/main/resources/data/backroomroguelike/worldgen/biome/backroom.json`
- Modify: `src/main/resources/pack.mcmeta`

**Interfaces:**
- Produces: the sole supported `backroomroguelike:backroom` dimension, with a single bottom bedrock boundary and the registered custom room generator.

- [x] Define the canonical dimension's minY and height for Minecraft 1.20.1, generate exactly one bedrock boundary layer at minY, and reference the custom chunk generator.
- [x] Do not create `backroom:backroom` dimension or staging resources; keep dimension/resource pack versions valid and avoid editing the external KubeJS data pack.
- [x] Inspect the final JSON and namespaced paths; verify the canonical dimension and generator IDs resolve to the registered resources.

## Task 4: Implement the custom chunk generator and recursive room selection

**Files:**
- Create: `src/main/java/com/servermod/backroomroguelike/world/RoomPool.java`
- Create: `src/main/java/com/servermod/backroomroguelike/world/RoomSelector.java`
- Create: `src/main/java/com/servermod/backroomroguelike/world/RoomChunkGenerator.java`
- Create: `src/main/java/com/servermod/backroomroguelike/world/RoomGeneratorRegistry.java`
- Create: `src/main/java/com/servermod/backroomroguelike/world/RoomPoolReloadListener.java`
- Create: `src/main/java/com/servermod/backroomroguelike/session/BackroomSessionData.java`

**Interfaces:**
- `RoomPool.load(ResourceManager manager, ResourceLocation root)` creates a folder tree from `data/backroomroguelike/structures/rooms/` NBT resource paths.
- `RoomPool.select(RandomSource random)` returns one concrete room `ResourceLocation`; each folder level samples its immediate `.nbt` resources and child folders uniformly.
- `RoomSelector.select(int chunkX, int chunkZ)` returns a stable template ID and orientation for a room cell; the coordinate hash is shared by generation and location prediction.
- `RoomChunkGenerator.fillFromNoise(...)` fills the one-layer bedrock boundary at the dimension's minimum Y, reads the generated `WORLD_SURFACE_WG` height before placing a room, then aligns the template's floor to that surface and writes its blocks/block entities directly into `ChunkAccess`, bounded to one 16×16 cell.
- `BackroomSessionData` persists a monotonically increasing region allocation index; each `PlayerRunState` stores its newly assigned distant region origin.

- [x] Register a custom ChunkGenerator codec and reference it from the canonical dimension's generator JSON; retain the existing dimension height and generate exactly one bedrock boundary layer at the dimension's minimum Y (default -64).
- [x] Build and atomically publish an immutable resource folder tree on server resource reload; treat each immediate NBT file and child folder as equally likely, recursively selecting until an NBT is reached. Generation workers only read this immutable snapshot.
- [x] Follow StructRealm's generation principle: override `fillFromNoise(...)`, select by canonical chunk X/Z, generate the single bedrock layer, read `Heightmap.Types.WORLD_SURFACE_WG` before writing the room, align the template's bottom floor layer to that computed surface, and write block states/block entities directly to `ChunkAccess`. Do not use a fixed room Y or trigger initial placement from `ChunkEvent.Load`/per-block Forge events.
- [x] Assign a fresh, distant starting chunk origin to every new player run and persist the allocation index in overworld SavedData. Room selection is coordinate-deterministic because the generation callback does not receive active session SavedData; unique origins provide a new area without reusing old chunks.
- [x] Existing generated chunks remain as saved by vanilla; a later run uses a new region rather than rewriting old chunks. Keep all templates bounded to their 16×16 cells and calculate player feet position from the generated room surface.
- [x] Inspect codec/resource registration and pool traversal with actual resource paths; verify the root 18:1 candidate shape and 40 files in the password folder, and ensure reload never mutates a pool snapshot read by generation workers.

## Task 5: Implement player runs, protections, extraction, and item entry

**Files:**
- Create: `src/main/java/com/servermod/backroomroguelike/session/PlayerRunState.java`
- Create: `src/main/java/com/servermod/backroomroguelike/session/BackroomRunManager.java`
- Create: `src/main/java/com/servermod/backroomroguelike/item/BackroomEntryItem.java`
- Create: `src/main/java/com/servermod/backroomroguelike/block/ExtractionPointBlock.java`
- Modify: `src/main/java/com/servermod/backroomroguelike/registry/ModBlocks.java`
- Modify: `src/main/java/com/servermod/backroomroguelike/registry/ModItems.java`
- Modify: `src/main/java/com/servermod/backroomroguelike/BackroomRoguelike.java`

**Interfaces:**
- `PlayerRunState` stores player UUID, entry timestamp in epoch milliseconds, assigned region, and extraction-progress state.
- `BackroomRunManager.enter(ServerPlayer player)` starts a run only when the player is outside `backroomroguelike:backroom`; it is a no-op inside the canonical dimension.
- `BackroomRunManager.startExtraction(ServerPlayer player, BlockPos beacon)` begins a 200-tick extraction if within 4 blocks.
- `BackroomRunManager.settle(ServerPlayer player, SettlementReason reason)` is idempotent, performs forced inventory drops only for death/timeout, and preserves inventory on successful extraction.

- [x] Implement the non-consuming entry item; right-click server-side starts a player run, directly teleports to the canonical dimension, and sets non-creative players to Adventure mode.
- [x] After the assigned target chunk generates, place the player's feet one block above the computed room-floor surface; do not hardcode a dimension Y coordinate.
- [x] Persist per-player entry timestamps; use wall-clock time so offline time continues. On login/re-entry, settle overdue runs once before allowing continued exploration.
- [x] Add 10-second extraction state; interrupt if damage is received, the player changes dimension, or distance to the extraction block exceeds four blocks.
- [x] On successful extraction, teleport to the overworld safe point, restore Survival mode for non-creative players, and keep carried items.
- [x] On in-dimension death or timeout, spawn each carried stack at the player's location, clear original inventory slots, and end the run before vanilla death handling to prevent duplicate drops.
- [x] Track canonical-dimension players; one player's exit settles only that player's run. Every later entry receives a fresh region from the persisted allocator; other players' generated regions are never reset or rewritten.
- [x] Cancel player block placement/breaking, mob griefing, liquid flow/bucket use and explosion/fire block mutation in the canonical dimension while preserving door/chest use and combat.
- [x] Ignore entry-item use when already in the canonical dimension; do not reset timestamp or assigned region.
- [x] Inspect event registration sides so server-only code does not load client classes on a dedicated server.

## Task 6: Implement timer HUD, scanner item, and public locator API

**Files:**
- Create: `src/main/java/com/servermod/backroomroguelike/network/BackroomNetwork.java`
- Create: `src/main/java/com/servermod/backroomroguelike/network/TimerSyncS2C.java`
- Create: `src/main/java/com/servermod/backroomroguelike/client/BackroomHudOverlay.java`
- Create: `src/main/java/com/servermod/backroomroguelike/api/RoomLocation.java`
- Create: `src/main/java/com/servermod/backroomroguelike/api/RoomLocator.java`
- Create: `src/main/java/com/servermod/backroomroguelike/item/RoomScannerItem.java`
- Modify: `src/main/java/com/servermod/backroomroguelike/registry/ModItems.java`
- Modify: `src/main/java/com/servermod/backroomroguelike/BackroomRoguelike.java`

**Interfaces:**
- `RoomLocator.findNearest(ServerLevel level, BlockPos origin, ResourceLocation roomId, int maxChunkRadius, Consumer<Optional<RoomLocation>> callback)` searches incrementally on the server thread without loading target chunks.
- `RoomLocation` exposes `roomId`, `chunkPos`, `origin`, and squared distance from the request origin.
- The scanner uses target room ID `backroomroguelike:rooms/password/extraction_room`, radius 128 chunks, and displays coordinates/distance in chat.

- [x] Register a Forge SimpleChannel with a versioned timer packet containing server remaining milliseconds; send to the entering player and update while they remain in the canonical dimension.
- [x] Draw a centered top-of-screen HUD timer from the latest server value, formatted `mm:ss.SSS`; hide it outside Backroom and after settlement.
- [x] Implement the generic locator by enumerating chunk coordinates nearest-first up to the supplied radius and using the same coordinate-only selector as `fillFromNoise` without loading chunks; process a bounded number of coordinates per server tick and invoke the callback once.
- [x] Make the scanner reusable, invoke the locator for the extraction room, and report either the nearest predicted location/distance or that none was found in the search radius.
- [x] Ensure all locator callers use the assigned player region so that predictions match later chunk generation exactly.
- [x] Inspect client packet handling and HUD registration so the common mod class has no client-only references.

## Task 7: Integrate runtime resources and compile the completed project

**Files:**
- Modify: `src/main/resources/META-INF/mods.toml`
- Modify: `src/main/resources/pack.mcmeta`
- Create: `src/main/resources/assets/backroomroguelike/blockstates/extraction_point.json`
- Create: `src/main/resources/assets/backroomroguelike/models/block/extraction_point.json`
- Create: `src/main/resources/assets/backroomroguelike/models/item/extraction_point.json`
- Create: `src/main/resources/assets/backroomroguelike/models/item/backroom_entry.json`
- Create: `src/main/resources/assets/backroomroguelike/models/item/room_scanner.json`
- Create: `src/main/resources/assets/backroomroguelike/lang/en_us.json`
- Create: `src/main/resources/assets/backroomroguelike/lang/zh_cn.json`
- Modify: `src/main/java/com/servermod/backroomroguelike/BackroomRoguelike.java`

- [x] Confirm resource namespace, data pack format, Forge dependency declarations, and all NBT resource IDs agree with the renamed mod; ensure no `backroom` staging resources were added.
- [x] Confirm the final jar resource tree contains 18 root ordinary NBTs, 39 password NBTs, one extraction NBT, canonical dimension data, and block/item assets.
- [x] Run `.\gradlew.bat compileJava processResources -x test`; expect successful compilation/resource processing without running tests.
- [x] Review project diff for files outside `E:\Server_mod\BackroomRoguelike`, accidental KubeJS edits, `example` package remnants, and changed source `structures/*.nbt` inputs.

## Scope Coverage Check

- Mod identity and package naming: Task 1.
- 15×15 room body in 16×16×16 NBT templates, 39 lock merges, preserved KubeJS/Create/doors/Lootr: Task 2.
- Canonical dimension and direct Java entry: Tasks 1, 3, and 5.
- Random per-cell rooms, recursive folder pool, multiplayer isolation, fresh distant region for each new run: Task 4.
- Entry item, protection, personal timers, offline timeout, extraction block and settlement: Task 5.
- Timer HUD, extraction scanner, reusable general room locator API: Task 6.
- Mod metadata, assets, and compile-only check: Task 7.
