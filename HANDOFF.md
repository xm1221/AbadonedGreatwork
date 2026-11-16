# AbadonedGreatwork 换手文档（2026-11-16）

> 本文件用于新会话快速接手当前工程进度。核心代码逻辑、注册、配方与手册文本均已编写并编译通过；**主要待办是重做 `塑界卫道石`（`ambit_switch`）与 `道引标`（`ambit_anchor`）的贴图和方块模型，以及入游戏实机验证。**

---

## 1. 环境与验证命令

- **工程根目录**：`c:\Users\Administrator\Desktop\BigPack\AbadonedGreatwork`
- **目标版本**：Minecraft `1.20.1` / Architectury（Common + Fabric + Forge）/ HexCasting `0.11.4`
- **JDK 21**：`C:\Program Files\Java\jdk-21.0.10`
- **快速校验命令**（PowerShell）：
  ```powershell
  python scripts/validate_truth_crystals.py
  $env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.10"
  .\gradlew.bat :common:classes :fabric:classes :forge:classes :common:processResources :fabric:processResources :forge:processResources --console=plain
  ```
- **最近一次验证结果**（写本文档前已通过）：
  - `python scripts/validate_truth_crystals.py`：退出码 `0`（`zh_cn.json` 与 `en_us.json` 均为 `181` 条键）。
  - 全量 `153` 个资源 JSON 解析通过。
  - Gradle `:common:classes :fabric:classes :forge:classes` + `processResources`：`BUILD SUCCESSFUL`。
  - **注意**：以上全部更改**尚未在 Minecraft 客户端内做过实机启动测试**。

---

## 2. 本阶段已完成的工作与文件索引

### 2.1 分合石与无线媒质波体系调整（已编过）
- 取消不同促动石媒质波在分合石处合流，改回不同促动石相遇即触发 `MishapConflictingImpetus` 事故；`WaveReceiverBlockEntity` 保留法术环闭合回自身校验。
- 方块正式定名（见 `lang/zh_cn.json` & `lang/en_us.json`）：
  - `abadoned_greatwork:wave_transmitter` → **灾厄发信器**（`Illager Wave Transmitter`）
  - `abadoned_greatwork:wave_receiver` → **掠夺者促动石**（`Pillager Impetus`）
  - `abadoned_greatwork:wave_repeater` → **媒质波中继器**（`Media Wave Repeater`）
  - `abadoned_greatwork:media_pool` → **媒质池**（`Media Pool`）
- 新增标签与配方：
  - `common/src/main/resources/data/abadoned_greatwork/tags/entity_types/illagers.json`
  - `common/src/main/resources/data/abadoned_greatwork/tags/entity_types/raid_captains.json`
  - `common/src/main/resources/data/abadoned_greatwork/recipes/media_pool.json`（媒质之瓶 + 淬灵晶碎片 + 石板）
  - `common/src/main/resources/data/abadoned_greatwork/recipes/wave_repeater.json`（山羊角 + 石板 + 充能紫水晶 + 淬灵晶碎片）
  - `common/src/main/resources/data/abadoned_greatwork/recipes/brainsweep/wave_transmitter.json`（`hexcasting:quenched_allay` + `#abadoned_greatwork:raid_captains`）
  - `common/src/main/resources/data/abadoned_greatwork/recipes/brainsweep/wave_receiver.json`（`hexcasting:impetus/empty` + `#abadoned_greatwork:illagers`）
- Mixin（已在 `abadoned_greatwork-common.mixins.json` 注册）：
  - `BrainsweepRecipeMixin.java`：拦截 `wave_transmitter` 剥离配方，校验实体为 `Raider` 且 `isPatrolLeader()` 或头戴灾厄旗帜（已确认 `abadoned_greatwork-common.refmap.json` 生成正确 Intermediary 映射）。
  - `BrainsweepProcessorMixin.java`：客户端 Patchouli 页面将 `wave_transmitter` 示例实体渲染为头戴灾厄旗帜的掠夺者并显示“袭击小队长”提示（已在 `common/build.gradle.kts` 加入 `modCompileOnly(libs.patchouli.fabric)`）。
