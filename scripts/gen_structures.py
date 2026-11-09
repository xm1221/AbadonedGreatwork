#!/usr/bin/env python3
"""程序化生成废弃法术环的结构 NBT（1.20.1，DataVersion 3465）。

用法：
    python scripts/gen_structures.py --preview          # 只打印俯视 ASCII 预览
    python scripts/gen_structures.py --write            # 落盘到 structures/
    python scripts/gen_structures.py --only ruined_circle_9 --write

设计约束（与既有结构保持一致）：
    * 结构底层 y=0 是自带地面层（结构用 start_height 绝对值放置，需要自带地面）
    * 主世界家族的 jigsaw 锚点都放在 y=3，与 ruined_circle_1/5 一致 → 地面落在同一高度
    * 只使用原版 + hexcasting 0.11.3 的方块，脚本里每一块都会跑白名单校验
"""
from __future__ import annotations

import argparse
import math
import random
import sys
from pathlib import Path

from nbtlib import Compound, Int, List, Long, String

sys.path.insert(0, str(Path(__file__).resolve().parent))

import struct_common as sc

DATA_VERSION = sc.DATA_VERSION
OVERWORLD_ANCHOR_Y = 3


# ----------------------------------------------------------------- 方块常量

GRASS = "minecraft:grass_block[snowy=false]"
DIRT = "minecraft:dirt"
COARSE = "minecraft:coarse_dirt"
STONE = "minecraft:stone"
COBBLE = "minecraft:cobblestone"
MOSSY_COBBLE = "minecraft:mossy_cobblestone"
STONE_BRICKS = "minecraft:stone_bricks"
MOSSY_BRICKS = "minecraft:mossy_stone_bricks"
CRACKED_BRICKS = "minecraft:cracked_stone_bricks"
CHISELED_BRICKS = "minecraft:chiseled_stone_bricks"
GRAVEL = "minecraft:gravel"
SAND = "minecraft:sand"
SANDSTONE = "minecraft:sandstone"
CUT_SANDSTONE = "minecraft:cut_sandstone"
SMOOTH_SANDSTONE = "minecraft:smooth_sandstone"
CHISELED_SANDSTONE = "minecraft:chiseled_sandstone"
DEAD_BUSH = "minecraft:dead_bush"
END_STONE = "minecraft:end_stone"
END_BRICKS = "minecraft:end_stone_bricks"
OBSIDIAN = "minecraft:obsidian"
PURPUR = "minecraft:purpur_block"
GRASS_TUFT = "minecraft:grass"
TALL_GRASS_LOW = "minecraft:tall_grass[half=lower]"
TALL_GRASS_HIGH = "minecraft:tall_grass[half=upper]"
LANTERN = "minecraft:lantern[hanging=false,waterlogged=false]"

SLATE = "hexcasting:slate_block"
SLATE_TILES = "hexcasting:slate_tiles"
SLATE_BRICKS = "hexcasting:slate_bricks"
SLATE_BRICKS_SMALL = "hexcasting:slate_bricks_small"
SLATE_PILLAR = "hexcasting:slate_pillar[axis=y]"
SLATE_AMETHYST_BRICKS = "hexcasting:slate_amethyst_bricks"
SLATE_AMETHYST_PILLAR = "hexcasting:slate_amethyst_pillar[axis=y]"
EDIFIED_PLANKS = "hexcasting:edified_planks"
EDIFIED_LOG = "hexcasting:edified_log[axis=y]"
EDIFIED_LOG_AMETHYST = "hexcasting:edified_log_amethyst[axis=y]"
EDIFIED_LOG_CITRINE = "hexcasting:edified_log_citrine[axis=y]"
EDIFIED_TILE = "hexcasting:edified_tile"
LEAVES_AMETHYST = "hexcasting:amethyst_edified_leaves[persistent=true,waterlogged=false]"
LEAVES_AVENTURINE = "hexcasting:aventurine_edified_leaves[persistent=true,waterlogged=false]"
LEAVES_CITRINE = "hexcasting:citrine_edified_leaves[persistent=true,waterlogged=false]"
CONJURED_LIGHT = "hexcasting:conjured_light[waterlogged=false]"
IMPETUS = "hexcasting:impetus/empty[facing=up,energized=false]"
DIRECTRIX = "hexcasting:directrix/empty[facing=%s,energized=false]"

