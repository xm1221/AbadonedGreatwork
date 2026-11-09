# 法术环件（三个方块）实现交接

> 交接对象：`plug_slate`（插接石板）、`multi_directrix`（多向导向石）、`multi_directrix_p`（多向导向石·核心）。
> 前两个只是注册+资产，`plug_slate` 已可当 HexCasting 环件使用，后两个**环件逻辑未实现**。
> 本文写于 2026-11-09，代码状态以当时磁盘为准（`git status` 里三个方块文件都还是未跟踪的新文件）。

---

## 1. 一句话现状

| 方块 | 环件逻辑 | 说明 |
|---|---|---|
| `abadoned_greatwork:plug_slate` | ✅ 完整 | 「底部 1px 平板 + 一侧 1px 竖墙」的 L 形转角件，16 个状态，能传导媒质波、能被点亮 |
| `abadoned_greatwork:multi_directrix` | ✅ 完整 | 六面各自「未连接 / 输入 / 输出」的实心方块，继承 `BlockCircleComponent`；**分流是真并行、合并已接**（见 §6.2 / §6.2.5） |
| `abadoned_greatwork:multi_directrix_p` | ❌ 未做 | 同上，只是外壳模型（8 角块 + 核心） |

`truth_crystal` 是更早完成的另一套东西，不在本文范围。

---

## 2. 环境与构建（必读，否则第一步就卡）

- MC **1.20.1** / Architectury（common + fabric + forge）/ Kotlin 1.9.22 / Java 17 / Loom 1.17
- 依赖 Hex Casting **0.11.3**、Architectury API 9.2.14
- mod id `abadoned_greatwork`，包 `cn.xm1221.abadoned_greatwork`

```powershell
# 机器 PATH 上是 JDK 25，直接跑 gradlew 会因为版本不对失败，必须先指定
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
.\gradlew.bat :common:classes --console=plain      # 日常验证就够
.\gradlew.bat :common:jar :fabric:classes :forge:classes
```

**不要用 `:common:build`**：它挂在 `:common:javadoc`，`common/src/main/java/cn/xm1221/abadoned_greatwork/entity/EyeOfWaypoint.java` 第 45 行有中文注释，GBK 下解码失败报 `unmappable character`。这是既有问题，和环件无关；要根治就改那句注释或给 javadoc 加编码参数。

编译产物位置：`common/build/classes/kotlin/main/cn/xm1221/abadoned_greatwork/...`

---

## 3. 已注册的方块与资产

全部在 `common/src/main/kotlin/cn/xm1221/abadoned_greatwork/registry/Abadoned_greatworkBlocks.kt`，
物品在 `Abadoned_greatworkItems.kt`（`registerBlockItem`），创造栏在 `Abadoned_greatworkCreativeTabs.kt`。

| id | 方块类 | 状态 | blockstate 变体 | 备注 |
|---|---|---|---|---|
| `plug_slate` | `block/PlugSlateBlock.kt` | 16（`energized`×`facing`×`half`） | 16 | 继承 `BlockCircleComponent` |
| `multi_directrix` | `block/MultiDirectrixBlock.kt` 的 `MultiDirectrixBlock` | 1458 | 36（multipart） | 继承 `BlockCircleComponent`，六面各自 未连接/输入/输出 |
| `multi_directrix_p` | 同文件 `MultiDirectrixPBlock` | 1 | 1 | `noOcclusion()`，需要 cutout |

三个方块都设了 `pushReaction(PushReaction.BLOCK)`（活塞推不动，和 HexCasting 环件一致）。
战利品表：`data/abadoned_greatwork/loot_tables/blocks/{plug_slate,multi_directrix,multi_directrix_p}.json`，掉落自身。

**渲染层**：`Abadoned_greatworkClient.kt` 里只给 `MULTI_DIRECTRIX_P` 注册了 `RenderType.cutout()`。
原因：那个模型把框架内侧面的 UV 映射到贴图的全透明区域，只有 cutout 才会真的"消失"，留在 solid 层会画成黑块。
`plug_slate` 的贴图不透明，走默认 solid 层即可（**加 cutout 会掉性能，也没有必要**）。

