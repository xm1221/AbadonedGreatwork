#!/usr/bin/env python3
import json, os, struct

RIDDLES_DIR = "/home/antigravity/xm1221/AbadonedGreatwork/common/src/main/resources/data/abadoned_greatwork/riddles"
ZH_PATH = "/home/antigravity/xm1221/AbadonedGreatwork/common/src/main/resources/assets/abadoned_greatwork/lang/zh_cn.json"
EN_PATH = "/home/antigravity/xm1221/AbadonedGreatwork/common/src/main/resources/assets/abadoned_greatwork/lang/en_us.json"

def to_snbt(obj):
    if isinstance(obj, bool):
        return "1b" if obj else "0b"
    elif isinstance(obj, (int, float)):
        return f"{float(obj)}d"
    elif isinstance(obj, str):
        return f"\"{obj}\""
    elif isinstance(obj, list):
        return "[" + ",".join(to_snbt(x) for x in obj) + "]"
    elif isinstance(obj, dict):
        entries = []
        for k, val in obj.items():
            key_str = f"\"{k}\"" if ":" in k else k
            entries.append(f"{key_str}:{to_snbt(val)}")
        return "{" + ",".join(entries) + "}"
    return str(obj)

def hex_double(val):
    return {"hexcasting:type": "hexcasting:double", "hexcasting:data": float(val)}

def hex_bool(val):
    return {"hexcasting:type": "hexcasting:boolean", "hexcasting:data": bool(val)}

def hex_vec3(x, y, z):
    return {"hexcasting:type": "hexcasting:vec3", "hexcasting:data": {"x": float(x), "y": float(y), "z": float(z)}}

def hex_list(items):
    return {"hexcasting:type": "hexcasting:list", "hexcasting:data": items}

def hex_pattern(angles, start_dir):
    return {"hexcasting:type": "hexcasting:pattern", "hexcasting:data": {"angles": angles, "start_dir": start_dir}}

def java_vec3_hash(x, y, z):
    def h(d):
        b = struct.unpack(">q", struct.pack(">d", float(d)))[0]
        return ((b ^ (b >> 32)) & 0xffffffff)
    hx = h(x)
    hy = h(y)
    hz = h(z)
    def to_signed32(n):
        n = n & 0xffffffff
        return (n ^ 0x80000000) - 0x80000000
    res = to_signed32(hx)
    res = to_signed32(res * 31 + hy)
    res = to_signed32(res * 31 + hz)
    return res

def make_riddle(tooltip, length_limit, variant, inputs, outputs):
    input_dict = {str(i): hex_list(inputs[i]) for i in range(len(inputs))}
    output_dict = {str(i): hex_list(outputs[i]) for i in range(len(outputs))}
    nbt_dict = {
        "can_write": False,
        "input": input_dict,
        "is_crafted": False,
        "length_limit": length_limit,
        "mode": "normal",
        "output": output_dict,
        "tooltip": tooltip
    }
    snbt = to_snbt(nbt_dict)
    return {
        "variant": variant,
        "nbt": snbt
    }

# 1. Update existing 7 riddles to ensure variant is set
existing_variants = {
    "add.json": 0,
    "bigger.json": 0,
    "abs.json": 0,
    "cross.json": 1,
    "mix.json": 1,
    "block.json": 2,
    "block_cube.json": 2,
}

for fname, var in existing_variants.items():
    fpath = os.path.join(RIDDLES_DIR, fname)
    if os.path.exists(fpath):
        with open(fpath, "r", encoding="utf-8") as f:
            data = json.load(f)
        data["variant"] = var
        with open(fpath, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)
        print(f"Updated {fname} with variant {var}")

# 2. Build 11 new riddles
new_riddles = {}

# 1) midpoint (Variant 0)
new_riddles["midpoint.json"] = make_riddle(
    tooltip="text.abadoned_greatwork.riddles.midpoint",
    length_limit=5,
    variant=0,
    inputs=[
        [hex_vec3(0, 0, 0), hex_vec3(2, 4, 6)],
        [hex_vec3(1, -3, 5), hex_vec3(3, 1, -1)],
        [hex_vec3(-4, 0, 8), hex_vec3(0, 6, 2)],
    ],
    outputs=[
        [hex_vec3(1, 2, 3)],
        [hex_vec3(2, -1, 2)],
        [hex_vec3(-2, 3, 5)],
    ]
)

# 2) manhattan (Variant 0)
new_riddles["manhattan.json"] = make_riddle(
    tooltip="text.abadoned_greatwork.riddles.manhattan",
    length_limit=12,
    variant=0,
    inputs=[
        [hex_vec3(0, 0, 0), hex_vec3(1, 2, 3)],
        [hex_vec3(2, -1, 4), hex_vec3(-1, 3, 0)],
        [hex_vec3(5, 5, 5), hex_vec3(5, 5, 5)],
    ],
    outputs=[
        [hex_double(6)],
        [hex_double(11)],
        [hex_double(0)],
    ]
)