SLATE_MIX = [SLATE, SLATE, SLATE, SLATE_BRICKS, SLATE_TILES, CRACKED_BRICKS, STONE_BRICKS]
RUBBLE_MIX = [COBBLE, COBBLE, MOSSY_COBBLE, GRAVEL, CRACKED_BRICKS]
SAND_MIX = [SANDSTONE, SANDSTONE, SMOOTH_SANDSTONE, CUT_SANDSTONE, SLATE_BRICKS]
END_MIX = [END_STONE, END_STONE, SLATE, OBSIDIAN, SLATE_BRICKS]
FACINGS = {"east": (1, 0), "west": (-1, 0), "south": (0, 1), "north": (0, -1)}


# ------------------------------------------------------------------- 构建器

class Template:
    def __init__(self, name, size, seed):
        self.name = name
        self.size = tuple(size)
        self.rng = random.Random(seed)
        self.cells = {}
        self.chests = []
        self.anchors = []

    # --- 基础写入
    def inside(self, x, y, z):
        return 0 <= x < self.size[0] and 0 <= y < self.size[1] and 0 <= z < self.size[2]

    def set(self, x, y, z, block):
        if not self.inside(x, y, z):
            raise ValueError(f"{self.name}: 坐标越界 {(x, y, z)} size={self.size}")
        self.cells[(x, y, z)] = block

    def clear(self, x, y, z):
        self.cells.pop((x, y, z), None)

    def get(self, x, y, z):
        return self.cells.get((x, y, z))

    def fill(self, x0, y0, z0, x1, y1, z1, block):
        for x in range(min(x0, x1), max(x0, x1) + 1):
            for y in range(min(y0, y1), max(y0, y1) + 1):
                for z in range(min(z0, z1), max(z0, z1) + 1):
                    self.set(x, y, z, block)

    def ground(self, block, edge=None, edge_width=1):
        """y=0 铺满整块地面；edge 为最外圈方块。"""
        for x in range(self.size[0]):
            for z in range(self.size[2]):
                on_edge = min(x, z, self.size[0] - 1 - x, self.size[2] - 1 - z) < edge_width
                self.set(x, 0, z, (edge or block) if on_edge else block)

    # --- 形状
    def ring_cells(self, cx, cz, radius):
        """中点画圆，返回 1 格宽的圆环格子（可能有斜角，装饰用足够）。"""
        cells = set()
        x, z, err = radius, 0, 1 - radius
        while x >= z:
            for dx, dz in ((x, z), (z, x), (-z, x), (-x, z),
                           (-x, -z), (-z, -x), (z, -x), (x, -z)):
                cells.add((cx + dx, cz + dz))
            z += 1
            if err < 0:
                err += 2 * z + 1
            else:
                x -= 1
                err += 2 * (z - x) + 1
        return sorted(cells)

    def ring(self, cx, cz, y, radius, blocks, gaps=(), gap_chance=0.0, thickness=1):
        """在 y 层铺一圈圆环；blocks 可为列表（随机挑）。"""
        block_list = blocks if isinstance(blocks, (list, tuple)) else [blocks]
        gap_set = set(gaps)
        for (x, z) in self.ring_cells(cx, cz, radius):
            if (x - (cx - radius), z - (cz - radius)) in gap_set:
                continue
            if gap_chance and self.rng.random() < gap_chance:
                continue
            self.set(x, y, z, self.rng.choice(block_list))

    def track_loop(self, cx, cz, half, chamfer):
        """4-连通闭合环（切角方环）。返回有序格子序列，相邻两格必为轴相邻。"""
        cells = []
        y = 0  # 只用于占位，真正高度由调用方决定
        # 底边：(-h+c, +h) -> (+h-c, +h)
        for x in range(-half + chamfer, half - chamfer + 1):
            cells.append((x, half))
        # 右下阶梯：(+h-c+1, +h) -> (+h, +h-c)
        x, z = half - chamfer, half
        for _ in range(chamfer):
            x += 1
            cells.append((x, z))
            z -= 1
            cells.append((x, z))
        # 右边：-> (h, -h+c)
        for z2 in range(half - chamfer - 1, -half + chamfer - 1, -1):
            cells.append((half, z2))
        # 右上阶梯：(h, -h+c-1) -> (h-c, -h)
        x, z = half, -half + chamfer
        for _ in range(chamfer):
            z -= 1
            cells.append((x, z))
            x -= 1
            cells.append((x, z))
        # 顶边：-> (-h+c, -h)
        for x2 in range(half - chamfer - 1, -half + chamfer - 1, -1):
            cells.append((x2, -half))
        # 左上阶梯：(-h+c-1, -h) -> (-h, -h+c)
        x, z = -half + chamfer, -half
        for _ in range(chamfer):
            x -= 1
            cells.append((x, z))
            z += 1
            cells.append((x, z))
        # 左边：-> (-h, h-c)
        for z2 in range(-half + chamfer + 1, half - chamfer + 1):
            cells.append((-half, z2))
        # 左下阶梯：(-h, h-c+1) -> (-h+c, h)
        x, z = -half, half - chamfer
        for _ in range(chamfer):
            z += 1
            cells.append((x, z))
            x += 1
            cells.append((x, z))
        # 去重（保留首次出现）并闭合检查
        seen = set()
        ordered = []
        for cell in cells:
            if cell in seen:
                continue
            seen.add(cell)
            ordered.append(cell)
        ordered = [(cx + x, cz + z) for (x, z) in ordered]
        return ordered

    def chest(self, x, y, z, loot, facing="south"):
        if not self.inside(x, y, z):
            raise ValueError(f"{self.name}: 宝箱越界 {(x, y, z)}")
        if self.get(x, y + 1, z) is not None or self.get(x, y, z) is not None:
            raise ValueError(f"{self.name}: 宝箱 {(x, y, z)} 位置被占用")
        self.chests.append((x, y, z, loot, facing))
        self.cells[(x, y, z)] = f"minecraft:chest[facing={facing},type=single,waterlogged=false]"

    def anchor(self, name, x, y, z):
        if self.get(x, y, z) is not None:
            raise ValueError(f"{self.name}: 锚点 {(x, y, z)} 位置被方块占用")
        self.anchors.append((name, x, y, z))

    def scatter(self, y, blocks, chance, skip=()):
        for x in range(1, self.size[0] - 1):
            for z in range(1, self.size[2] - 1):
                if (x, z) in skip:
                    continue
                if self.get(x, y, z) is None and self.get(x, y - 1, z) is not None and self.rng.random() < chance:
                    self.set(x, y, z, blocks if isinstance(blocks, str) else self.rng.choice(blocks))

    # --- 输出
    def preview(self, layers=None):
        print(f"== {self.name} size={self.size}")
        heights = sorted({y for (_, y, _) in self.cells})
        for y in (layers if layers is not None else heights):
            print(f"-- y={y}")
            for z in range(self.size[2]):
                row = []
                for x in range(self.size[0]):
                    block = self.get(x, y, z)
                    if block is None:
                        row.append(".")
                    elif block.startswith("minecraft:chest"):
                        row.append("C")
                    elif block.startswith("minecraft:jigsaw"):
                        row.append("J")
                    elif "impetus" in block:
                        row.append("I")
                    elif "directrix" in block:
                        row.append("D")
                    elif block == "hexcasting:slate":
                        row.append("S")
                    elif "grass" in block:
                        row.append(",")
                    elif "leaves" in block or "log" in block:
                        row.append("T")
                    elif "water" in block:
                        row.append("~")
                    else:
                        row.append("#")
                print("".join(row))

    def to_nbt(self):
        palette = []
        index_by_text = {}
        blocks = []
        for key in sorted(self.cells.keys(), key=lambda p: (p[1], p[2], p[0])):
            text = self.cells[key]
            # 宝箱要带 nbt，统一在下面单独写，这里跳过免得重复
            if text.startswith("minecraft:chest"):
                continue
            if text not in index_by_text:
                index_by_text[text] = len(palette)
                palette.append(sc.make_palette_entry(text))
            blocks.append(Compound({
                "pos": List[Int]([Int(key[0]), Int(key[1]), Int(key[2])]),
                "state": Int(index_by_text[text]),
            }))

        jigsaw_index = None
        for (name, x, y, z) in self.anchors:
            if jigsaw_index is None:
                text = "minecraft:jigsaw"
                if text not in index_by_text:
                    index_by_text[text] = len(palette)
                    palette.append(sc.make_palette_entry(text))
                jigsaw_index = index_by_text[text]
            blocks.append(Compound({
                "pos": List[Int]([Int(x), Int(y), Int(z)]),
                "state": Int(jigsaw_index),
                "nbt": Compound({
                    "name": String(name),
                    "pool": String("minecraft:empty"),
                    "target": String("minecraft:empty"),
                    "joint": String("rollable"),
                    "final_state": String("minecraft:air"),
                }),
            }))

        for (x, y, z, loot, facing) in self.chests:
            text = f"minecraft:chest[facing={facing},type=single,waterlogged=false]"
            if text not in index_by_text:
                index_by_text[text] = len(palette)
                palette.append(sc.make_palette_entry(text))
            blocks.append(Compound({
                "pos": List[Int]([Int(x), Int(y), Int(z)]),
                "state": Int(index_by_text[text]),
                "nbt": Compound({
                    "id": String("minecraft:chest"),
                    "LootTable": String(loot),
                    "LootTableSeed": Long(self.rng.randint(-2**62, 2**62)),
                }),
            }))

        return Compound({
            "size": List[Int]([Int(v) for v in self.size]),
            "entities": List[Compound]([]),
            "blocks": List[Compound](blocks),
            "palette": List[Compound](palette),
            "DataVersion": Int(DATA_VERSION),
        })

