# 后室（Backroom）系统分析报告

| 项目 | 内容 |
| --- | --- |
| 分析对象 | `E:\MC_UNSpro\开发环境\Client5.0\versions\1.20.1-Forge_47.4.20\kubejs` 中所有 backroom / backrooms 相关内容 |
| 目标项目 | `E:\Server_mod\BackroomRoguelike`（Forge 1.20.1，后室主题地牢 + 搜打撤） |
| 运行环境 | Minecraft 1.20.1 Forge 47.4.20，KubeJS 2001.6.5，EntityJS 0.6.6，Rhino 2001.2.3 |
| 分析日期 | 2026-10-22 |
| 分析方式 | 静态阅读脚本 + 解析数据包 JSON + 解析结构 NBT（自写 NBT 解析器） |

---

## 0. 结论摘要

1. **系统由三部分组成**：KubeJS 脚本（进入/离开、规则、追猎者、战利品、方块） + 原版数据包（维度、生物群系、Jigsaw 结构与模板池） + 结构 NBT（房间预制件）。没有 Java 代码。
2. **地图是纯原版 Jigsaw 拼图**：16×16 格房间、单方块宽走廊、四向可旋转连接，`spacing=5` 区块随机散布，单个结构最多扩张 7 层、80 格半径。
3. **进入方式是隐藏秘籍**：在生存背包界面依次按 `W W S S A A D D B A B A`，客户端发包，服务端校验后传送到固定 Y 层。
4. **规格中的“20 分钟倒计时 + 撤离点”完全未实现**，当前只有“手动输入秘籍原路返回”，没有时间压力、没有撤离流程、没有局内状态。
5. **存在两处 P0 级缺陷**（按当前配置静态推算）：
   - 传送目标 `Y = -62` 位于平原地形的基岩层（-64~-55），安全点搜索必然失败，进不去后室；
   - 离开/死亡结算调用了未定义的 `BackpackHelper`（对应模组未安装，且 CONST.js 中已注释），回调会在传送前抛异常。
6. **追猎者系统是“可运行的原型”**，但存在聊天栏刷屏、重载后追踪丢失、追猎者脱离追踪后永久残留等问题。
7. **战利品数值明显超标**：普通房 1~3 组、每组 32 个的矿物，池内含钻石/远古残骸/绿宝石；起始房池里“密码房”权重 50%。
8. **“密码房”只有陶片展示位**，39 个 lock 结构刚好对应 39 种陶片，但没有密码校验、开锁或奖励判定逻辑，属于未完成的玩法钩子。

---

## 1. 文件清单

### 1.1 脚本

| 端 | 文件 | 职责 |
| --- | --- | --- |
| client | `client_scripts/backroom/tobackroom.js` | 监听背包界面的按键序列，命中后发送 `pause_menu_code` 网络包 |
| client | `client_scripts/backroom/main.js` | 空入口（仅注释） |
| server | `server_scripts/backroom/NetworkEvents.js` | 接收秘籍包、进/出后室、安全点搜索、掉落结算、死亡结算 |
| server | `server_scripts/backroom/mutant.js` | 追猎者（突变凋灵骷髅）生成、追踪、袭击周期 |
| server | `server_scripts/backroom/loot.js` | 用 LootJS 覆盖 5 个战利品表 |
| server | `server_scripts/backroom/main.js` | 空入口 |
| startup | `startup_scripts/backrooms/item.js` | 注册 3 个后室专用方块 |
| startup | `startup_scripts/backrooms/ForgeEvents.js` | 禁止后室内放置方块、禁止挖掘 |
| startup | `startup_scripts/backrooms/main.js` | 空入口 |

### 1.2 数据包（`kubejs/data/backroom/`）

| 文件 | 职责 |
| --- | --- |
| `dimension/backroom.json` | 维度定义（flat 超平坦生成器） |
| `dimension_type/backroom.json` | 维度类型（无天空光、固定午夜、末地天空） |
| `worldgen/biome/backroom.json` | 空生物群系（无特征、无刷怪） |
| `worldgen/structure/center.json` | Jigsaw 结构定义 |
| `worldgen/structure_set/center_set.json` | 结构散布规则 |
| `worldgen/template_pool/center.json` | 起始房池（4 选 1） |
| `worldgen/template_pool/pool.json` | 通用迷宫房间池（18 个条目） |
| `worldgen/template_pool/password.json` | 密码房侧龛池（39 个 lock） |
| `loot_tables/rooms/*.json` | 5 个战利品表（苹果占位，运行时被 loot.js 覆盖） |
| `structures/*.nbt` | 59 个结构（20 个 17×16×17 大件 + 39 个 1×2×2 lock） |

---

## 2. 总体架构