# 3) list_reverse (Variant 0)
new_riddles["list_reverse.json"] = make_riddle(
    tooltip="text.abadoned_greatwork.riddles.list_reverse",
    length_limit=6,
    variant=0,
    inputs=[
        [hex_list([hex_double(1), hex_double(2)])],
        [hex_list([hex_vec3(1, 0, 0), hex_vec3(0, 1, 0)])],
        [hex_list([hex_double(0), hex_double(9)])],
    ],
    outputs=[
        [hex_list([hex_double(2), hex_double(1)])],
        [hex_list([hex_vec3(0, 1, 0), hex_vec3(1, 0, 0)])],
        [hex_list([hex_double(9), hex_double(0)])],
    ]
)

# 4) projection (Variant 1)
new_riddles["projection.json"] = make_riddle(
    tooltip="text.abadoned_greatwork.riddles.projection",
    length_limit=8,
    variant=1,
    inputs=[
        [hex_vec3(3, 4, 0), hex_vec3(1, 0, 0)],
        [hex_vec3(2, 5, 7), hex_vec3(0, 1, 0)],
        [hex_vec3(1, 2, 4), hex_vec3(0, 0, 1)],
    ],
    outputs=[
        [hex_vec3(3, 0, 0)],
        [hex_vec3(0, 5, 0)],
        [hex_vec3(0, 0, 4)],
    ]
)

# 5) reflection (Variant 1)
new_riddles["reflection.json"] = make_riddle(
    tooltip="text.abadoned_greatwork.riddles.reflection",
    length_limit=10,
    variant=1,
    inputs=[
        [hex_vec3(1, -1, 0), hex_vec3(0, 1, 0)],
        [hex_vec3(2, 0, -3), hex_vec3(0, 0, 1)],
        [hex_vec3(4, 3, 2), hex_vec3(1, 0, 0)],
    ],
    outputs=[
        [hex_vec3(1, 1, 0)],
        [hex_vec3(2, 0, 3)],
        [hex_vec3(-4, 3, 2)],
    ]
)

# 6) list_sum (Variant 1)
new_riddles["list_sum.json"] = make_riddle(
    tooltip="text.abadoned_greatwork.riddles.list_sum",
    length_limit=8,
    variant=1,
    inputs=[
        [hex_list([hex_double(1), hex_double(2), hex_double(3)])],
        [hex_list([hex_double(10), hex_double(-5), hex_double(2)])],
        [hex_list([hex_double(0), hex_double(0), hex_double(0)])],
    ],
    outputs=[
        [hex_double(6)],
        [hex_double(7)],
        [hex_double(0)],
    ]
)

# 7) collinear (Variant 1)
new_riddles["collinear.json"] = make_riddle(
    tooltip="text.abadoned_greatwork.riddles.collinear",
    length_limit=12,
    variant=1,
    inputs=[
        [hex_vec3(0, 0, 0), hex_vec3(1, 1, 1), hex_vec3(2, 2, 2)],
        [hex_vec3(0, 0, 0), hex_vec3(1, 0, 0), hex_vec3(0, 1, 0)],
        [hex_vec3(1, 2, 3), hex_vec3(2, 4, 6), hex_vec3(-1, -2, -3)],
    ],
    outputs=[
        [hex_bool(True)],
        [hex_bool(False)],
        [hex_bool(True)],
    ]
)

# 8) clamp (Variant 1)
new_riddles["clamp.json"] = make_riddle(
    tooltip="text.abadoned_greatwork.riddles.clamp",
    length_limit=10,
    variant=1,
    inputs=[
        [hex_double(5), hex_double(0), hex_double(10)],
        [hex_double(-3), hex_double(0), hex_double(10)],
        [hex_double(15), hex_double(0), hex_double(10)],
    ],
    outputs=[
        [hex_double(5)],
        [hex_double(0)],
        [hex_double(10)],
    ]
)

# 9) axial_neighbors (Variant 2)
def get_sorted_neighbors(cx, cy, cz):
    neighbors = [
        (cx, cy + 1, cz),
        (cx, cy - 1, cz),
        (cx + 1, cy, cz),
        (cx - 1, cy, cz),
        (cx, cy, cz + 1),
        (cx, cy, cz - 1),
    ]
    sorted_n = sorted(neighbors, key=lambda c: java_vec3_hash(*c))
    return [hex_vec3(*c) for c in sorted_n]