# --------------------------------------------------------------- 战利品/锚点

OVERWORLD_LOOT = "minecraft:chests/ruined_circles_overworld"
END_LOOT = "minecraft:chests/ruined_circles_end"
DESERT_LOOT = "minecraft:chests/ruined_circles_desert"

OW_ANCHOR = "abadoned_greatwork:ruined_circles_overworld"
END_ANCHOR = "abadoned_greatwork:ruined_circles_end"
DESERT_ANCHOR = "abadoned_greatwork:ruined_circles_desert"

EDIFIED_LOG_X = "hexcasting:edified_log[axis=x]"
EDIFIED_LOG_Z = "hexcasting:edified_log[axis=z]"


def octagon_inside(dx, dz, half, chamfer):
    """判断格子是否在切角方环的内部（与 Template.track_loop 同参数）。"""
    return max(abs(dx), abs(dz)) <= half - 1 and abs(dx) + abs(dz) <= 2 * half - chamfer - 1


# --------------------------------------------------------------- 具体结构

def piece_small_ring():
    """ruined_circle_3：9x9 的小环，中心还立着一颗空的促动石。"""
    t = Template("ruined_circle_3", (9, 5, 9), seed=1003)
    t.ground(GRASS, edge=COARSE)
    t.ring(4, 4, 1, 3, SLATE_MIX, gap_chance=0.08)
    t.set(4, 1, 4, IMPETUS)
    t.chest(2, 1, 4, OVERWORLD_LOOT, facing="east")
    t.chest(6, 1, 4, OVERWORLD_LOOT, facing="west")
    t.scatter(1, GRASS_TUFT, 0.18)
    t.anchor(OW_ANCHOR, 4, OVERWORLD_ANCHOR_Y, 4)
    return t