命名（中英已在 `lang/zh_cn.json` / `en_us.json`）：
- `plug_slate` = 插接石板 / Plug Slate
- `multi_directrix` = 多向导向石 / Multi-Directrix
- `multi_directrix_p` = 多向导向石·核心 / Multi-Directrix Core

> 译名参照 HexCasting 官方中文：directrix = 导向石、impetus = 促动石、slate = 石板、media = 媒质。

---

## 4. `plug_slate` 实现细节

### 4.1 状态与放置

```kotlin
FACING : DirectionProperty = BlockStateProperties.HORIZONTAL_FACING   // 竖墙贴住的那一面
HALF   : EnumProperty<Half> = BlockStateProperties.HALF               // bottom=贴地 / top=贴天花板
ENERGIZED : BooleanProperty                                            // 父类 BlockCircleComponent 提供
```

`getStateForPlacement`：
- 点方块**侧面** → `facing = clicked.opposite`（竖墙靠住被点的那一格）
- 点**顶面/底面** → `facing = context.horizontalDirection`（玩家视线方向）
- `half = if (clicked == DOWN) TOP else BOTTOM`

`createBlockStateDefinition` **必须先 `super`**（父类在那里注册 `ENERGIZED`）；漏了不会立刻报错，
等媒质波第一次点亮这一格才炸（`setValue` 未注册属性）。

### 4.2 形状：全部 1px，没有任何整格兜底

板厚 **1px（0.0625）**，因为「同一条薄板折了 90°」，底板和竖墙同厚。

```
底板  贴地      ( 0, 0, 0)-(16, 1,16)
底板  贴天花板  ( 0,15, 0)-(16,16,16)     ← 模型绕 X 翻 180°
竖墙  贴地      y 1..16，x 0..1 或 z 0..16（随 facing 转）
竖墙  贴天花板  y 0..15，同上
```

`getShape` 的兜底是 `SHAPES[half]?.get(facing) ?: PLATES.getValue(half)` —— **兜底只给底板**，
所以碰撞箱不可能退化成整格方块（这个 bug 修过一次，根因就是某次编辑把 `getShape` 弄丢了）。

模型 `assets/abadoned_greatwork/models/block/plug_slate.json` 与形状表逐坐标对应；
改厚度/尺寸时**两边必须同时改**，否则渲染和碰撞对不上。

### 4.3 blockstate 与模型旋转

16 个变体，`facing` 映射（基模型是"西墙"）：

| facing | `y` | 说明 |
|---|---|---|
| `west` | 0（省略） | 基模型 |
| `north` | 90 | |
| `east` | 180 | |
| `south` | 270 | |

`half=top` 额外加 `"x": 180`。旋转顺序是**先 x 后 y**（`BlockModelRotation` = `Ry·Rx`），两者正交，
所以 4 个 y × 2 个 x 正好覆盖 8 种形态，不需要 roll。

### 4.4 环件逻辑（**当前版本**：两片并集 + 折线歧义报错）

这是被咩咩拍板的版本。曾有一版"精确板面接触"（用 `getShape` 求两个邻格的截面矩形是否正面积重叠），
被咩咩判定「没什么道理」并要求回退，**不要再往回写**。

```kotlin
possibleExitDirections  = EnumSet.allOf(Direction::class.java)   // 全 6 向
canEnterFromDirection   = true                                   // 任何方向都能进
acceptControlFlow       = (来向平行折线) ? 报去路过多 + Stop
                                        : Continue(全 6 向 - 来路)
```

**为什么出口是 6 个**：单块石板只有一片，出口只能取「法线以外」；这块是两片（横片 + 竖片），
两片各自禁掉自己暴露面的法线后取并集，剩下的正好全通。