```
玩家（生存背包界面）
  └─ tobackroom.js  按键序列检测
       └─ sendData('pause_menu_code', {code})
            └─ NetworkEvents.js  dataReceived
                 ├─ 不在后室 → 安全点搜索 → teleportTo(backroom:backroom, y=-62)
                 │                └─ 非创造 → gamemode adventure
                 └─ 已在后室 → 掉落背包/饰品/背包内容 → teleportTo(overworld, 地表)
                                  └─ 非创造 → gamemode survival

后室维度 tick（mutant.js）
  ├─ 每 6000 tick/玩家：生成 1 只追猎者（32~40 格外）
  ├─ 袭击期（6000 tick 周期内前 3600 tick）：每 20 tick 重新寻路
  └─ 和平期：每 600 tick 重新寻路

结构生成（数据包）
  structure center → structure_set center_set → 随机散布
    └─ start_pool backroom:center（起始房 4 选 1）
         └─ 房间四向 jigsaw → backroom:pool（迷宫房间池）循环扩张
              └─ password_room 侧向 jigsaw → backroom:password（39 个陶片龛）
```

---

## 3. 世界生成

### 3.1 维度定义

`data/backroom/dimension/backroom.json`：

- 生成器：`minecraft:flat`
- 层：`minecraft:bedrock` × 10 层（从 `min_y = -64` 起，即 **-64 ~ -55 全是基岩**）
- `features: true`，`lakes: false`
- 生物群系：`backroom:backroom`
- 结构覆盖：仅 `backroom:center_set`

`data/backroom/dimension_type/backroom.json`：

| 参数 | 值 | 影响 |
| --- | --- | --- |
| `min_y` / `height` | -64 / 384 | 可建高度 -64~320 |
| `fixed_time` | 18000 | 永远午夜 |
| `has_skylight` | false | 无天空光 |
| `ambient_light` | 0.0 | 环境光为 0，完全依赖方块光源 |
| `effects` | `minecraft:the_end` | 末地天空/雾 |
| `bed_works` / `respawn_anchor_works` | false / false | 无法设重生点 |
| `has_raids` | false | 无袭击机制 |
| `piglin_safe` | true | — |
| `monster_spawn_light_level` / `block_light_limit` | 0 / 0 | 自然刷怪被压制 |
| `natural` | true | 指南针/床等按自然维度处理 |

### 3.2 Y 轴推算（关键）

- 房间结构 NBT 的局部 `y=0` 是地板层（`rainbow:bedrock_block`）。
- Jigsaw 结构 `start_height.absolute = -53`，即地板世界坐标为 **-53**，玩家站立层为 **-52**。
- 房间内部净高只有 **4 格**（局部 y=1~4），局部 y=5 是带 4 盏海晶灯的屋顶，y=6~15 是纯基岩填充。
- 脚本 `BACKROOM_TARGET_Y = -62`，位于基岩层内部（基岩 -64~-55）。`canStandAt` 要求两个空气 + 一个实体地板，在 -62 永远不成立，按当前配置**进入流程会直接失败并只打印日志**。

> 结论：`-62` 与当前维度/结构配置不匹配，需改为 -52 或改成“先落到地板再向上找空气”的搜索方式。此项必须实机确认（若线上可进入，说明维度 json 曾被其他数据包覆盖）。

### 3.3 生物群系

`data/backroom/worldgen/biome/backroom.json`：

- `features` 为空数组，`carvers` 为空，`spawners` 全空 → 不生成任何自然地形/生物/矿物。
- 有降水、雾色/天光色沿用原版默认值（对后室内体验影响不大，因为无天空光）。
- `structures.structure_set` 为空（结构由维度级 `structure_overrides` 控制）。

### 3.4 结构注册链

1. **结构** `backroom:center`
   - `type: jigsaw`
   - `biomes: [backroom:backroom]`
   - `start_pool: backroom:center`
   - `size: 7`，`max_distance_from_center: 80`
   - `start_height: absolute -53`
   - `step: surface_structures`，`terrain_adaptation: beard_thin`
   - `use_expansion_hack: true`
2. **结构集** `backroom:center_set`
   - `random_spread`，`salt: 729445389`
   - `spread_type: triangular`，`spacing: 5`，`separation: 0`
   - 即平均每 5 区块（80 格）一处，最近可 0 格间隔 → **理论上相邻迷宫可能重叠/拼接**。
3. **模板池**
   - `backroom:center`（起始房）：`advanced_room 1`、`normal_room 1`、`password_room 3`、`hidden_room 1`
   - `backroom:pool`（迷宫）：18 个条目（见 3.6）
   - `backroom:password`（密码龛）：`lock_1 ~ lock_39`，各权重 1
   - 所有池 `fallback: "empty"`，找不到目标池时连接终止（不会报错）。

### 3.5 房间单元规格

所有大件 NBT 都是 **17 × 16 × 17**（16×16 的有效格 + 1 格重叠边界，符合 Jigsaw 拼接约定）。

横截面（以普通房为例）：

