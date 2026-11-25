Backroom Roguelike
==================

Minecraft 1.20.1 Forge 模组。玩家进入后室后有 20 分钟探索时间，房间按区块生成，撤离点随机出现在密码房子池中。

安装要求
--------

- Minecraft 1.20.1 与 Forge 47.4.x。
- 模组依赖：KubeJS、Create、Lootr、Caverns & Chasms、Oreganized、Atmospheric、NetherExp、Windswept。
- 房间模板保留 rainbow:* KubeJS 方块 ID。安装的 KubeJS 数据包必须注册这些方块；本模组不负责创建这些 KubeJS 方块，也不接管旧 KubeJS 维度的进入/离开流程。

游玩
----

1. 使用创造栏中的“后室通行证”进入，或执行 `/give @s backroomroguelike:backroom_entry`。
2. 每位玩家有独立的 20 分钟倒计时；断线期间继续计时。每次新开局会分配新的远距离区域。
3. 使用“电台呼叫器”或执行 `/give @s backroomroguelike:radio_caller`，呼叫撤离电台获取附近 128 区块搜索范围内最近的撤离点坐标。区域不保证有撤离点。
4. 找到撤离点方块（无线电撤离电台）后右键，保持在 4 格内读条 10 秒；启动时会播放无线电静电音。受到伤害或离开范围会中断。
5. 成功撤离会保留物品；在后室死亡或时间耗尽会掉落背包物品并结束本局。离线期间到期的玩家重连时结算。

结构说明
--------

- 每个区块生成一个房间单元，NBT 尺寸为 16×16×16，主体为 15×15，底部地基为 16×16；房间地板跟随区块表面。
- 39 个密码房变体位于独立随机子池。陶片与 Create 展示框数据按源 NBT 保留。
- 世界最小高度 -64 处有一层基岩边界。已生成区块保留在存档中；再次进入会使用新区域。
- 初始房间由 `data/backroomroguelike/structures/rooms/index.json` 指定；模组默认使用 `none.nbt`，新局入口区块固定生成该结构。
- 自定义房间数据包可在 `data/<命名空间>/structures/rooms/` 放置 `index.json` 和 `.nbt`。示例：
  `{"initial_structure":"start.nbt","priority":1}`。优先级高于 0 时，仅使用该数据包提供的房间结构，模组内置房间池不参与随机选择；初始结构路径相对于 `structures/rooms/`。

构建
----

在项目根目录运行 `gradlew.bat build`，产物位于 `build/libs/`。

素材
----

- 撤离点方块模型与贴图沿用 Rainbow Power 的炮兵电台素材；启动音效为 OpenGameArt "Frequency Static Sound Effects"（CC0）。
- 电台呼叫器图标为手绘 16×16 像素素材，配色与撤离点电台一致。