**为什么入口恒 true**（这一段是踩坑记，别改回去）：
原来的写法是 `enterDir != normal.opposite || enterDir != face.opposite`，它**恒为 true**。
看着像写错，但其实是对的——一个方向不可能同时等于两个互不相同的方向。
改成 `&&` 才是错的：那会把转角石板变成「只能从下方进」。

根因是上游那个缺失的参数：`ICircleComponent` 只给方向、**不给带高**。
波从东边朝墙过来时，可能走在横片的上边缘（合法），也可能垂直穿过竖片的暴露面（不合法），
`enterDir` 区分不了。算不准就只能取宽：任一方向至少有**一片**能承载这条波。

真正的收窄由引擎做：它拿着候选出口逐格再问下一格的 `canEnterFromDirection`，
**两个邻格都能接**时才报「去路过多」—— 那才是真的「把这块当两块板用」。

**折线轴**（`foldAxis`）= 水平方向上垂直于 `FACING` 的那个轴（`facing=west` → Z 轴 → 被拦的是 N/S）。
来向平行于折线时，横片和竖片**都**能承载这条波 = 两片都认 = 主动报「去路过多」。

报错是**照抄引擎**的（见 §5 的 `many_exits` 原文），观感和原版完全一致。
开关在 companion：

```kotlin
const val REPORT_FOLD_LINE_AMBIGUITY = true   // false = 退化成万能接头，任何方向都能过
```

行为表（贴地 + `facing=west`）：

| `enterDir`（前进方向） | 上一格在哪 | 结果 |
|---|---|---|
| NORTH / SOUTH | 南 / 北 | **平行折线 → 去路过多** |
| EAST | 西边（墙那侧） | 通过 |
| WEST | 东边 | 通过 |
| UP | 下方 | 通过 |
| DOWN | 上方 | 通过 |

出口再交给引擎按邻居收窄。**读法：这块只能"转"（水平 ↔ 竖直），不能当直板沿着墙走。**



### 4.5 粒子

```kotlin
override fun normalDir(...) = if (half == BOTTOM) UP else DOWN     // 只喂粒子，逻辑不用它
override fun particleHeight(...) = 0.5f - 15f / 16f                // = -0.4375
```

`-0.4375` 是**有符号**偏移：`vpos = 格心 + normalDir.step() * height`，贴地时法线朝上 → 粒子在
`y = 1/16`（底板顶面）；贴天花板时法线朝下 → 同一个常量让粒子落在 `y = 15/16`（底板底面）。
与 HexCasting 石板同值（它的板也是 1px）。

---

## 5. HexCasting 0.11.3 环系统要点（实读源码，可直接照抄）

环件判定：`state.getBlock() instanceof ICircleComponent`，**没有 tag、没有白名单**。

`ICircleComponent` 的方法：

```java
ControlFlow acceptControlFlow(CastingImage, CircleCastEnv, Direction enterDir, BlockPos, BlockState, ServerLevel);
boolean canEnterFromDirection(Direction enterDir, BlockPos, BlockState, ServerLevel);
EnumSet<Direction> possibleExitDirections(BlockPos, BlockState, Level);   // 注意是 Level，不是 ServerLevel
void startEnergized / boolean isEnergized / void endEnergized;
default Direction normalDir(BlockPos, BlockState, Level, int recursionLeft);
default float particleHeight(BlockPos, BlockState, Level);
static void sfx(BlockPos, BlockState, ServerLevel, BlockEntityAbstractImpetus, boolean success);
static Pair<BlockPos, Direction> exitPositionFromDirection(BlockPos, Direction);
```

`BlockCircleComponent`（抽象基类）提供了 `ENERGIZED` 属性、比较器输出 15、
`startEnergized/isEnergized/endEnergized`、抽象 `normalDir` 与 `particleHeight`；
它的 `createBlockStateDefinition` 只注册 `ENERGIZED`，子类必须 `super`。

**建环（`CircleExecutionState.createNew`）**：从 `impetus.relative(impetus.FACING)` 开始 DFS 洪泛，
每格扩散 `possibleExitDirections`，进入前先过 `canEnterFromDirection`；必须能回到促动石本身，
超过 `HexConfig.server().maxSpellCircleLength()` 失败。