```
y=15  基岩填充（2890 格纯 bedrock）
y=6-14 基岩填充
y=5   rainbow:bedrock_block 屋顶 + 4 盏 rainbow:bedrock_sea_lantern（位于四角内缩处）
y=1-4 房间净空（4 格高）
y=0   地板 rainbow:bedrock_block；四边中点放 minecraft:jigsaw（朝外，rollable）
```

平面结构（16×16 有效格）：

```
边界 0/16 为公共走廊（1 格宽） ← 相邻房间在此拼接
内圈 x/z = 1..15 为墙体，内部 13×13 为“金库式”房间
普通/高级/隐藏/密码房在内圈 +z 边中点开门
```

- 房间内部有真实门：普通房 `caverns_and_chasms:waxed_oxidized_copper_door`、高级房 `oreganized:lead_door`、密码房 `minecraft:iron_door`（不可手动开）、隐藏房 `rainbow:fake_block`（无碰撞，**隐形假门**）。
- 密码房除铁门外，在 x=9 处还有一条 1 格宽的侧向开口通向外部走廊和陶片龛（铁门只是装饰/误导）。

### 3.6 结构清单

**17×16×17 大件（20 个）**

| NBT | 类型 | 是否被池引用 | 说明 |
| --- | --- | --- | --- |
| `none.nbt` | 空房间 | ✅ | 全空，仅保留连接口，用于“死胡同/过渡” |
| `center.nbt` | 十字房 | ✅ | 四面贯通，无内墙 |
| `horizontal.nbt` | 直通（东西） | ✅ | 仅 x 轴两侧连通 |
| `vertical.nbt` | 直通（南北） | ✅ | 仅 z 轴两侧连通 |
| `corner_1.nbt` | 转角 | ✅（权重 3） | L 形墙 |
| `corner_4.nbt` | 转角（镜像） | ✅ | L 形墙镜像 |
| `corner_2.nbt` | 转角（死胡同） | ❌ 未使用 | 四个接口全为 `minecraft:empty` |
| `corner_3.nbt` | 转角 | ❌ 未使用 | 与 corner_1 同组，但未进池 |
| `fake_center.nbt` | 假墙十字房 | ✅ | 墙用 `rainbow:fake_block`，可穿行 |
| `fake_horizontal.nbt` | 假墙直通 | ✅ | — |
| `fake_vertical.nbt` | 假墙直通 | ✅ | — |
| `fake_corner_1.nbt` | 假墙转角 | ✅（权重 3） | — |
| `fake_corner_4.nbt` | 假墙转角 | ✅ | — |
| `fake_corner_2/3.nbt` | 假墙转角 | ❌ 未使用 | 接口全 empty |
| `normal_room.nbt` | 普通房 | ✅ | 铜门 + Lootr 箱子，表 `backroom:rooms/normal_room` |
| `advanced_room.nbt` | 高级房 | ✅ | 铅门 + Lootr 箱子，表 `advanced_room` |
| `hidden_room.nbt` | 隐藏房 | ✅ | 假墙假门 + Lootr 箱子，表 `hidden_room` |
| `password_room.nbt` | 密码房 | ✅ | 铁门 + 侧龛 jigsaw（目标池 `backroom:password`，目标名 `backroom:password_lock`）+ Lootr 箱子，表 `password_room` |
| `sweet_roll.nbt` | 机械动力展示房 | ❌ 未使用 | 含大量 Create 机器、农作物、箱子（铁块×64 等），测试遗留 |

**1×2×2 lock 结构（39 个）**：每个由 1 个 jigsaw（`name: backroom:password_lock`、`pool: backroom:empty`、`target: backroom:password_room`）+ 1 个 `rainbow:bedrock_block` 墙 + 1 个 `create:placard`（展示架）组成。39 个 placard 上分别放着**39 种不同的陶片**，与 `loot.js` 里的 `normal_room_items` 陶片列表**一一对应**。这是“密码=某一种陶片”的玩法钩子，但 lock 自身的 `pool` 指向不存在的 `backroom:empty`，连接在这里终止，**没有任何后续逻辑**。

### 3.7 模板池权重

`backroom:pool`（总权重 18）：

| 条目 | 权重 | 占比 |
| --- | --- | --- |
| `none` | 1 | 5.6% |
| `vertical` | 1 | 5.6% |
| `horizontal` | 1 | 5.6% |
| `corner_1` | 3 | 16.7% |
| `corner_4` | 1 | 5.6% |
| `center` | 1 | 5.6% |
| `fake_center` | 1 | 5.6% |
| `fake_horizontal` | 1 | 5.6% |
| `fake_vertical` | 1 | 5.6% |
| `fake_corner_1` | 3 | 16.7% |
| `fake_corner_4` | 1 | 5.6% |
| `advanced_room` | 1 | 5.6% |
| `normal_room` | 1 | 5.6% |
| `hidden_room` | 1 | 5.6% |