new_riddles["axial_neighbors.json"] = make_riddle(
    tooltip="text.abadoned_greatwork.riddles.axial_neighbors",
    length_limit=30,
    variant=2,
    inputs=[
        [hex_vec3(0, 0, 0)],
        [hex_vec3(10, 64, -5)],
    ],
    outputs=[
        [hex_list(get_sorted_neighbors(0, 0, 0))],
        [hex_list(get_sorted_neighbors(10, 64, -5))],
    ]
)

# 10) filter_positive (Variant 2)
new_riddles["filter_positive.json"] = make_riddle(
    tooltip="text.abadoned_greatwork.riddles.filter_positive",
    length_limit=20,
    variant=2,
    inputs=[
        [hex_list([hex_double(-1), hex_double(2), hex_double(-3)])],
        [hex_list([hex_double(3), hex_double(5), hex_double(0)])],
        [hex_list([hex_double(-2), hex_double(-4), hex_double(-1)])],
    ],
    outputs=[
        [hex_list([hex_double(2)])],
        [hex_list([hex_double(3), hex_double(5)])],
        [hex_list([])],
    ]
)

# 11) apply_twice (Variant 2)
inc_pattern = hex_list([
    hex_pattern("qaq", "NORTH_EAST"),
    hex_pattern("aqaa", "SOUTH_EAST"),
    hex_pattern("waaw", "NORTH_EAST"),
])
new_riddles["apply_twice.json"] = make_riddle(
    tooltip="text.abadoned_greatwork.riddles.apply_twice",
    length_limit=8,
    variant=2,
    inputs=[
        [hex_double(1), inc_pattern],
        [hex_double(5), inc_pattern],
        [hex_double(-2), inc_pattern],
    ],
    outputs=[
        [hex_double(3)],
        [hex_double(7)],
        [hex_double(0)],
    ]
)

# Write all new riddles
for fname, content in new_riddles.items():
    fpath = os.path.join(RIDDLES_DIR, fname)
    with open(fpath, "w", encoding="utf-8") as f:
        json.dump(content, f, indent=2, ensure_ascii=False)
    print(f"Created new riddle {fname}")

# 3. Add lang keys
zh_translations = {
    "text.abadoned_greatwork.crystal.wrong_round": "验证失败（第 %s 轮）：栈结果与期望输出不一致！",
    "text.abadoned_greatwork.crystal.length_exceed": "错误：手持法术总长度超过水晶上限（上限：%s）！",
    "text.abadoned_greatwork.riddles.midpoint": "执两用中，折半而求其心",
    "text.abadoned_greatwork.riddles.midpoint.long": "要求一个这样的图案列表：接受两个坐标向量，返回它们连线的中点坐标向量。",
    "text.abadoned_greatwork.riddles.manhattan": "纵横步履，折线而量远近",
    "text.abadoned_greatwork.riddles.manhattan.long": "要求一个这样的图案列表：接受两个三维向量，返回它们之间的曼哈顿距离（各分量绝对差值之和）。",
    "text.abadoned_greatwork.riddles.list_reverse": "颠倒首尾，逆流而溯其源",
    "text.abadoned_greatwork.riddles.list_reverse.long": "要求一个这样的图案列表：接受一个包含两个元素的列表，将其内部两个元素交换位置并返回翻转后的列表。",
    "text.abadoned_greatwork.riddles.projection": "借光投石，顺势而求其影",
    "text.abadoned_greatwork.riddles.projection.long": "要求一个这样的图案列表：接受两个向量 a 与 b（b 为单位方向向量），返回 a 在 b 方向上的投影向量（即 (a·b) * b）。",
    "text.abadoned_greatwork.riddles.reflection": "明镜止水，折光而现其形",
    "text.abadoned_greatwork.riddles.reflection.long": "要求一个这样的图案列表：接受入射向量 v 与单位法向量 n，返回镜面反射向量 v - 2*(v·n)*n。",
    "text.abadoned_greatwork.riddles.list_sum": "聚沙成塔，集腋以成其裘",
    "text.abadoned_greatwork.riddles.list_sum.long": "要求一个这样的图案列表：接受一个包含三个数值的列表，返回列表中三个数值的总和。",
    "text.abadoned_greatwork.riddles.collinear": "三星连珠，审势而辨曲直",
    "text.abadoned_greatwork.riddles.collinear.long": "要求一个这样的图案列表：接受三个点向量 a, b, c，判断它们是否共线，返回布尔值。",
    "text.abadoned_greatwork.riddles.clamp": "过犹不及，守度而立规矩",
    "text.abadoned_greatwork.riddles.clamp.long": "要求一个这样的图案列表：接受数值 x、下界 low、上界 high（low <= high），返回限制在 [low, high] 区间内的数值。",
    "text.abadoned_greatwork.riddles.axial_neighbors": "六合之内，毗邻而守四方",
    "text.abadoned_greatwork.riddles.axial_neighbors.long": "要求一个这样的图案列表：接受一个中心坐标向量，返回与其六个面直接相邻的 6 个坐标（上下东西南北）组成的列表，坐标向下取整，用忒弥斯之纯化排序。",
    "text.abadoned_greatwork.riddles.filter_positive": "去芜存菁，扬清而弃浊流",
    "text.abadoned_greatwork.riddles.filter_positive.long": "要求一个这样的图案列表：接受一个包含三个数值的列表，过滤掉其中所有小于等于 0 的数，仅保留正数返回。",
    "text.abadoned_greatwork.riddles.apply_twice": "二度演绎，复沓而臻化境",
    "text.abadoned_greatwork.riddles.apply_twice.long": "要求一个这样的图案列表：栈底为初始数值 x，栈顶为可执行图案列表 f，连续对 x 应用两次 f 并返回最终结果。",
}