**每 tick（`CircleExecutionState.tick`）顺序**：

1. 走到非环件 → 放失败音效、停机
2. `startEnergized`
3. `reachedPositions.add(currentPos)`
4. `acceptControlFlow(...)`
5. 促动石没了 → 停
6. `Stop()` → 停机（**错误必须由 `acceptControlFlow` 自己发过**，引擎不会再补）
7. `Continue(update, exits)` → 遍历 exits 问下一格肯不肯接：
   **0 个** → 失败音效 + `impetus.postNoExits(pos)` + 停；
   **≥2 个** → 见下；**恰好 1 个** → 走过去，`currentImage = update.withOverriddenUsedOps(0)`

「去路过多」原文（`many_exits`，不是 mishap，是一条显示）：

```java
impetus.postDisplay(
    Component.translatable("hexcasting.tooltip.circle.many_exits",
        Component.literal(pos.toShortString()).withStyle(ChatFormatting.RED)),
    new ItemStack(Items.COMPASS));
ICircleComponent.sfx(pos, bs, world, impetus, false);
halt = true;
```

节流：`getTickSpeed() = max(2, 10 - (reachedPositions.size() - 1) / 3)`。
结束时对 `reachedPositions` 全部 `endEnergized`。

**关键限制**：一块促动石只持有一个 `executionState`（字段受保护），所以"真并行"必须
子类化 `CircleExecutionState` 并自管 BE tick，见 §6。

---

## 6. 待办：`multi_directrix` / `multi_directrix_p`

咩咩对机制的原话：

- `multi_directrix`：**分流器**，一个输入变多个媒质波输出（或者反过来合并）。
- `multi_directrix_p`：**更智能的版本**，可以在内部通过咒术运算决定怎么分流/合并。

**视觉与状态层已拍板并落地**（见 §6.1）。分流/合并的**数据**语义还没定，只有三个档位的取舍（按成本递增）：

| 档位 | 做法 | 代价 |
|---|---|---|
| L1 路由 | 只把多向做成 `ICircleComponent`，出口列表给多个方向，让引擎自己消歧 | 零额外代码，但做不到"一个输入多个输出"（引擎要求恰好 1 个可进邻格） |
| L2 时分 | 自己持 `BlockEntity` + 计数器，每 tick 轮换把不同的分支当出口 | 需要 BE 与 ticker；同一时刻仍只有一条波在跑 |
| L3 并行 | 子类化 `CircleExecutionState`（`executionState` 字段 protected，子类可见），复制出多条波 | 最接近语义，但要不要改 `BlockAbstractImpetus` 的存盘格式要确认 → **咩咩已选这条，见 §6.2** |

### 6.1 端口状态与渲染（已落地）

咩咩拍板的机制：**六个面各自是一个端口，三档** —— `_0 = 未连接`、`_2 = 输入`、`_1 = 输出`。

| 项 | 定论 |
|---|---|
| 放置默认 | 六面全 `未连接`（`_0`），整块看起来是全封实的实心方块，**什么都不接** |
| 切换 | **潜行右键点中哪一面就切哪一面**，按 `未连接 → 输入 → 输出 → 未连接` 循环（用 `hit.direction`，不用算视线）；音高 0.8 / 1.4 / 1.0 作三档反馈 |
| 贴图 | `未连接` → `_0`/`_0_energized`；`输入` → `_2`/`_2_energized`；`输出` → `_1`/`_1_energized` |
| 激活 | 媒质波经过时 `energized = true`，六面同时换成各自的 `_energized` |
| 音效 | `SoundType.DEEPSLATE`。**照抄上游**：`HexBlocks.slateish()` = `Properties.copy(Blocks.DEEPSLATE_TILES).strength(4f, 4f)`，石板 / 促动石 / 两种导向石全用它，所以整族都是深板岩音效；本项目 `plug_slate` 早就是 DEEPSLATE |
| 序列化名 | `none` / `in` / `out`（进 blockstate 的 `when`，别改） |
| 状态数 | 3⁶ × 2 = **1458**（对比：vanilla `redstone_wire` 是 1296，还在这量级内） |