`backroom:center`（起始房，总权重 6）：**密码房 50%**，普通/高级/隐藏各 16.7%。密码房是最高价值房间，作为出生房概率过高，属于明显失衡。

### 3.8 连接规则

Java 版 jigsaw 的 NBT 语义（易混淆，先明确）：

- `pool` = **目标模板池**：从这里抽取下一个预制件；
- `name` = 本接口的名字；
- `target` = **目标接口名**：要求候选件里存在同名接口（候选 `name` == 本件 `target`）才能对接。

本数据包的连接：

- 所有房间的 4 个 jigsaw：`name/pool/target` 均为 `backroom:pool`，`joint: rollable`，`final_state: rainbow:bedrock_block` → 任意朝向均可拼接，由原版 Jigsaw 自动旋转。
- 假墙系列 `joint: aligned`，保持朝向一致。
- 密码房侧向 jigsaw：`name: backroom:password_room`、`pool: backroom:password`、`target: backroom:password_lock`、`joint: aligned` → 从 39 个 lock 中抽 1 个（lock 的 `name` 正好是 `password_lock`）。
- lock 的 jigsaw：`name: backroom:password_lock`、`pool: backroom:empty`、`target: backroom:password_room` → **`backroom:empty` 池不存在，链在这里断掉**，只生成一个陶片展示龛。

---

## 4. 进入 / 离开 / 死亡流程

### 4.1 客户端秘籍

`client_scripts/backroom/tobackroom.js`：

- 仅在 `mc.screen` 为 `net.minecraft.client.gui.screens.inventory.InventoryScreen` 时监听。
- 监听 `W/S/A/D/B` 的**按下边沿**（上一 tick 未按下），拼出 `wwssaaddbaba`。
- 命中后 `player.sendData('pause_menu_code', { code: 'wwssaaddbaba' })` 并清空缓冲。
- 离开背包界面/打开其它界面时清空缓冲与按键状态。
- 代码在客户端硬编码，任何玩家都可以输入；服务端只校验字符串常量。

### 4.2 服务端传送

`server_scripts/backroom/NetworkEvents.js`：

```
收到包 & code 匹配
├─ 当前维度 != backroom:backroom
│   ├─ 取 backroom:backroom 维度
│   ├─ 在 (x, -62, z) 固定层半径 16 搜索安全点（空气×2 + 实体地板）
│   ├─ 找到 → teleportTo(维度, 安全点, 保留 yaw/pitch)
│   └─ 非创造 → gamemode adventure
└─ 当前维度 == backroom:backroom
    ├─ 在主世界相同 x/z 附近半径 16 搜索“露天地表”安全点
    ├─ 先掉落并清空：背包 → Curios → Backpacked 内容
    ├─ teleportTo(overworld, 安全点, 保留 yaw/pitch)
    └─ 非创造 → gamemode survival
```

### 4.3 安全点算法

- 后室：`findNearbySafeAtFixedY` 只在该 Y 层做环形扩散（半径 16），**不上下扫描**。
- 主世界：`findNearbySurfaceSafe` 先找“能看到天空”的最高安全 Y，找不到再退化为不限天空的最高安全 Y。
- 判定条件：脚/头为空气、下方非空气且非流体且 `blocksMotion()`。
- 传送位置统一 `+0.5` 对齐方块中心。

### 4.4 逃离结算

`dropAndClearAllBackroomItems(player)` 依次处理：

1. `dropPlayerInventoryExceptEnderChest`：主背包 36 格逐格生成 `ItemEntity`（拾取延迟 20 tick），清空槽位，保留末影箱。
2. `dropAllCurios`：遍历 `player.getAllCurios()` 的所有槽位，掉落并 `setEquippedCurio(..., EMPTY)`。
3. `dropAllBackpackedContents`：调用 `BackpackHelper.getBackpacks(player)` 与 `player['backpacked$GetBackpackInventory'](i)`。

**致命问题**：`BackpackHelper` 在 `server_scripts/CONST.js` 第 11 行已被注释，且整合包未安装 Backpacked 模组。Rhino 中该标识符未定义，函数一进入就抛 `ReferenceError`。因为调用顺序是“背包 → 饰品 → 背包内容”，所以逃离时玩家会**先被清空并掉出全部背包与饰品，然后在传送前中断，人留在后室**；在后室死亡时同样会在死亡事件回调中报错（物品已掉落，其余逻辑不执行）。

### 4.5 死亡结算

`EntityEvents.death('minecraft:player')`：

- 仅当维度为 `backroom:backroom` 时触发 `dropAndClearAllBackroomItems`。
- 目的：把物品“留在后室”，避免死亡后带回主世界。
- 由于先清空再让原版死亡流程掉落，正常情况下不会双倍掉落。
- 未处理：重生点（维度不可设床/锚），玩家死亡后按原版规则回主世界重生点，`/back` 又被禁用（见第 9 节），因此无法回到死亡点。

