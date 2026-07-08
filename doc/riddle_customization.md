# 数据包自定义谜题

真理水晶的谜题支持通过数据包添加，无需修改代码。

## 文件位置

在数据包的 `data/abadoned_greatwork/riddles/` 目录下创建 `.json` 文件，文件名随意。

```
<datapack>/
└── data/
    └── abadoned_greatwork/
        └── riddles/
            ├── add.json
            └── my_riddle.json
```

## 两种格式

### 1. 完整 NBT 模式（推荐）

直接给出真理水晶物品的完整 NBT（SNBT 格式），最简洁：

```json
{
  "nbt": "{input:{0:<Input0的ListIota SNBT>,1:<Input1的ListIota SNBT>},output:{0:<Output0的ListIota SNBT>,1:<Output1的ListIota SNBT>},length_limit:5,tooltip:\"自定义提示\",mode:\"normal\",is_crafted:0b,can_write:0b}"
}
```

> 获取 SNBT：游戏内手持已配好的真理水晶，按 F3+H 开启高级提示，`/data get entity @s SelectedItem.tag` 即可复制 NBT。

### 2. 结构化字段模式

逐字段填写，适合手动编写：

```json
{
  "name_key": "text.abadoned_greatwork.riddles.my_riddle",
  "input0": "{hexcasting:type:\"hexcasting:list\",hexcasting:data:[...]}",
  "input1": "{hexcasting:type:\"hexcasting:list\",hexcasting:data:[...]}",
  "output0": "{hexcasting:type:\"hexcasting:list\",hexcasting:data:[...]}",
  "output1": "{hexcasting:type:\"hexcasting:list\",hexcasting:data:[...]}",
  "length_limit": 5,
  "variant": 0
}
```

| 字段 | 说明 |
|---|---|
| `name_key` | tooltip 翻译键 |
| `input0/1` | 两轮输入，每轮一个 ListIota 的 SNBT |
| `output0/1` | 两轮期望输出 |
| `length_limit` | 手持法术长度上限 |
| `variant` | 水晶变体 0/1/2，决定奖励战利品表 |

## 效果

每个谜题自动生成 **3 个变体物品**，分别出现在：
- 创造模式物品栏
- 结构战利品箱（随机抽取）

验证通过后根据 `variant` 发放对应奖励。