def piece_double_ring():
    """ruined_circle_7：19x19 双环，外环立四根导引石柱。"""
    t = Template("ruined_circle_7", (19, 9, 19), seed=1007)
    t.ground(GRASS, edge=COARSE, edge_width=2)
    t.ring(9, 9, 1, 8, SLATE_MIX, gap_chance=0.16)
    t.ring(9, 9, 1, 4, SLATE_MIX, gap_chance=0.10)
    t.fill(8, 1, 8, 10, 1, 10, SLATE_BRICKS)
    t.set(9, 2, 9, IMPETUS)
    for (px, pz) in ((9, 1), (17, 9), (9, 17), (1, 9)):
        t.set(px, 1, pz, SLATE_PILLAR)
        t.set(px, 2, pz, SLATE_PILLAR)
        t.set(px, 3, pz, CONJURED_LIGHT)
    t.chest(6, 1, 9, OVERWORLD_LOOT, facing="east")
    t.chest(12, 1, 9, OVERWORLD_LOOT, facing="west")
    t.chest(9, 1, 14, OVERWORLD_LOOT, facing="north")
    t.scatter(1, GRASS_TUFT, 0.12)
    t.anchor(OW_ANCHOR, 9, OVERWORLD_ANCHOR_Y, 9)
    return t