- Patchouli 手册（`common/src/main/resources/assets/hexcasting/patchouli_books/thehexbook/en_us/entries/greatwork/`）：
  - 更新 `confluence_directrix.json` 对应文本（`hexcasting.page.confluence_directrix.2`）为撞波事故。
  - 新增 `wireless_media_wave.json`（《无石板媒质波》，`sortnum: 5`）。
  - 新增 `media_pool.json`（《媒质池》，`sortnum: 6`）。
  - `plug_slate` 因存在未知 bug 暂未写入手册。

### 2.2 塑界卫道石（`ambit_switch`）& 道引标（`ambit_anchor`）
- **核心设计**：不增加法阵总体范围，**体积总量严格守恒**；通过世界中的道引标围合多边形取代原法阵死板的方形 `bounds`。
- **几何与预算规则**：
  - 平面（XZ）按同频道锚点顺位 `1→2→...→1` 使用**奇偶规则（Even-Odd）**闭合成面：支持任意 C 形凹角，错序自相交（如 `⋈`）不报事故，按实际命中整数格数 `S` 计面积。
  - 垂直（Y）取同频锚点 `[minY, maxY]`，高度 `H = maxY - minY + 1`。
  - 体积预算 = 原法阵 `CircleExecutionState.bounds` 的盒体积（`dx × dy × dz`）。
  - 波流经 `塑界卫道石` 时检验：
    - `0` 根道引标：清空自定义域，还原为原版默认方盒 `bounds`；
    - `1~2` 根：触发 `MishapNotEnoughAmbitAnchors`；
    - `S × H > 原预算`：扫描线提前中止并触发 `MishapAmbitBudgetExceeded`；
    - 合法：将多边形数据写入该支波 `CastingImage.userData`（支持分合石平行分支各自持域），由 `AmbitReshapeHook` 监听 `CastingEnvironment.addCreateEventListener` 替换 `CircleCastEnv` 原本的 `bounds`（保留施法者贴身与哨卫范围）。
- **交互设计**：
  - `塑界卫道石`（`ambit_switch`）：
    - 继承 `BlockCircleComponent`，保留六向 `FACING` 属性，六面均可出入波（`normalDir = Direction.UP`），兼容直接从 `hexcasting:directrix/empty` 剥离原位转化。
    - 手持`道引标`物品右键：将本块频道写入手中道引标物品的 `BlockEntityTag`。
    - 空手右键（潜行反向）：切换 `0..15` 频道；已接入 IoticBlocks（`Abadoned_greatworkIotaHolders.java`）支持焦点写入任意 Iota。
  - `道引标`（`ambit_anchor`）：
    - 放置时继承物品 NBT 中的绑定频道并自动置入该频道末尾序号；
    - 空手右键：切换频道并置入新频道末尾；
    - **潜行 + 空手右键**：在本频道内将顺位向后挪动 1 位（末尾回绕首位），并沿新轮廓喷射粒子。