### 4.6 模式切换的实现风险

`setAdventureIfNotCreative` / `setSurvivalIfNotCreative` 使用 `player.runCommandSilent('execute as <name> run gamemode ...')`。KubeJS 的命令源权限取自玩家，**非 OP 玩家在专用服上会因权限不足静默失败**（`gamemode` 需要权限等级 2）。客户端单人/开放局域网主机会有权限，表现为“能用”，容易掩盖问题。Java 版应直接调用 `player.setGameMode(GameType.ADVENTURE)`。

---

## 5. 玩家约束

`startup_scripts/backrooms/ForgeEvents.js`：

| 事件 | 行为 |
| --- | --- |
| `BlockEvent.EntityPlaceEvent` | 维度为 `backroom:backroom` 时 `setCanceled(true)`，禁止一切实体放置方块（含玩家与非玩家） |
| `PlayerEvent.BreakSpeed` | 维度为 `backroom:backroom` 时 `newSpeed = 0`，挖掘速度为 0 |
| 脚本侧的 `gamemode adventure` | 非创造玩家进入后强制冒险模式 |

问题：

- 维度判断使用 `player.level.name.getString() === "backroom:backroom"`，与项目其它模块统一使用的 `String(player.level.dimension)` 不一致，属于脆弱写法。
- 只禁“放置”和“破坏速度”，没有拦截爆炸、火焰蔓延、实体破坏等其它方块变更途径（当前维度无自然刷怪、无 TNT 来源，风险较低）。
- 冒险模式下仍可交互（开门、按按钮、开箱子），符合搜打撤需要。

---

## 6. 追猎者系统

`server_scripts/backroom/mutant.js`，以 `LevelEvents.tick('backroom:backroom')` 驱动。

### 6.1 参数

| 常量 | 值 | 含义 |
| --- | --- | --- |
| `mutantId` | `mutantmore:mutant_wither_skeleton` | 追猎者实体（突变凋灵骷髅，非规格中的原版僵尸） |
| `spawnInterval` | 6000 tick（5 分钟） | 每个玩家补充追猎者的检查周期 |
| `raidInterval` | 6000 tick | 袭击周期 |
| `raidDuration` | 3600 tick（3 分钟） | 每周期内的高频追击时长（占 60%） |
| `moveInterval` | 20 tick（1 秒） | 袭击期重新寻路间隔 |
| `peaceMoveInterval` | 600 tick（30 秒） | 和平期重新寻路间隔 |
| `spawnMinDistance` / `spawnMaxDistance` | 32 / 40 | 生成环带 |
| `moveSpeed` | 1.2 | 寻路速度倍率 |
| `maxMutantsPerPlayer` | 1 | 每人同时存在上限 |

### 6.2 状态与流程

- `trackedMutantsByPlayer`：内存对象，键为玩家 UUID 字符串，值为 `{ entityUUIDs: [] }`。
- 生成：只在与玩家**同一 Y 层**的环形区域（r=32~40）逐格外圈扫描，要求脚/头空气、下方实体方块；找到后 `setPositionAndRotation` → 播放 `rainbow:voice.warning` → `spawn()` → `moveTo(player, 1.2)`。
- 袭击开始/结束：全维度广播聊栏提示，结束时清空目标并停止导航。
- 无玩家：遍历维度内所有该类型实体全部 `discard()`，清空追踪表。
- 玩家离开维度但在线：其追踪记录会被删除，但**追猎者实体不会清理**，变成永久残留（只有“维度内无任何玩家”时才会全清）。

### 6.3 主要问题

| 级别 | 问题 |
| --- | --- |
| P1 | 每次 AI 更新都调用 `player.tell`，袭击期每个有追猎者的玩家**每秒收到 2 条聊天消息**，属于调试输出遗留；`console.log` 同样每 20 tick 一次 |
| P1 | 追踪表不持久化：脚本重载/服务器重启后表清空，旧追猎者不再受控且不会被回收，同时上限判定从 0 重新开始，实体可累积 |
| P1 | 追猎者只在玩家同层生成：玩家站到非走廊高度（屋顶、掉出地板）时永远找不到生成点 |
| P2 | 生成搜索是 `r × dx × dz` 的环形遍历（约 2.3k 列 × 3 次方块查询），且每 5 分钟对每个玩家执行；建议预计算或限制尝试次数 |
| P2 | 没有击杀掉落、没有经验/奖励、没有随房间深度或撤离剩余时间成长 |
| P2 | 规格要求“每 3 秒向玩家当前坐标移动”，实现是“每 1 秒（袭击）或 30 秒（和平）”，节奏差异大 |

---

## 7. 战利品系统

### 7.1 实现方式

