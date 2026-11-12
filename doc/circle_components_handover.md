# 法术环件三件套 — 交接文档

> 交接对象：`plug_slate`（插接石板）、`multi_directrix`（多向导向石）、`multi_directrix_p`（多向导向石·核心）。
> 本文对应仓库状态 **`225066b`（2026-11-11）**，工作区干净，三端 `classes` 全绿。
> 上一版（2026-11-09）写的「后两个环件逻辑未实现」**已经作废** —— 三个方块现在都是完整的环件。

---

## 0. 现在的状态

| 方块 | 环件逻辑 | 一句话 |
|---|---|---|
| `abadoned_greatwork:plug_slate` | ✅ 完整 | 「1px 底板 + 1px 竖墙」的 L 形转角件，只传波、不做运算 |
| `abadoned_greatwork:multi_directrix` | ✅ 完整 | 六面各「未连接 / 输入 / 输出」；**分流是真并行**，**合并已接** |
| `abadoned_greatwork:multi_directrix_p` | ✅ 完整 | 同上 + 方块里存一条图案，用它在分流/合并时做运算，并按栈顶 iota 染色 |

**还没做的一件事：跑通过一次完整施法。** 最近一次启动是 11-10 13:14，崩在
`ICircleComponent.sfx` 的空指针上（§7-8），修复已进 HEAD 但**尚未再次进游戏验证**。
下面的 §8 给了手测清单，接手时先按它跑一遍。

---

## 1. 环境与构建