en_translations = {
    "text.abadoned_greatwork.crystal.wrong_round": "Verification failed (Round %s): stack result does not match expected output!",
    "text.abadoned_greatwork.crystal.length_exceed": "Error: spell total length exceeds crystal limit (%s)!",
    "text.abadoned_greatwork.riddles.midpoint": "Hold the center, halve the distance to find the heart.",
    "text.abadoned_greatwork.riddles.midpoint.long": "Submit a list of patterns that takes two position vectors and returns the midpoint coordinate between them.",
    "text.abadoned_greatwork.riddles.manhattan": "Crisscross steps, measuring distance along gridlines.",
    "text.abadoned_greatwork.riddles.manhattan.long": "Submit a list of patterns that takes two vectors and returns the Manhattan distance between them (sum of absolute differences of components).",
    "text.abadoned_greatwork.riddles.list_reverse": "Reverse head and tail, tracing the stream upstream.",
    "text.abadoned_greatwork.riddles.list_reverse.long": "Submit a list of patterns that takes a list of two elements, swaps them, and returns the reversed list.",
    "text.abadoned_greatwork.riddles.projection": "Cast a shadow with light, finding the projection along the path.",
    "text.abadoned_greatwork.riddles.projection.long": "Submit a list of patterns that takes vector a and unit direction vector b, returning the projection vector of a onto b ((a·b) * b).",
    "text.abadoned_greatwork.riddles.reflection": "A still mirror on water, refracting light to reveal the angle.",
    "text.abadoned_greatwork.riddles.reflection.long": "Submit a list of patterns that takes incident vector v and unit normal vector n, returning the reflected vector v - 2*(v·n)*n.",
    "text.abadoned_greatwork.riddles.list_sum": "Gather grains into a mound, sum all parts into the whole.",
    "text.abadoned_greatwork.riddles.list_sum.long": "Submit a list of patterns that takes a list of three numbers and returns their sum.",
    "text.abadoned_greatwork.riddles.collinear": "Three stars in a line, judging straightness across space.",
    "text.abadoned_greatwork.riddles.collinear.long": "Submit a list of patterns that takes three position vectors a, b, c and returns a boolean indicating whether they are collinear.",
    "text.abadoned_greatwork.riddles.clamp": "Neither too much nor too little, bound within limits.",
    "text.abadoned_greatwork.riddles.clamp.long": "Submit a list of patterns that takes number x, lower bound low, and upper bound high, returning x clamped to [low, high].",
    "text.abadoned_greatwork.riddles.axial_neighbors": "Within the six directions, adjacent guards of the axes.",
    "text.abadoned_greatwork.riddles.axial_neighbors.long": "Submit a list of patterns that takes a position vector and returns its 6 direct axial adjacent neighbors (up, down, east, west, north, south), floored, sorted with Themistian Purification.",
    "text.abadoned_greatwork.riddles.filter_positive": "Discard the chaff, keeping only what is pure and positive.",
    "text.abadoned_greatwork.riddles.filter_positive.long": "Submit a list of patterns that takes a list of three numbers and filters out non-positive values, returning only positive numbers.",
    "text.abadoned_greatwork.riddles.apply_twice": "A second iteration, recurring to reach perfection.",
    "text.abadoned_greatwork.riddles.apply_twice.long": "Submit a list of patterns that takes number x at stack base and pattern list f at stack top, applying f twice to x and returning the result.",
}

with open(ZH_PATH, "r", encoding="utf-8") as f:
    zh_data = json.load(f)
zh_data.update(zh_translations)
with open(ZH_PATH, "w", encoding="utf-8") as f:
    json.dump(zh_data, f, indent=2, ensure_ascii=False)
print("Updated zh_cn.json")

with open(EN_PATH, "r", encoding="utf-8") as f:
    en_data = json.load(f)
en_data.update(en_translations)
with open(EN_PATH, "w", encoding="utf-8") as f:
    json.dump(en_data, f, indent=2, ensure_ascii=False)
print("Updated en_us.json")

print("All 18 riddles and translations successfully generated!")