- `data/backroom/loot_tables/rooms/*.json` 全部是 **1 个苹果的占位表**。
- 运行期由 `ServerEvents.genericLootTables` 用 LootJS **覆盖同 ID 表**（`normal_room / advanced_room / hidden_room / password_room`，`red_room` 被注释）。
- 箱子方块是 `lootr:lootr_chest`，Lootr 会给每个玩家独立实例；掉落在 NBT 内已绑定 `LootTable`。
- 附魔书使用 `global.allEnchantments`（启动时从 `ForgeRegistries.ENCHANTMENTS` 导出全部附魔）。

### 7.2 各房间内容

| 表 | 食物池 | 陶片池 | 矿物池 | 附魔书池 | 模板池 | 神器池 |
| --- | --- | --- | --- | --- | --- | --- |
| `normal_room` | 藤椒面条 ×1 | — | 1~3 组，每组 32 | — | — | — |
| `advanced_room` | 藤椒面条 / 橡胶 二选一 | 0~1 | 1~5 组，每组 32 | — | — | — |
| `hidden_room` | — | 1~2 | — | 1~5 本 | 1~2 | — |
| `password_room` | — | 1~2 | — | 1~5 本 | 1~2 | 1 件（固定 1 抽） |
| `red_room` | 已注释 | — | — | — | — | — |

- 矿物池共 19 项（含 amethyst 重复、两个模组的 raw_silver），包含 `ancient_debris`、`diamond`、`emerald`、`quartz`、`lapis_lazuli` 等。
- 神器池 18 项（含重复的 `mysticartifacts:katana`），含 `rainbow:dice / moai_charm / giants_ring / gravity_core / lyre / ancientaegis / power_sword / tyrfing`、`scepterofdominion`、`helldivers:magic_book` 等。
- 模板池 36 项，覆盖原版 + atmospheric + caverns_and_chasms + netherexp + oreganized + rainbow + royalvariations + wetland_whimsy。

### 7.3 问题

| 级别 | 问题 |
| --- | --- |
| P1 | 数值严重超标：普通房就有 32~96 个矿物，高级房最多 160 个，且包含钻石/远古残骸/绿宝石 |
| P1 | 起始房 50% 是密码房，等于一半的局开局直接拿到神器 + 满配附魔书 + 模板 |
| P2 | JSON 占位表只有苹果；若 KubeJS 未加载（如纯数据包环境、KubeJS 报错），箱子退化为苹果 |
| P2 | `red_room` 表存在但结构不存在，`loot.js` 里对应段落被注释，属于残留 |
| P2 | `global.allEnchantments` 含全部模组附魔，附魔书随机范围不可控（可能出现不适用于书的附魔） |
| P2 | 物品列表硬编码在 JS，Java 版迁移应改为纯数据包表 |

---

## 8. 自定义方块

`startup_scripts/backrooms/item.js`，命名空间为 `rainbow:`（并非 `backroom:`）：

| ID | 属性 | 用途 |
| --- | --- | --- |
| `rainbow:fake_block` | `hardness(-1)`、`unbreakable()`、`noDrops()`、`noCollision()`、模型 `minecraft:block/yellow_concrete` | 假墙/隐藏房门，可穿行 |
| `rainbow:bedrock_block` | `hardness(-1)`、`unbreakable()`、`noDrops()`、模型同为黄色混凝土 | 房间外壳、地板、屋顶 |
| `rainbow:bedrock_sea_lantern` | 同上 + `lightLevel(1.0)`（即亮度 15）、模型 `minecraft:block/sea_lantern` | 屋顶光源，每间 4 盏 |

- 三者 `displayName` 都是“后室”，均不可破坏、无掉落。
- `fake_block` 与 `bedrock_block` **共用同一个黄色混凝土模型**，玩家无法通过外观区分真墙/假墙（隐藏房靠“撞墙”发现）。
- 方块 ID 挂在 `rainbow` 命名空间，Java 版迁移时应改为本项目命名空间。

---

## 9. 跨模块耦合

| 位置 | 耦合内容 |
| --- | --- |
| `server_scripts/Plugins.js` | `/back` 在后室内禁用；`/tpa` 只要发起者或目标任一在后室就拒绝，防止绕过“必须秘籍逃出”的规则 |
| `server_scripts/Plugins.js` | 后室死亡不写入 `/back` 返回点 |
| `server_scripts/Plugins.js` | `BACKROOM_DIM_STR` 常量 + `isBackroomDimension()` 统一判断 |
| `startup_scripts/CONST.js` | `global.allEnchantments` 供战利品附魔池使用 |
| `server_scripts/CONST.js` | `ResourceLocation / ItemStack / ItemEntity` 等类加载；`BackpackHelper` 已被注释（见 4.4） |
| `server_scripts/curios_skill_system/Skillwheel.js` | 仅在注释中提及“后室/副本”维度，说明时缓命令必须由玩家自身指令源执行；与后室本体无逻辑耦合 |
| `server_scripts/BlockEvents.js` | 有一段被注释的“营火传送门”旧方案，直接传送到随机坐标，已被秘籍流程取代 |