**为什么值得多出这一档**：没有「未连接」时，每个没被用到的面也是 `in`，
「这个块有几个输入」根本数不出来，合并就没法判断「到齐了没有」。有了这一档，
`in` 面的个数就是**精确的期望到达数** —— 这是接合并的前提。

**不写 1458 个模型**：用 `multipart`。模型只有 6 个（同一张「朝北面片」配 6 张贴图：
`none` / `in` / `out` 各一套未激活 + 激活），六个面靠 blockstate 的 `x`/`y` 旋转摆到各自面上，
共 6 面 × 3 档 × 2 激活 = **36 条 multipart**：

| face | 旋转 | face | 旋转 |
|---|---|---|---|
| north | 无 | west | `y: 270` |
| east | `y: 90` | up | `x: 270` |
| south | `y: 180` | down | `x: 90` |

推导依据（别再靠猜）：MC 的 `BlockModelRotation` 四元数是 `Ry(-y)·Rx(-x)`，把朝北面片的
法线 `(0,0,-1)` 代进去 → `y=90` 得 `+X`、`x=90` 得 `-Y`、`x=270` 得 `+Y`，与 vanilla
`glow_lichen` 一致（它的本体就是一张 `z=0.1` 的朝北面片）。

**每一条都必须带 `uvlock: true`**：六张图**都没有任何旋转或镜像对称性**
（180°/90°/左右/上下全不相等；实测 `_0`↔`_2` 差 228 像素），不加 uvlock 时贴图跟着模型转，
侧面图案会躺倒；uvlock 由 `FaceBakery.recomputeUVs` 把 UV 反向补偿回该面原本的朝向。

方块仍是**整格实心**：没有 `noOcclusion()`，形状用默认整格立方体，贴图全不透明 →
走默认 solid 渲染层，**别加 cutout**。面片 `cullface` 指向自己那一面，邻格实心时正常剔除。

`rotate` / `mirror` 两个 override 必须把六个属性一起换位，否则结构方块一转就指错方向。

### 6.2 真并行分流（已落地）

咩咩拍板：**分流就是「一环变多环」，每个出口各拿一份镜像往下走**；合并的数据语义先放着，
只写个占位函数备用。

#### 6.2.1 引擎为什么挡路

`CircleExecutionState.tick` 的出口循环（原文见 §5）对每个候选出口问下一格
`canEnterFromDirection`，**一旦出现第二个能接的就报 `many_exits` 并停机**。
所以分流器不能把多个出口交出去，只能交一个 —— 其余的波必须自己另起一条。

#### 6.2.2 分流器侧（`MultiDirectrixBlock.acceptControlFlow`）

1. `possibleExitDirections` 给出全部 `out` 面，再**按「下一格真的肯接」过滤**一遍。
   不过滤的话会把波塞进一个当拍就被拒的方向，那条分支等于白死。
2. 过滤后 0 或 1 个 → 照原样交给引擎（无路可走 / 普通合并），行为和以前完全一致。
3. ≥2 个 → **第一个**交回引擎当主波，**其余每个出口各 fork 一条分支波**挂到促动石上。
   引擎只看到一条出口，`many_exits` 永远不会触发。
4. 拿不到 `ImpetusBranchHost`（不是 HexCasting 的促动石）时**退回**「老实交出全部出口」，
   让引擎自己报 `many_exits` —— 宁可报错也不静默丢路。

镜像复制见 `CastingImage.forkImage()`：栈共享（`CastingImage` 是不可变的 data class），
只有会被**就地改写**的 `userData`（`CompoundTag`）深拷贝，再 `withOverriddenUsedOps(0)`
对齐引擎推主波时的算子归零。

#### 6.2.3 促动石侧（`ImpetusBranchMixin`）

促动石原本只有一条 `executionState`，全环也只有它一个天然调度者，所以四条注入：