- **相关代码与数据路径**：
  - `common/src/main/kotlin/cn/xm1221/abadoned_greatwork/casting/circles/AmbitNetwork.kt`
  - `common/src/main/kotlin/cn/xm1221/abadoned_greatwork/casting/circles/MishapAmbitReshape.kt`
  - `common/src/main/kotlin/cn/xm1221/abadoned_greatwork/block/AmbitSwitchBlock.kt`
  - `common/src/main/kotlin/cn/xm1221/abadoned_greatwork/block/entity/AmbitSwitchBlockEntity.kt`
  - `common/src/main/kotlin/cn/xm1221/abadoned_greatwork/block/AmbitAnchorBlock.kt`
  - `common/src/main/kotlin/cn/xm1221/abadoned_greatwork/block/entity/AmbitAnchorBlockEntity.kt`
  - 无线收信剥离旧 `TAG_ACTIVE_AMBIT` 防跨环偷渡：`WaveReceiverBlockEntity.kt`
  - 注册入口：`Abadoned_greatwork.kt`、`Abadoned_greatworkBlocks.kt`、`Abadoned_greatworkBlockEntities.kt`、`Abadoned_greatworkItems.kt`、`Abadoned_greatworkCreativeTabs.kt`、`Abadoned_greatworkIotaHolders.java`、`Abadoned_greatworkClient.kt`
  - 配方：`recipes/ambit_anchor.json`（紫水晶碎片 + 铁锭 + 石板 + 深板岩/深板岩圆石 → `4` 根）、`recipes/brainsweep/ambit_switch.json`（`hexcasting:directrix/empty` + `minecraft:vindicator`）
  - 手册：`assets/hexcasting/patchouli_books/thehexbook/en_us/entries/greatwork/vindicator_ambit.json`（`sortnum: 7`）

---

## 3. 新会话优先待办（重点！）

### 3.1 重做美术资产：贴图与模型（用户明确认为当前版本太粗糙）
请先参考本模组已有精细方块模型与贴图的风格（如 `models/block/wave_receiver.json`、`wave_repeater.json`、`multi_directrix_p.json`、`media_pool.json` 及对应贴图），重新制作以下占位/粗制资产：

- **塑界卫道石（`ambit_switch`）**
  - 当前文件：
    - `common/src/main/resources/assets/abadoned_greatwork/blockstates/ambit_switch.json`
    - `common/src/main/resources/assets/abadoned_greatwork/models/block/ambit_switch.json`
    - `common/src/main/resources/assets/abadoned_greatwork/models/block/ambit_switch_energized.json`
    - `common/src/main/resources/assets/abadoned_greatwork/models/item/ambit_switch.json`
    - `common/src/main/resources/assets/abadoned_greatwork/textures/block/ambit_switch_front.png`（及 `_energized`、`side`、`_side_energized`）
  - **视觉目标**：沿用板岩法术环方砖底材；不要只画简单纯 `cube` 格子，应结合镂空凹槽/斜切斧刃折角铁质镶边与中心菱形紫晶核（`energized` 时菱形刻槽与棱线亮起亮紫/冷银光），可参考 Blockbench 多元素立体结构。
- **道引标（`ambit_anchor`）**
  - 当前文件：
    - `common/src/main/resources/assets/abadoned_greatwork/blockstates/ambit_anchor.json`
    - `common/src/main/resources/assets/abadoned_greatwork/models/block/ambit_anchor.json`
    - `common/src/main/resources/assets/abadoned_greatwork/models/item/ambit_anchor.json`
    - `common/src/main/resources/assets/abadoned_greatwork/textures/block/ambit_anchor.png`
  - **视觉目标**：纤细不挡视线的界桩（深板岩底座 + 铁箍卡扣 + 顶部斧刃状紫水晶尖锥）；如有大改高度/轮廓，同步检查 `AmbitAnchorBlock.SHAPE` 碰撞箱及 `models/item/ambit_anchor.json` 的 GUI 展示角度（`display`）。

### 3.2 建议新会话顺手检查的实机项
1. 启动客户端测试 `ambit_switch` 由 `hexcasting:directrix/empty` 剥离卫道士转化后的 `FACING` 朝向与石板接驳效果。
2. 测试 `ambit_anchor` 顺位编号、潜行调序、粒子轮廓、奇偶填充（C 形凹角 / `⋈` 交叉）、高度跨度 `[minY, maxY]` 与超预算触发事故。
3. 核对无线媒质波与媒质池相关配方及 Patchouli 手册渲染（含袭击小队长展示与工具提示）。
4. （可选遗留）排查并修复 `plug_slate` 的既有 bug 后再补入手册。