---

## 10. 与 BackroomRoguelike 规格对照

`E:\Server_mod\BackroomRoguelike\AGENTS.txt` 中的规格 vs 现状：

| # | 规格 | 现状 | 结论 |
| --- | --- | --- | --- |
| 1 | 从数据包指定文件夹读取**所有** `.nbt` 按区块生成，实现规整后室布局 | 使用原版 Jigsaw + 固定模板池，仅引用白名单内 NBT | **实现方式不同**，动态目录读取未实现 |
| 2 | 结构需为 16×16 | NBT 为 17×16×17（16×16 + 1 格重叠边界） | ✅ 等价（Jigsaw 约定） |
| 3 | 玩家冒险模式，不能放置/破坏 | 有（但依赖命令权限，非 OP 可能失效） | ⚠️ 部分实现 |
| 4 | 每 3 秒向玩家坐标移动，默认原版僵尸 | 突变凋灵骷髅；袭击期 1 秒、和平期 30 秒 | ❌ 与规格不符 |
| 5 | 战利品箱子由 NBT 内部箱子实现 | Lootr 箱子 + 数据包战利品表（JS 覆盖） | ✅ 等价（依赖 Lootr） |
| 6 | 玩家最大存在 20 分钟，撤离期间倒计时失效 | **完全没有倒计时与撤离** | ❌ 未实现 |
| 7 | 死亡或超时仍在维度则强制掉落物品 | 仅死亡掉落；无超时逻辑 | ⚠️ 部分实现 |
| 8 | 搜打撤循环（探索、战斗、带出收益） | 只有无限进出 + 一次性手动逃离 | ❌ 核心循环缺失 |

---

## 11. 缺陷与风险清单

### P0（阻塞级）

1. **进入坐标错误**：`BACKROOM_TARGET_Y = -62` 与房间地板 `-53`、基岩层 `-64~-55` 冲突，按配置无法找到安全点。
2. **`BackpackHelper` 未定义**：逃离与后室死亡回调在传送/结算前抛异常，玩家会被清空装备却留在原地。需删除该段或改为 `ModList.isLoaded('backpacked')` 的可选兼容。
3. **`gamemode` 命令权限**：非 OP 玩家在专用服无法切换冒险/生存，规则失效。

### P1（体验/平衡/稳定性）

4. 20 分钟倒计时、撤离点、撤离读条全部缺失。
5. 起始房密码房权重 50%，战利品强度失衡。
6. 战利品数值超标（32/组、最多 5 组、含顶级矿物）。
7. 追猎者追踪表不持久化，重载/重启后孤儿实体残留并重复生成。
8. 追猎者 AI 更新时 `player.tell` 刷屏（袭击期约 2 条/秒/人）。
9. 玩家离开维度后追猎者不清理，只在“维度内无玩家”时全清。
10. 死亡后无重生点管理，`/back` 被禁，玩家无法回到死亡位置。

### P2（一致性与清理）

11. 战利品 JSON 为苹果占位，KubeJS 加载失败时退化。
12. 未使用资源：`corner_2/3`、`fake_corner_2/3`、`sweet_roll.nbt`、`red_room.json`、`loot.js` 注释块、`BlockEvents.js` 注释的营火传送门。
13. 维度判断 `level.name.getString()` 写法不统一。
14. 结构 `spacing: 5` + `separation: 0` + 半径 80，存在相邻迷宫重叠风险。
15. 客户端秘籍无反馈、无冷却、可无限刷；在背包界面含文本框时可能误触。
16. 强依赖 `mutantmore / lootr / oreganized / caverns_and_chasms / create / create:placard`；Java 版需决定软硬依赖。
17. 维度内环境光 0、无天空光，只有每间 4 盏海晶灯，远处走廊极暗（设计取舍，但影响可玩性）。

---

## 12. Java 版迁移建议

### 12.1 系统映射