| 目标 | 时机 | 干什么 |
|---|---|---|
| `tickExecution` | `RETURN` | 跟着主波一起 `tick` 每条分支；主波结束时把分支一起收掉 |
| `startExecution` | `HEAD` | 开新一次施法前清掉上一次的残党（正在跑时不动） |
| `saveModData` / `loadModData` | `TAIL` | 分支列表存盘读盘；读盘时世界可能没就绪，先存 NBT，等第一次 tick 再反序列化（上游自己也是这么处理 `executionState` 的） |

**不需要额外收尾**：分支与主波**共用同一个 `reachedPositions` 列表**，主波结束时的
`endExecution` 会顺手把分支点亮的格子全部熄掉。这也是为什么 `ForkedExecutionState`
必须共享这个 `List` 而不是各拿一份 —— 各拿一份就会留下一堆永远亮着的方块。

遍历分支时走**快照**（`new ArrayList<>(branches)`），因为分支自己路过另一个分流器时会往
同一张表里加新分支，直接迭代会触发 `ConcurrentModificationException`；新加的分支这一 tick
不跑，下一 tick 开始跑。

#### 6.2.4 已知取舍

- **主波一结束，所有分支立刻一起结束**。分支可能差一两格没走完就被切断 —— 只影响视觉
  （那几格不点亮），不影响媒质和数据。要改成「等所有波走完」也可以，但得自己
  `scheduleTick` 续命，还得在最后一条分支死掉时补一次 `endExecution`，否则会漏掉点亮。
- `getTickSpeed()` 吃的是共享的 `reachedPositions.size()`，所以并行分支多时整环会一起变慢。
- **分支比主波快一拍**：fork 是在主波 `tick` 里发生的，而分支在那次 `tickExecution` 的尾部就
  被推进了一格，主波要等下一次调度才动。这个相位差是**恒定 1 tick**、不累积的（之后每 tick
  两者各进一格），肉眼与手感上都无差别，但如果以后要对齐时序（比如合并登记）要记得它。
- 分支存在促动石上，**分流器被拆掉不影响已经分出去的波**（这是选促动石而不是给分流器
  加 `BlockEntity` 的原因）。

#### 6.2.5 合并（已接上）

三个卡点，逐个解决：

| 卡点 | 怎么解决的 |
|---|---|
| 引擎没有「等待」态：波到一格必须在同一 tick 决定走还是死 | 先到的波 `ControlFlow.Stop()` 自己，只把镜像留在登记表里 |
| 「到齐了没有」 | §6.1 的 `未连接` 档让 **`in` 面个数 = 精确的期望到达数** |
| 生命周期：上一版是「主波结束 → 所有分支一起结束」，而合并就是要让波主动 Stop | 改成「**一条活波都没有**才算结束」 |

**真流程**（`MergeBuffer` + 促动石那五条注入）：

| 谁 | 做什么 |
|---|---|
| `MultiDirectrixBlock.acceptControlFlow` | 可行 `out` 只有 1 个、且 `in` 面 ≥ 2 → 登记自己，不够数就 `Stop()`（不报错，格子保持点亮） |
| 同上，凑够数的那一条 | `CircleImageMerge.merge(...)` 合并、清登记表、自己带着合并结果继续走 —— **不多造波** |
| `ImpetusBranchMixin` ① | 推进分支波（走快照，新分支下一 tick 才动） |
| ② 栅栏 | 一条活波都没有了、但登记表非空 → 凑合放行：合并已到手的，在 `pos.relative(outDir)` **外面一格**造新波（从本格出发会立刻再次登记自己，死循环） |
| ③ 收尾 | 彻底没波 → 补一次 `endExecution` 熄灯（幂等），清表 |
| ④ 续调度 | 主波死了但分支还活着 → 先重新点亮促动石，再 `scheduleTick` |

**超时兜底不需要了**：原来担心「玩家搭出没有促动石的闭环支路时那条波永远转，栅栏永远不触发」。
但那种环在接入前就已经是坏环（上游同样永远转），加个超时只会误伤正常的长环，所以不做。