MC **1.20.1** / Architectury（common + fabric + forge）/ 包 `cn.xm1221.abadoned_greatwork` / mod id `abadoned_greatwork`。
产物 **Java 17**，但 **Gradle 必须跑在 JDK 21 上**。

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.10"     # 不设这个，buildSrc 解析 Loom 依赖直接失败
.\gradlew.bat :common:classes :fabric:classes :forge:classes --console=plain
```

| 项 | 版本 | 备注 |
|---|---|---|
| Gradle | 8.14 | Loom 1.11 要求 |
| Architectury Loom | 1.11.454 | 旧版 remap Kotlin 2.2 模组会报 `cannot write metadata for future compiler versions` |
| Kotlin | 2.2.21 | 跟 Hex 0.11.4 的元数据版本对齐 |
| Hex Casting | **0.11.4** | 本轮目标版本 |
| IoticBlocks | **1.0.2** | `_p` 的图案写入口；**硬前置** |
| fabric-loader / fabric-api | 0.19.2 / 0.92.8+1.20.1 | 跟 FLK 1.13.7 配套 |
| fabric-language-kotlin / KFF | 1.13.7+kotlin.2.2.21 / 4.12.0 | 运行时 stdlib 必须和编译器同版 |
| inline | **1.20.1-1.2.2** | Hex 0.11.4 的硬前置，见 §7-9 |

**不要跑 `:common:build`**：它挂在 `:common:javadoc`，`common/src/main/java/cn/xm1221/abadoned_greatwork/entity/EyeOfWaypoint.java:45`
有中文注释，GBK 下解码失败报 `unmappable character`。这是既有问题，与环件无关。

运行日志：`fabric/run/logs/latest.log`、崩溃报告在 `fabric/run/crash-reports/`。

---

## 2. 三个方块一览

注册在 `registry/Abadoned_greatworkBlocks.kt`，物品走 `registerBlockItem`，创造栏在 `Abadoned_greatworkCreativeTabs.kt`，
战利品表 `data/abadoned_greatwork/loot_tables/blocks/*.json`（掉落自身），三个都设了 `pushReaction(BLOCK)`（活塞推不动）。

| id | 方块状态数 | blockstate 条目 | 属性 | 渲染层 |
|---|---|---|---|---|
| `plug_slate` | 4 facing × 2 half × 2 energized = **16** | **16**（`energized` 两档指向同一模型，**不会发光**） | `noOcclusion`、`strength(2.0, 6.0)`、DEEPSLATE | solid |
| `multi_directrix` | 3⁶ × 2 = **1458** | **36** multipart（6 面 × 3 档 × 2 激活） | 整格实心、`strength(3.0, 6.0)`、DEEPSLATE | solid |
| `multi_directrix_p` | 3⁶ × 2 = **1458** | **13** multipart（本体 + 6 面 × out/none） | `noOcclusion` + 方块实体、`strength(3.0, 6.0)`、DEEPSLATE | **cutout** |

**只有 `_p` 需要 cutout**：它把框架内侧面的 UV 映射到贴图的全透明区域，留在 solid 层会画成黑块；
`plug_slate` 和 `multi_directrix` 的贴图全不透明，加 cutout 纯掉性能。

译名（`lang/zh_cn.json`）：插接石板 / 多向导向石 / 多向导向石·核心。
术语沿用 HexCasting 官方中文：directrix = 导向石、impetus = 促动石、slate = 石板、media = 媒质。

---

## 3. HexCasting 0.11.4 环系统要点（改造前必读）

环件判定：`state.getBlock() instanceof ICircleComponent`，**没有 tag、没有白名单**。

```java
ControlFlow acceptControlFlow(CastingImage, CircleCastEnv, Direction enterDir, BlockPos, BlockState, ServerLevel);
boolean canEnterFromDirection(Direction enterDir, BlockPos, BlockState, ServerLevel);
EnumSet<Direction> possibleExitDirections(BlockPos, BlockState, Level);
void startEnergized / boolean isEnergized / void endEnergized;
default Direction normalDir(...);  default float particleHeight(...);
static void sfx(BlockPos, BlockState, Level, BlockEntityAbstractImpetus, boolean success);
```

`BlockCircleComponent` 提供 `ENERGIZED` 属性、比较器输出 15、`startEnergized/isEnergized/endEnergized`，
并留下 `normalDir` / `particleHeight` 两个抽象方法。**子类的 `createBlockStateDefinition` 必须先 `super`。**

**建环**（`CircleExecutionState.createNew`）：从 `impetus.relative(FACING)` 起 DFS 洪泛，每格扩散
`possibleExitDirections`，进之前先过 `canEnterFromDirection`，必须能走回促动石，超 `maxSpellCircleLength()` 失败。

**每 tick**（`CircleExecutionState.tick`）：

1. 走到非环件 → 失败音效 + 停机
2. `startEnergized` → `reachedPositions.add(currentPos)` → `reachedSlate += 1`
3. `acceptControlFlow(...)`
4. 促动石没了 → 停
5. `Stop()` → 停机（**错误必须由 `acceptControlFlow` 自己报**，引擎不会补）
6. `Continue(update, exits)` → 逐个问下一格的 `canEnterFromDirection`：
   **0 个** → 失败音效 + `postNoExits` + 停；**≥2 个** → `many_exits` 显示 + 停；**恰好 1 个** → 走过去，
   `currentImage = update.withOverriddenUsedOps(0)`

「去路过多」是 `postDisplay` 一条显示，不是 mishap：

```java
impetus.postDisplay(Component.translatable("hexcasting.tooltip.circle.many_exits",
    Component.literal(pos.toShortString()).withStyle(ChatFormatting.RED)), new ItemStack(Items.COMPASS));
```

节流：`getTickSpeed() = max(2, 10 - (reachedSlate - 1) / 3)`（0.11.4 起吃 `reachedSlate`，不再是 `reachedPositions.size()`）。

**0.11.4 相对 0.11.3 的破坏性改动**（我们的分流全靠这套 API）：

- `knownPositions` 没了，并进 `reachedPositions`；后者从 `List` 变 `HashSet`
- 新增 `reachedSlate: Long`、`greaterCorner` / `lesserCorner`，protected 构造器变 **11 参**
- `ICircleComponent.sfx` 在成功分支里**不判空**就读 `impetus.getExecutionState().reachedSlate`（§7-8）

---

## 4. `plug_slate`（转角件）

### 4.1 状态与放置

```kotlin
FACING : DirectionProperty = HORIZONTAL_FACING   // 竖墙贴住的那一面
HALF   : EnumProperty<Half>                       // bottom=贴地 / top=贴天花板
ENERGIZED : BooleanProperty                       // 父类提供
```

- 点**侧面** → `facing = clicked.opposite`；点**顶/底** → `facing = context.horizontalDirection`
- `half = if (clicked == DOWN) TOP else BOTTOM`

### 4.2 形状：全部 1px，没有整格兜底

板厚 1px（0.0625），因为是「同一条薄板折了 90°」，底板与竖墙同厚：底板 `(0,0,0)-(16,1,16)`，
竖墙 1px 宽、随 `facing` 转；`half=top` 整体绕 X 翻 180°（模型与 `VoxelShape` 都是）。
`getShape` 的兜底是 `SHAPES[half]?.get(facing) ?: PLATES.getValue(half)` —— **兜底只给底板**，
碰撞箱不可能退化成整格。改厚度时**模型和 8 个 `VoxelShape` 必须同时改**。

### 4.3 blockstate 旋转

16 个变体，`facing` 映射（基模型是"西墙"）：`west` 0（省略）/ `north` 90 / `east` 180 / `south` 270，
`half=top` 再加 `"x": 180`。旋转顺序是先 x 后 y（`BlockModelRotation = Ry·Rx`），两者正交，8 种形态够用。

### 4.4 环件逻辑：两片并集 + 折线歧义报错

```kotlin
possibleExitDirections = 全 6 向
canEnterFromDirection  = true                     // 任何方向都能进
acceptControlFlow      = 来向平行于折线 ? 报「去路过多」+ Stop : Continue(全 6 向 - 来路)
```

**为什么出口是 6 个**：单块石板只有一片，出口只能取法线以外；这块是两片，两片各自禁掉自己暴露面的法线后取并集，正好全通。

**为什么入口恒 true**（踩坑记，别改回去）：`enterDir != normal.opposite || enterDir != face.opposite`
**恒为 true** —— 一个方向不可能同时等于两个互不相同的方向。改成 `&&` 才是错的（会把转角石板变成只能从下方进）。
根因是上游只给方向、**不给带高**：波从东边朝墙来时，可能走在横片上边缘（合法），也可能垂直穿过竖片暴露面（不合法），
`enterDir` 区分不了，只能取宽。真正的收窄由引擎做：它拿候选出口逐格再问下一格，**两个邻格都能接**时才报「去路过多」。

**折线轴** = 水平方向上垂直于 `FACING` 的轴（`facing=west` → Z 轴 → 拦 N/S）。开关：
`const val REPORT_FOLD_LINE_AMBIGUITY = true`（false = 退化成万能接头）。

| `enterDir`（前进方向） | 上一格在哪 | 结果 |
|---|---|---|
| NORTH / SOUTH | 南 / 北 | **平行折线 → 去路过多** |
| EAST / WEST / UP / DOWN | 墙那侧 / 东边 / 下方 / 上方 | 通过 |

**读法：这块只能「转」（水平 ↔ 竖直），不能当直板沿着墙走。**

### 4.5 粒子

`normalDir = if (half == BOTTOM) UP else DOWN`（只喂粒子），`particleHeight = 0.5f - 15f/16f = -0.4375`。
带符号偏移让贴地/贴天花板两种情况都落在底板内侧面上，与 HexCasting 石板同值。

---

## 5. `multi_directrix`（分流 / 合并）

### 5.1 端口三档与渲染

六个面各一个 `PortMode`（属性名就是 `north/east/south/west/up/down`），序列化名 **`none` / `in` / `out`**（进 blockstate 的 `when`，别改）：

| 档位 | 贴图 | 说明 |
|---|---|---|
| `none` 未连接 | `_0` / `_0_energized` | 新放置的默认值，既不接也不放 |
| `in` 输入 | `_2` / `_2_energized` | 波从这一面进来 |
| `out` 输出 | `_1` / `_1_energized` | 波从这一面出去 |

- 潜行右键点**被点中的那一面**（用 `hit.direction`，不用算视线），`none → in → out → none` 循环，
  音高 0.8 / 1.4 / 1.0
- 波经过时 `energized = true`，六面一起换 `_energized`
- `rotate` / `mirror` 必须把六个属性一起换位，否则结构方块一转端口就指错

**为什么必须有「未连接」这一档**：没有它，没被用到的面也是 `in`，「这个块有几个输入」根本数不出来，
合并就没法判断到齐没有。有了它，**`in` 面个数 = 精确的期望到达数**。

**1458 个状态不写 1458 个模型**：`multipart` + 一个朝北面片，6 张图，靠 `x`/`y` 旋转摆到六个面，
共 36 条。旋转表（**每一条都必须带 `uvlock: true`**，六张图没有任何旋转/镜像对称性，否则侧面图案会躺倒）：

| face | 旋转 | face | 旋转 |
|---|---|---|---|
| north | 无 | west | `y: 270` |
| east | `y: 90` | up | `x: 270` |
| south | `y: 180` | down | `x: 90` |

推导依据：MC 的 `BlockModelRotation` 是 `Ry(-y)·Rx(-x)`，把法线 `(0,0,-1)` 代进去 → `y=90` 得 `+X`、`x=90` 得 `-Y`、`x=270` 得 `+Y`，与 vanilla `glow_lichen` 一致。

### 5.2 引擎为什么挡路

`tick` 的出口循环逐个问「下一格肯不肯接」，**第二个能接就报 `many_exits` 并停机**。
所以分流器不能把多个出口交出去 —— 其余的波必须自己另起一条。

### 5.3 分流（真并行）

`acceptControlFlow` 第一步永远是**按「下一格当拍真的肯接」过滤 `out` 面**（不过滤会把波塞进当拍就被拒的方向，那条分支白死）。然后：

| viable 数量 | 做法 |
|---|---|
| 0 | `Continue(imageIn, 空)` → 引擎报无路可走 |
| 1 | 直通 / 合并（见 5.4） |
| **≥2** | **第一个**交回引擎当主波，**其余每个出口各 fork 一条分支波**挂到促动石上 |

引擎只看到一条出口，`many_exits` 永远不会触发。镜像复制见 `CastingImage.forkImage()`：栈共享
（`CastingImage` 不可变），只有会被就地改写的 `userData` 深拷贝，再 `withOverriddenUsedOps(0)` 对齐引擎推主波时的算子归零。

拿不到 `ImpetusBranchHost`（不是 HexCasting 的促动石）时**退回**「老实交出全部出口」，让引擎自己报 `many_exits` —— 宁可报错也不静默丢路。

### 5.4 合并

三个卡点：

| 卡点 | 解法 |
|---|---|
| 引擎没有「等待」态：波到一格必须当拍决定走还是死 | 先到的波 `Stop()` 自己（不报错、格子保持点亮），镜像留在 `MergeBuffer` |
| 「到齐了没有」 | §5.1 的「未连接」档让 `in` 面个数 = 期望到达数 |
| 生命周期 | 改成「**一条活波都没有**才算结束」（否则 `Stop` 的波会被立刻收掉） |

可行 `out` 只有 1 个 **且** `in` 面 ≥ 2 → 登记自己；凑到最后一条时由**它**带着 `CircleImageMerge.merge(...)` 的结果继续走
（**不多造波**）；登记表随后清空。

**凑不齐的兜底（栅栏）**：支路半路死了就永远到不齐，所以「全环一条活波都没有了」时由促动石把到手的凑合放行
（`MergeBuffer.flushToBranch()`，在 `pos.relative(outDir)` **外面一格**造新波 —— 从本格出发会立刻再次登记自己，死循环）。

**合并数据语义（咩咩拍板）**：栈**按到达顺序**从下往上摞（`ListIota` 约定表尾 = 栈顶）；
**渡鸦之思后到覆盖先到**，最终取最后一个到达的那份（存在 `image.userData` 的 `hexcasting:ravenmind` 键下，
`HexAPI.RAVENMIND_USERDATA`，读写见 `OpPushLocal` / `OpPeekLocal`）。必须 `copy()` `userData` —— 它会被 VM 就地改写。

### 5.5 促动石混入层（`ImpetusBranchMixin`，4 条注入）

分支波存在**促动石**上而不是分流器上：这样分流器被拆掉不影响已经分出去的波，也不需要给方块加 `BlockEntity`。

| 目标 | 时机 | 干什么 |
|---|---|---|
| `tickExecution` | `RETURN` | 推进分支（走快照）、栅栏兜底、收尾熄灯、续调度 |
| `startExecution` | `HEAD` | 开新一次施法前清掉上一次的残党（正在跑时不动） |
| `saveModData` / `loadModData` | `TAIL` | 分支列表存盘/读盘；读盘时世界可能没就绪，先存 NBT，第一次 tick 再反序列化 |

`tickExecution` 尾部那四步：

| 步 | 干什么 |
|---|---|
| ① | 逐条 `tick` 分支（**走快照**，新分支下一 tick 才动，否则 CME）；期间临时挂「代表波」防 §7-8 的空指针 |
| ② 栅栏 | 一条活波都没有了、但登记表非空 → 凑合放行 |
| ③ 收尾 | 彻底没波 → 补一次 `endExecution` 熄灯（幂等）+ 清表 |
| ④ 续调度 | 主波死了但分支还活着 → **先重新点亮促动石**，再 `scheduleTick` |

两个踩到才知道的坑：

- `BlockAbstractImpetus.tick` 是 `if (... && pState.getValue(ENERGIZED)) tile.tickExecution();`
  —— **促动石不亮就根本不推进**。主波死时上游 `endExecution()` 会把促动石一起熄灭，所以续调度之前必须先 `startEnergized`。
- 熄灯不能只指望上游：栅栏放行出来的新波是主波死后才动的，它点亮的格子没人管，收尾那步得自己再扫一遍。

**不需要额外收尾**：分支与主波**共用同一个 `reachedPositions` 集合**（`ForkedExecutionState.fork` 里共享），
主波结束时顺手把分支点亮的格子全熄掉。各拿一份就会留下一堆永远亮着的方块。

### 5.6 已知取舍

- **分支比主波快一拍**：fork 发生在主波 `tick` 里，分支当拍就被推进一格，主波等下次调度。相位差恒定 1 tick、不累积。
- `getTickSpeed()` 吃共享的 `reachedSlate`，并行分支多时整环会一起变慢。
- **登记表里攒的是已经 `Stop()` 掉的波，没有活波可以存盘** → 施法中途存档，这部分会丢（分支本身存盘，不会坏档）。
- 施法中拆促动石：上游 `onRemove` 里的 `endExecution()` 因 `executionState == null` 直接返回，分支不会被清，那些格子会一直亮着。
- 不做超时兜底：没有促动石的闭环支路在接入前就已经是坏环（上游同样永远转），加超时只会误伤正常长环。

---

## 6. `multi_directrix_p`（可编程分流器）

`_p` = 普通分流器 + 「跑一条写在方块里的图案」。**贴图命名和普通件不一样**：

| 档位 | 贴图 | 渲染 |
|---|---|---|
| 输入 | `multi_directrix_p` | 骨架本体，不贴附加层 |
| 输出 | `multi_directrix_p_1`（**纯灰阶**） | 本体 + 面片附加层，`tintindex 0` 染色 |
| 关闭 | `multi_directrix_p_2`（自带暗紫） | 本体 + 面片附加层，不染色 |

放置默认仍是六面 `none`（关闭）—— 与普通件一致，这样「有几个输入面」才数得出来。

### 6.1 「附加层」怎么落地

骨架的 8 个角块每个都同时贴着三个面，**一整块几何没法表达「北面输入、东面输出」**。
所以附加层落成**每个面一片 16×16 附加面片**：`multi_directrix_p_face_out` / `_face_none`，
由 multipart 按面状态挂到对应朝向，`y`/`x` + `uvlock` 复用同一张模型（旋转表与本体一致）。
面片贴 `z = 0.01`（略内缩）—— 平齐会与角块的 `z = 0` 面 z-fighting。

### 6.2 图案怎么存 / 怎么写

- `MultiDirectrixPBlockEntity` 继承上游 `HexBlockEntity`（存盘 + `getUpdateTag` 同步白送），存 `pattern: HexPattern?` 与 `tint: Int`。
- 实现 `ADIotaHolder`，**只收 `PatternIota`**（写数字/列表没东西可执行，`writeIota` 返回 `false`）。
- 写入口交给 **IoticBlocks**：`Abadoned_greatworkIotaHolders.init()` 里
  `IoticBlocksAPI.INSTANCE.registerIotaHolderProvider(id("multi_directrix_p"), ...)`，provider 直接返回本方块实体。
  那段注册写成 **Java**，因为包名 `gay.object.ioticblocks.api` 里的 `object` 是 Kotlin 关键字。
- 方块要 `EntityBlock` + `getRenderShape() = MODEL`（`Block` 不声明 `newBlockEntity`；`EntityBlock` 默认 `INVISIBLE`）。

### 6.3 染色

按**栈顶 iota 的类型**，直接借 Hex 自己的类型配色表 `IotaType.color()`（就是施法 GUI 堆栈列表上那些颜色），不另造映射。
服务端在 `setTint()` 里落进方块实体并只在变化时 `sync()`；客户端用 Architectury 的
`ColorHandlerRegistry.registerBlockColors` 读它，乘到 `_p_1` 上。空栈给白色（= 灰阶原样）。

### 6.4 时序（`acceptControlFlow`）

1. **过滤出口**（同上）。0 个 → 交给引擎报无路可走。
2. **合并**（`host != null` 且 `in` 面 ≥ 2）：所有到达都登记在 `viable.first()` 那把钥匙下；
   不够数就 `Stop()`；凑够后**每条到达压一个列表**

   `[ ListIota(那一支原本的整个栈), 渡鸦之思 或 null, Vec3Iota(来自方向) ]`

   N 条进来 = 出栈 N 个列表，然后**跑一次**图案。图案失败 → `Stop()`。
   没写图案时退回 `CircleImageMerge.merge`（普通件语义）。
3. **分流**（每个出口各一遍）：压出口方向 → 跑图案 → 这条就是该出口的分支镜像。
   第一个出口交回引擎当主波，其余 `ForkedExecutionState` 分支波。**没写图案时不压方向**（免得把普通环的栈弄脏）。
4. **多进 + 多出** = 先合并跑一次，再对每个出口各跑一次。
5. 图案执行失败（莫邪 / 停机）→ 那条路断掉；一条不剩就 `Stop()`。
6. 染色跟着**主出口**那条波的栈顶走。

图案执行照抄 `BlockSlate.acceptControlFlow`：

```java
var vm = new CastingVM(image, env);
var result = vm.queueExecuteAndWrapIota(new PatternIota(pattern), world);
if (!result.getResolutionType().getSuccess()) return null;   // 断这条
return vm.getImage().withOverriddenUsedOps(0);               // 算子计数归零
```

---

## 7. 踩过的坑

1. **IDE 缓冲区覆盖源码**：这个项目里 `PlugSlateBlock.kt` 被 IntelliJ 旧缓冲区冲掉过两次（磁盘上的代码突然退回旧版）。
   改磁盘前先在 IDE 里 **File → Reload from Disk**，改完核对 mtime。
2. **stale class**：文件写对了但 `build/classes` 还是旧的（`getShape` 曾因此"消失"，碰撞箱变成整格）。
   改完看 `.class` 时间戳，或 `javap -p` 看方法在不在。
3. **`createBlockStateDefinition` 忘了 `super`** → 首次点亮炸 `setValue` 未注册属性。
4. **模型与形状表脱钩**：改了模型厚度没改 `VoxelShape` → 看得见撞不到 / 撞到看不见。
5. **`RenderType` 加多了**：只有 `_p` 需要 cutout。
6. **版本目录别名冲突**：`buildSrc` 里的 Java 版本别名**不能叫** `java-build` / `javaBuild`，
   会和 `java` 形成前缀冲突，`libs.versions.java.get()` 当场变成 receiver type mismatch，一口气带崩 buildSrc。现在叫 `jdk = "21"`。
7. **`Block` 不声明 `newBlockEntity`**：加方块实体必须 `EntityBlock` 且把 `getRenderShape` 改回 `MODEL`。
8. **上游 `ICircleComponent.sfx` 不判空**：走通出口时它读 `impetus.getExecutionState().reachedSlate` 算音高
   （`ICircleComponent.java:138`），而「主波已经结束、分支还在飞」正是 `executionState == null` 的时刻 ——
   分支一走通出口就 `NullPointerException: Cannot read field "reachedSlate" because "state" is null` 崩服。
   解法：推进分支期间把**某条分支临时挂成促动石的 `executionState`**，跑完 `try/finally` 立刻摘掉。
   这个替身只骗 `sfx` / `getPigment`：`CircleCastEnv` 是在 `CircleExecutionState.tick` 里用 `this` 新建的，
   所以 `env.circleState()` 拿到的仍是真正在跑的那条波，分流/合并数据不受污染。
9. **Hex 0.11.4 的硬前置 `inline >= 1.20.1-1.2.2`**：项目里曾钉在 `1.20.1-1.0.1`，fabric 端直接
   `Incompatible mods found / HARD_DEP_NO_CANDIDATE`。同族的还有 `paucal [0.6.0,0.7.0)`、
   `patchouli >= 1.20.1-80`、`cardinal-components ~5.2.1`（fabric）、`caelus >= 3.1.0+1.20`（forge）。
10. **IoticBlocks 不能走 Modrinth maven**：同一版本号只发一个文件，`maven.modrinth:ioticblocks:1.0.2+1.20.1`
    拿到的是 forge 那个 jar，fabric 那边 404。所以按本仓库既有 `libs:` flatDir 约定放两个 jar。
11. **`getExecutionState()` 会复活死波**：它在 `lazyExecutionState != null` 时懒加载，
    而 `lazyExecutionState` 在波结束后**不会被清空**。所以「施法中途保存 → 重进世界 → 施法结束」之后
    理论上还能把已经结束的波再拉起来。正常新世界不触发（该字段只在读档时出现），暂未处理。

---

## 8. 验证配方

```powershell
# 1) 编译（日常够用）
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.10"
.\gradlew.bat :common:classes :fabric:classes :forge:classes --console=plain
```

```powershell
# 2) 核对 dev 运行时的模组版本（Hex 升级后必做，比进游戏试错快得多）
.\gradlew.bat :fabric:dependencyInsight --dependency inline --configuration modRuntimeClasspath --console=plain
.\gradlew.bat :fabric:dependencies --configuration modRuntimeClasspath --console=plain | Select-String "hexcasting|ioticblocks|paucal|patchouli|inline"
```

```powershell
# 3) 资产自检
#    texcheck.py 只覆盖普通分流器的 6 张图（multi_directrix_{0,1,2}[_energized]），
#    打印尺寸/alpha 通道、两两像素差、_X 与 _X_energized 的轮廓是否一致，以及 ASCII 预览
python doc\texcheck.py common\src\main\resources\assets\abadoned_greatwork\textures\block
# 手工核：blockstates 条目数 16 / 36 / 13；每个 element 的 uv 尺寸 == 该面实际尺寸
```

**进游戏手测清单**（不需要看截图，看日志和游戏内现象即可）：

| # | 搭法 | 期望 |
|---|---|---|
| 1 | 促动石 + 两个 `plug_slate` 转角 + 一块石板 | 波正常转弯，无 `many_exits` |
| 2 | 分流：`in`=北、`out`=东+西，两路各接石板 | **两条波同时前进**（不是一条），无 `many_exits`，日志无 NPE |
| 3 | 拆分流的中途把分流器拆了 | 已经分出去的波照常跑完 |
| 4 | 合并：`in`=东+西、`out`=南 | 先到的那条停住（格子**保持点亮**），到齐后从南出去一条，石板收到的栈按到达顺序排列 |
| 5 | 合并只接一条来路 | 支路死了之后靠栅栏放行，不会永远卡住 |
| 6 | `_p` 写入一条图案后分流 | 每个出口栈里多一个方向矢量，染色跟着栈顶 iota 的类型变 |
| 7 | `_p` 写入 `[栈, 渡鸦之思, 方向]` 的合并 | 出栈 N 个列表，图案跑**一次** |
| 8 | 施法中途存档、退出、重进 | 不应崩；分支应能续跑（登记表会丢，见 §5.6） |

---

## 9. 未完成 / 待定

| # | 事项 | 状态 |
|---|---|---|
| 1 | **跑通一次完整施法** | 修复 sfx 空指针后还没试过，优先级最高 |
| 2 | `_p` 没有 `_energized` 贴图 | 激活态在视觉上没变化（`energized` 属性还在，比较器照常可用） |
| 3 | `_p` 不能「把图案写在物品上，放下去就带着」 | 石板那种 `BlockEntityTag` 携带没做；目前只能放下来再用 iota 写 |
| 4 | `_p` 硬依赖 IoticBlocks | 没装就没有写入口，方块等于摆设。要不要「装了才注册」待定 |
| 5 | 合并登记表不存盘 | 施法中途存档会丢（见 §5.6） |
| 6 | 施法中拆促动石 → 格子常亮 | 上游 `onRemove` 在 `executionState == null` 时直接返回 |
| 7 | `lazyExecutionState` 会复活死波 | 见 §7-11，未处理 |
| 8 | `multi_directrix_energized.json` 是孤立模型 | 没有任何 blockstate 引用它，可删 |
| 9 | `plug_slate` 的 `energized=true/false` 指向同一模型 | 点亮时没有视觉反馈；要不要补一张发光图待定 |
| 10 | 潜行右键用的是 `AMETHYST_BLOCK_CHIME` | 方块本身是 DEEPSLATE 音效，这一下算不算违和待定 |
| 11 | `strength(3.0, 6.0)` | 上游 `slateish()` 是 `(4, 4)`，要不要对齐待定 |

---

## 10. 文件清单

```
common/src/main/kotlin/cn/xm1221/abadoned_greatwork/
├── Abadoned_greatworkClient.kt                  # _p 的 cutout 层 + 附加层染色注册
├── block/
│   ├── PlugSlateBlock.kt                        # 转角件（L 形薄板）
│   ├── MultiDirectrixBlock.kt                   # 分流/合并件 + PortMode + PORTS 表
│   └── MultiDirectrixPBlock.kt                  # 可编程分流器（合并 → 跑图案 → 分流）
├── block/entity/
│   └── MultiDirectrixPBlockEntity.kt            # 存 HexPattern + 染色，实现 ADIotaHolder
├── casting/circles/
│   ├── ForkedExecutionState.kt                  # 分支波子类（只为暴露 protected 构造器）+ forkImage()
│   ├── MergeBuffer.kt                           # 合并点到达登记表 + 栅栏兜底放行
│   ├── CircleImageMerge.kt                      # 合并镜像（栈按到达顺序摞，渡鸦之思取最后一个）
│   └── ImpetusBranchHost.java                   # 促动石「分支表」鸭子接口
└── registry/
    ├── Abadoned_greatworkBlocks.kt              # 方块注册
    ├── Abadoned_greatworkBlockEntities.kt       # 方块实体注册
    ├── Abadoned_greatworkItems.kt               # BlockItem
    └── Abadoned_greatworkCreativeTabs.kt        # 创造栏

common/src/main/java/cn/xm1221/abadoned_greatwork/
├── registry/Abadoned_greatworkIotaHolders.java   # IoticBlocks 写入口注册（Java，包名里有 Kotlin 关键字）
└── mixin/
    ├── ImpetusBranchMixin.java                   # 促动石分支表（4 条注入，全部 remap = false）
    ├── PatternIotaMixin.java                     # 既有：测试环境禁 SpellAction
    ├── MixinDatagenMain.java                     # 既有：仅 datagen 时启用
    └── Abadoned_greatworkMixinConfigPlugin.java   # 既有：按系统属性开关 MixinDatagenMain

common/src/main/resources/assets/abadoned_greatwork/
├── blockstates/plug_slate.json                   # 16 变体（facing × half × energized）
├── blockstates/multi_directrix.json              # 36 条 multipart（6 面 × 3 档 × 2 激活）
├── blockstates/multi_directrix_p.json            # 13 条 multipart（本体 + 6 面 × out/none）
├── models/block/plug_slate.json                  # L 形，1px
├── models/block/multi_directrix.json
├── models/block/multi_directrix_face_{none,in,out}{,_energized}.json   # 6 张朝北面片
├── models/block/multi_directrix_p.json           # 8 角块 + 核心
├── models/block/multi_directrix_p_face_{out,none}.json                 # 16×16 附加面片
├── models/block/multi_directrix_energized.json   # ⚠ 孤立，无人引用
├── models/item/{plug_slate,multi_directrix,multi_directrix_p}.json
├── textures/block/plug_slate.png
├── textures/block/multi_directrix_{0,0_energized,1,1_energized,2,2_energized}.png   # 0=关闭 1=输出 2=输入
├── textures/block/{multi_directrix_p,multi_directrix_p_1,multi_directrix_p_2}.png   # 输入 / 输出(灰阶) / 关闭
└── lang/{zh_cn,en_us}.json

common/src/main/resources/abadoned_greatwork-common.mixins.json    # mixins 里要有 ImpetusBranchMixin
common/src/main/resources/data/abadoned_greatwork/loot_tables/blocks/{plug_slate,multi_directrix,multi_directrix_p}.json
doc/texcheck.py                                                     # 无依赖 PNG 自检
libs/IoticBlocks-{fabric,forge}-1.20.1-1.0.2.jar                    # IoticBlocks 本地 jar（不进 Modrinth maven）
```