def piece_collapsed():
    """ruined_circle_8：13x13，西半侧塌成一堆碎石，东半侧还站着半截柱。"""
    t = Template("ruined_circle_8", (13, 8, 13), seed=1008)
    t.ground(GRASS, edge=COARSE)
    ring = t.ring_cells(6, 6, 5)
    for (x, z) in ring:
        roll = t.rng.random()
        if x < 6:
            if roll < 0.55:
                t.set(x, 1, z, t.rng.choice(RUBBLE_MIX))
            elif roll < 0.80:
                continue
            else:
                t.set(x, 1, z, t.rng.choice(SLATE_MIX))
        else:
            if roll < 0.12:
                continue
            t.set(x, 1, z, t.rng.choice(SLATE_MIX))
    # 塌下来的石块堆，把西半侧的环埋掉一部分
    t.fill(1, 1, 4, 3, 2, 8, COBBLE)
    t.fill(2, 1, 5, 2, 1, 7, MOSSY_COBBLE)
    # 东侧残留的柱子
    pillar = next((cell for cell in ring if cell[0] > 6 and cell[1] == 6), None)
    if pillar is not None:
        for y in (1, 2, 3):
            t.set(pillar[0], y, pillar[1], SLATE_PILLAR)
        t.set(pillar[0], 4, pillar[1], SLATE_BRICKS_SMALL)
    t.chest(4, 1, 6, OVERWORLD_LOOT, facing="east")
    t.chest(8, 1, 6, OVERWORLD_LOOT, facing="west")
    t.scatter(1, GRASS_TUFT, 0.16)
    t.anchor(OW_ANCHOR, 6, OVERWORLD_ANCHOR_Y, 6)
    return t