**两个踩到才知道的坑**：

- `BlockAbstractImpetus.tick` 是 `if (... && pState.getValue(ENERGIZED)) tile.tickExecution();`
  —— **促动石不亮就根本不推进**。而主波死掉时上游的 `endExecution()` 会把促动石一起熄灭，
  所以续调度**之前必须先重新点亮**（走 `startEnergized`），否则排进去的 tick 什么都不做。
- 熄灯不能只指望上游：栅栏放行出来的新波是主波死后才动的，它点亮的格子没人管，
  收尾那一步得自己再扫一遍。

**已知限制**：

- 登记表里攒的是已经 `Stop()` 掉的波，没有活波可以存盘 → **世界在施法中途保存时这部分会丢**
  （分支本身是存盘的，不会坏档）。
- 施法中把促动石拆掉：上游 `onRemove` 里的 `endExecution()` 会因为 `executionState == null`
  直接返回，分支不会被清，那些格子会一直亮着。

**数据语义仍是占位**：`CircleImageMerge.merge` 现在是「以第一份为底，其余栈按顺序接在下面」。
栈长度一变，后面图案取到的 iota 就全错位，所以真要用起来多半得换成「每份栈各打成一个
`ListIota`」。三种备选见 `CircleImageMerge.kt` 的注释。

### 6.3 `_p` 额外需要

- 一个 `BlockEntity`，照 `BlockEntitySlate` 的 NBT 存 `HexPattern`（键名 `pattern`）
- 放置时从物品的 `BlockEntityTag.pattern` 读（HexCasting 的石板就是这么拿图案的）
- 运算用 `CastingVM(image, env).queueExecuteAndWrapIota(PatternIota(p), level)`，
  看 `ExecutionClientView.resolutionType == ResolvedPatternType.success`、栈顶 iota 决定出口
- **已知风险**：一个 `PatternIota` = 一个算子；多步运算要靠同一个 VM 连续调用，未实测
- **真正的卡点**：`_p` 算完之后的结果怎么落到"出口唯一"上（引擎只认恰好 1 个能进的邻格）

分流已经按 L3 真并行落地（§6.2），所以 `_p` 的活不是「打通链路」，而是在这之上加一层
「先算一算，再决定从哪个口出去」—— 每个 `out` 面各跑一次运算，或者算一次再用结果挑口。

---

## 7. 踩过的坑（按痛苦程度排）

1. **IDE 缓冲区覆盖源码**：这个会话里 `PlugSlateBlock.kt` 被 IntelliJ 的旧缓冲区冲掉过两次
   （表现：磁盘上的代码突然退回旧版本，或出现你以为已经删掉的函数）。
   改磁盘前先在 IDE 里 **File → Reload from Disk**，改完核对 mtime。
2. **stale class**：文件写对了但 `build/classes` 还是旧的（`getShape` 曾因此"消失"，
   碰撞箱因此变成整格方块）。改完必须看 `.class` 时间戳，或 `javap -p` 看方法在不在。
3. **`possibleExitDirections` 写成空函数体 + `TODO()`** 会导致编译失败 / 运行期崩，别留空。
4. **`createBlockStateDefinition` 忘了 `super`** → 首次点亮炸 `setValue` 未注册属性。
5. **模型与形状表脱钩**：改了模型厚度但没改 8 个 `VoxelShape` → 看得见却撞不到 / 撞到看不见。
6. **`facing` 的 y 旋转映射**记住基模型是"西墙"：north=90、east=180、south=270。
7. **`RenderType` 加多了**：`plug_slate` 不需要 cutout；`multi_directrix_p` 才需要。

---

## 8. 验证配方

```powershell
# 1) 编译 + 产物里方法是否真的存在
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
.\gradlew.bat :common:classes --console=plain
& "C:\Program Files\Java\jdk-17\bin\javap.exe" -p -classpath common\build\classes\kotlin\main `
    cn.xm1221.abadoned_greatwork.block.PlugSlateBlock