| 现有 KubeJS 实现 | Java 版建议 |
| --- | --- |
| `dimension / dimension_type / biome / structure / structure_set` JSON | 原样放入 `data/<modid>/`，无需重写 |
| 20 个 17×16×17 结构 + 39 个 lock NBT | 放入 `data/<modid>/structures/`；若坚持“读取文件夹所有 NBT 动态生成”，需自定义 `StructureType`/`StructurePiece` 或启动时扫描注册 `StructureTemplatePool` |
| `rainbow:fake_block / bedrock_block / bedrock_sea_lantern` | `DeferredRegister<Block>`，`noCollission()`、`strength(-1.0F, 3600000.0F)`、`noLootTable()`、`lightLevel(s -> 15)`，改用本项目命名空间 |
| 客户端按键序列 + `sendData` | `KeyMapping` + `SimpleChannel`（C2S 请求包），服务端仍需校验字符串与玩家状态 |
| 安全点搜索 | 复用算法，但后室改为“扫描 -64~320 找地板上方空气”，避免固定 Y 失效 |
| `gamemode` 命令 | `player.setGameMode(GameType.ADVENTURE / SURVIVAL)` |
| `BlockEvent.EntityPlaceEvent` / `PlayerEvent.BreakSpeed` | 同样两个 Forge 事件；建议再加 `PlayerInteractEvent` 的放置拦截兜底 |
| `LevelEvents.tick(维度)` | `TickEvent.LevelTickEvent`（Phase.END）+ 维度判断 |
| `trackedMutantsByPlayer` 内存表 | `SavedData`（`UUID → List<UUID>`），随存档持久化，重载不丢 |
| `player.tell` 调试输出 | `LOGGER.debug`；玩家提示改为 ActionBar 且仅在袭击开始/结束时发 |
| `LootJS` 覆盖战利品 | 纯数据包 `loot_tables` + Lootr 兼容；数值重新平衡 |
| `EntityEvents.death` | `LivingDeathEvent`（玩家）+ 维度判断，掉落逻辑抽成工具类 |
| Curios / Backpacked 掉落 | 软兼容：`ModList.get().isLoaded("curios")` / `"backpacked"`，用 `InterModComms` 或反射封装 |
| 20 分钟倒计时（缺失） | `PlayerTickEvent` + 每玩家 `SavedData`（进入时间、是否撤离中），ActionBar/BossBar 显示；超时强制结算 |

### 12.2 推荐补齐的核心循环

```
进入（消耗/冷却/队伍） → 14 分钟探索（倒计时 HUD）
  → 最后 6 分钟撤离窗口（撤离点激活，倒计时暂停）
    ├─ 成功撤离：保留战利品，回主世界
    ├─ 超时：强制掉落并传送（等同死亡）
    └─ 死亡：掉落留在后室，主世界重生
```

建议新增：

- `BackroomRunData`（SavedData）：每个玩家的 `enterGameTime`、`state`（EXPLORING / EXTRACTING / DEAD）、`extractPoint`。
- `ExtractPointBlock`：右键开始 10 秒读条，期间受伤打断。
- 撤离/超时的统一结算方法 `settleRun(player, success)`，内部只做一次掉落，避免重复清空。
- 追猎者改为“按玩家生成、按 UUID 持久化、死亡给奖励”，并把生成距离/频率与撤离倒计时联动。

### 12.3 建议改造顺序

1. 修 `Y=-62` 与 `BackpackHelper`，保证进得去、出得来（P0）。
2. 用 `setGameMode` 替换命令，保证规则在专用服生效。
3. 迁移数据包（维度/结构/战利品表）到 Java 项目，去掉 KubeJS 依赖。
4. 重写追猎者：持久化 + 去掉刷屏 + 与倒计时联动。
5. 补倒计时 / 撤离 / 超时结算，形成完整搜打撤闭环。
6. 平衡战利品与起始房权重，补充击杀奖励。

---

## 附录 A：关键常量

| 名称 | 值 | 位置 |
| --- | --- | --- |
| 秘籍 | `wwssaaddbaba` | `tobackroom.js` / `NetworkEvents.js` |
| 后室维度 | `backroom:backroom` | 全局 |
| 进入 Y | `-62`（与结构 -53/-52 不符） | `NetworkEvents.js` |
| 后室搜索半径 | 16 | `NetworkEvents.js` |
| 主世界搜索半径 | 16 | `NetworkEvents.js` |
| 追猎者实体 | `mutantmore:mutant_wither_skeleton` | `mutant.js` |
| 生成周期 | 6000 tick | `mutant.js` |
| 袭击周期 / 时长 | 6000 / 3600 tick | `mutant.js` |
| 追猎者上限 | 1 / 玩家 | `mutant.js` |
| 结构起始 Y | `-53` | `worldgen/structure/center.json` |
| 结构散布 | spacing 5 / separation 0 | `worldgen/structure_set/center_set.json` |
| 房间净高 | 4 格（局部 y=1~4） | 结构 NBT |

## 附录 B：结构 NBT 统计

| 项 | 数量 |
| --- | --- |
| 17×16×17 大件 | 20 |
| 1×2×2 lock | 39 |
| 被池引用的结构 | 15 个大件 + 39 个 lock |
| 未使用结构 | `corner_2`、`corner_3`、`fake_corner_2`、`fake_corner_3`、`sweet_roll` |
| 陶片种类（lock 展示） | 39（与战利品陶片池完全一致） |

---

*本报告基于静态分析，涉及实机行为的结论（传送是否成功、命令权限、Jigsaw 拼接效果）建议在测试服逐项验证。*