def piece_track():
    """ruined_circle_9：近乎完整、能连起来的导引石环路，只差两格。"""
    t = Template("ruined_circle_9", (15, 7, 15), seed=1009)
    t.ground(GRASS, edge=COARSE)
    loop = t.track_loop(7, 7, 6, 4)
    for index in range(len(loop)):
        x, z = loop[index]
        nx, nz = loop[(index + 1) % len(loop)]
        dx, dz = nx - x, nz - z
        if (abs(dx) + abs(dz)) != 1:
            raise ValueError(f"环路不连续：{loop[index]} -> {loop[index + 1]}")
    # 环内铺石板地板（只铺八边形内部，别铺到环外面去）
    for x in range(1, 14):
        for z in range(1, 14):
            if octagon_inside(x - 7, z - 7, 6, 4):
                t.set(x, 1, z, SLATE_TILES if (x + z) % 5 else SLATE_BRICKS)
    gaps = {len(loop) // 4, (3 * len(loop)) // 4}
    impetus_index = len(loop) // 2
    for index, (x, z) in enumerate(loop):
        if index in gaps:
            t.clear(x, 1, z)
            continue
        nx, nz = loop[(index + 1) % len(loop)]
        facing = {(1, 0): "east", (-1, 0): "west", (0, 1): "south", (0, -1): "north"}[(nx - x, nz - z)]
        # 促动石必须自己也在环上，媒质才有地方回去
        if index == impetus_index:
            t.set(x, 1, z, f"hexcasting:impetus/empty[facing={facing},energized=false]")
        else:
            t.set(x, 1, z, DIRECTRIX % facing)
    t.set(7, 2, 7, CONJURED_LIGHT)
    t.chest(1, 1, 1, OVERWORLD_LOOT, facing="south")
    t.chest(13, 1, 13, OVERWORLD_LOOT, facing="north")
    t.scatter(2, GRASS_TUFT, 0.05)
    t.scatter(1, RUBBLE_MIX, 0.05)
    t.anchor(OW_ANCHOR, 7, OVERWORLD_ANCHOR_Y, 7)
    return t


def piece_trees():
    """ruined_circle_10：被六重环树侵占的环，树干直接顶穿石板。"""
    t = Template("ruined_circle_10", (15, 12, 15), seed=1010)
    t.ground(GRASS, edge=COARSE)
    t.ring(7, 7, 1, 6, SLATE_MIX, gap_chance=0.22)
    trees = (
        (4, 4, EDIFIED_LOG, LEAVES_AMETHYST, 4),
        (10, 10, EDIFIED_LOG_CITRINE, LEAVES_CITRINE, 3),
        (11, 3, EDIFIED_LOG_AMETHYST, LEAVES_AVENTURINE, 4),
    )
    for (tx, tz, log, leaves, height) in trees:
        for y in range(1, 1 + height):
            t.set(tx, y, tz, log)
        top = height + 1
        for dx in range(-2, 3):
            for dz in range(-2, 3):
                if abs(dx) == 2 and abs(dz) == 2:
                    continue
                for y in (top, top + 1):
                    x, z = tx + dx, tz + dz
                    if not t.inside(x, y, z) or t.get(x, y, z) is not None:
                        continue
                    if y == top + 1 and abs(dx) + abs(dz) > 2:
                        continue
                    t.set(x, y, z, leaves)
    # 倒下的树干
    t.set(6, 1, 10, EDIFIED_LOG_X)
    t.set(7, 1, 10, EDIFIED_LOG_X)
    t.chest(7, 1, 4, OVERWORLD_LOOT, facing="south")
    t.chest(3, 1, 7, OVERWORLD_LOOT, facing="east")
    t.scatter(1, GRASS_TUFT, 0.20)
    t.anchor(OW_ANCHOR, 7, OVERWORLD_ANCHOR_Y, 7)
    return t


def piece_end_1():
    """ruined_circle_end_1：末地浮空环，碎块悬在环的正上方。"""
    t = Template("ruined_circle_end_1", (11, 8, 11), seed=1101)
    t.ground(END_STONE, edge=END_BRICKS)
    t.ring(5, 5, 1, 4, END_MIX, gap_chance=0.15)
    t.set(5, 1, 5, IMPETUS)
    for _ in range(7):
        x = t.rng.randint(1, 9)
        z = t.rng.randint(1, 9)
        y = t.rng.choice((4, 5, 6))
        if (x, y, z) == (5, OVERWORLD_ANCHOR_Y, 5):
            continue
        if t.get(x, y, z) is None:
            t.set(x, y, z, t.rng.choice((END_BRICKS, END_STONE, OBSIDIAN)))
    t.chest(3, 1, 5, END_LOOT, facing="east")
    t.chest(7, 1, 5, END_LOOT, facing="west")
    t.anchor(END_ANCHOR, 5, OVERWORLD_ANCHOR_Y, 5)
    return t


def piece_end_2():
    """ruined_circle_end_2：立起来的紫珀环，像一扇还立在末地石上的门。"""
    t = Template("ruined_circle_end_2", (13, 10, 13), seed=1102)
    t.ground(END_STONE, edge=END_BRICKS)
    for (dx, dy) in t.ring_cells(0, 0, 4):
        t.set(6 + dx, 5 + dy, 6, t.rng.choice((PURPUR, OBSIDIAN, SLATE_BRICKS, END_BRICKS)))
    t.ring(6, 6, 1, 5, END_MIX, gap_chance=0.25)
    t.set(6, 1, 6, SLATE_TILES)
    t.set(6, 2, 6, OBSIDIAN)
    t.chest(3, 1, 4, END_LOOT, facing="east")
    t.chest(9, 1, 8, END_LOOT, facing="west")
    t.anchor(END_ANCHOR, 6, OVERWORLD_ANCHOR_Y, 6)
    return t


def piece_desert_1():
    """ruined_circle_desert_1：被沙丘埋掉一半的环。"""
    t = Template("ruined_circle_desert_1", (13, 7, 13), seed=1201)
    t.ground(SAND, edge=SANDSTONE)
    ring = t.ring_cells(6, 6, 5)
    for (x, z) in ring:
        if x < 6 and t.rng.random() < 0.6:
            t.set(x, 1, z, SAND)
            t.set(x, 2, z, t.rng.choice((SAND, SANDSTONE)))
            continue
        if t.rng.random() < 0.18:
            continue
        t.set(x, 1, z, t.rng.choice(SAND_MIX))
    t.fill(1, 1, 3, 4, 1, 8, SAND)
    t.set(6, 1, 6, IMPETUS)
    t.chest(8, 1, 6, DESERT_LOOT, facing="west")
    t.chest(4, 1, 9, DESERT_LOOT, facing="east")
    t.scatter(1, DEAD_BUSH, 0.08)
    t.scatter(2, SAND, 0.10)
    t.anchor(DESERT_ANCHOR, 6, OVERWORLD_ANCHOR_Y, 6)
    return t


def piece_desert_2():
    """ruined_circle_desert_2：9x9 小环，四角立着砂岩残柱。"""
    t = Template("ruined_circle_desert_2", (9, 9, 9), seed=1202)
    t.ground(SAND, edge=SANDSTONE)
    t.ring(4, 4, 1, 3, SAND_MIX, gap_chance=0.12)
    t.set(4, 1, 4, IMPETUS)
    for (px, pz) in ((1, 1), (7, 1), (1, 7), (7, 7)):
        for y in range(1, 5):
            t.set(px, y, pz, t.rng.choice((SANDSTONE, CUT_SANDSTONE, SMOOTH_SANDSTONE)))
        t.set(px, 5, pz, CHISELED_SANDSTONE)
    t.chest(4, 1, 2, DESERT_LOOT, facing="south")
    t.chest(4, 1, 6, DESERT_LOOT, facing="north")
    t.scatter(1, DEAD_BUSH, 0.10)
    t.anchor(DESERT_ANCHOR, 4, OVERWORLD_ANCHOR_Y, 4)
    return t


PIECES = (
    piece_small_ring,
    piece_double_ring,
    piece_collapsed,
    piece_track,
    piece_trees,
    piece_end_1,
    piece_end_2,
    piece_desert_1,
    piece_desert_2,
)


# ------------------------------------------------------------------ 校验/输出

def check_blocks(template, known):
    bad = []
    for key, text in template.cells.items():
        block_id = sc.parse_block(text)[0]
        if block_id == "minecraft:jigsaw":
            continue
        if block_id not in known:
            bad.append(f"{template.name}: {key} -> {block_id}")
    for (name, x, y, z) in template.anchors:
        if not name.startswith("abadoned_greatwork:"):
            bad.append(f"{template.name}: 锚点名 {name} 命名空间不对")
    return sorted(set(bad))


def main(argv=None):
    parser = argparse.ArgumentParser(description="生成废弃法术环结构")
    parser.add_argument("--write", action="store_true", help="落盘到 structures/")
    parser.add_argument("--preview", action="store_true", help="打印俯视 ASCII 预览")
    parser.add_argument("--only", default=None, help="只处理指定结构名")
    parser.add_argument("--force", action="store_true", help="覆盖已存在的 NBT")
    args = parser.parse_args(argv)

    known = sc.known_block_ids()
    failed = False
    for builder in PIECES:
        template = builder()
        if args.only and template.name != args.only:
            continue
        problems = check_blocks(template, known)
        if problems:
            failed = True
            print(f"[方块校验失败] {template.name}")
            for problem in problems:
                print("    " + problem)
            continue
        if args.preview:
            template.preview()
        if args.write:
            path = sc.save_structure(sc.STRUCT_DIR / f"{template.name}.nbt", template.to_nbt(), force=args.force)
            print(f"写出 {path.relative_to(sc.ROOT)}  blocks={len(template.cells)} chests={len(template.chests)}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