```

```powershell
# 2) 资产自检（变体数 / 模型元素 UV 尺寸是否等于面尺寸 / 贴图与战利品表是否存在）
#    以前用 python 脚本做，核心检查：
#    - blockstates/plug_slate.json 必须有 16 个 variants，且 pointing 到 models/block/plug_slate.json
#    - 每个 element 的每个面 uv 宽度/高度 == 该面的实际尺寸（1px 板的面是 1×16 或 16×16）
#    - textures/block/plug_slate.png 存在；loot_tables/blocks/plug_slate.json 存在
```

```powershell
# 3) 运行时读真实形状表（不启动游戏）
#    - 用 --init-script 给 :fabric 加一个 dumpRuntimeCp 任务，打印 runtimeClasspath
#    - java -cp "common\build\classes\kotlin\main;<cp>" 自己的 ShapeCheck.java
#    - ShapeCheck 里必须先 SharedConstants.tryDetectVersion() 再 Bootstrap.bootStrap()，
#      否则一碰 MC 类就炸
#    - classpath 很长，用 java @argfile，且**不要**对它做 -replace '\\','/' 之类的变换
#    期望输出：8 个状态的厚度都是 0.0625（1px）
```

---

## 9. 文件清单

```
common/src/main/kotlin/cn/xm1221/abadoned_greatwork/
├── Abadoned_greatworkClient.kt                 # 只给 multi_directrix_p 注册 cutout
├── block/
│   ├── PlugSlateBlock.kt                       # 本文主体，含环件逻辑
│   └── MultiDirectrixBlock.kt                  # 两个方块类（MultiDirectrixBlock / MultiDirectrixPBlock）
├── casting/circles/
│   ├── ForkedExecutionState.kt                 # 分支波子类（只为暴露 protected 构造器）+ forkImage()
│   ├── MergeBuffer.kt                          # 合并点到达登记表 + 栅栏兜底放行
│   ├── CircleImageMerge.kt                     # 合并镜像（语义仍是占位：栈拼接）
│   └── ImpetusBranchHost.java                  # 促动石「分支表」鸭子接口
└── registry/
    ├── Abadoned_greatworkBlocks.kt             # 方块注册
    ├── Abadoned_greatworkItems.kt              # BlockItem
    └── Abadoned_greatworkCreativeTabs.kt       # 创造栏

common/src/main/java/cn/xm1221/abadoned_greatwork/mixin/
├── ImpetusBranchMixin.java                     # 往促动石织入分支表（4 条注入，全部 remap = false）
├── PatternIotaMixin.java                       # 已有：测试环境禁 SpellAction
├── MixinDatagenMain.java                       # 已有：仅 datagen 时启用
└── Abadoned_greatworkMixinConfigPlugin.java    # 已有：按系统属性开关 MixinDatagenMain

common/src/main/resources/assets/abadoned_greatwork/
├── blockstates/{plug_slate,multi_directrix,multi_directrix_p}.json   # multi_directrix 是 multipart，24 条
├── models/block/{plug_slate,multi_directrix,multi_directrix_p}.json
├── models/block/multi_directrix_face_{none,in,out,in_energized,out_energized,none_energized}.json   # 6 张朝北面片
├── models/item/{plug_slate,multi_directrix,multi_directrix_p}.json
├── textures/block/{plug_slate,multi_directrix_p}.png
├── textures/block/multi_directrix_{0,0_energized,1,1_energized,2,2_energized}.png
│                                    # 0=未连接 1=输出 2=输入，各自带 _energized 纯换色版
└── lang/{zh_cn,en_us}.json

doc/texcheck.py                     # 无依赖 PNG 解码：查不透明性 / 像素差 / 激活态轮廓是否一致

common/src/main/resources/abadoned_greatwork-common.mixins.json   # mixins 里要有 ImpetusBranchMixin

common/src/main/resources/data/abadoned_greatwork/loot_tables/blocks/
└── {plug_slate,multi_directrix,multi_directrix_p}.json
```
