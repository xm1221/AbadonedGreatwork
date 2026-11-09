#!/usr/bin/env python3
"""结构内容校验：方块白名单、NBT 完整性、worldgen 引用完整性、lang 键一致性。

用法：
    .venv/Scripts/python.exe scripts/validate_structures.py
退出码 0 表示全部通过（允许 warning）；1 表示存在 error。
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import struct_common as sc

ERRORS = []
WARNINGS = []
INFOS = []
BIOME_TAGS = set()

# 旧内容里引用的其它附属方块：本次不修改，只提示，不算错误
LEGACY_THIRD_PARTY = {
    "ephemera:fakeslate",
    "hexical:pedestal",
    "hexdebug:splicing_table",
    "hexdebug:focus_holder",
    "hierophantics:flay_bed",
    "slate_work:hotbar_loci",
    "miehex:man_made_stone",
}


def err(message):
    ERRORS.append(message)


def warn(message):
    WARNINGS.append(message)


def info(message):
    INFOS.append(message)


def _load_all_structures():
    return {path.stem: sc.load_structure(path) for path in sorted(sc.STRUCT_DIR.glob("*.nbt"))}


def _pool_templates(pool):
    """返回 pool 里所有 single_pool_element 的模板 id。"""
    out = []
    for element in pool.get("elements", []):
        body = element.get("element", {})
        if body.get("element_type") == "minecraft:single_pool_element":
            out.append(body.get("location", ""))
        else:
            warn(f"template_pool 元素类型 {body.get('element_type')} 未做引用检查")
    return out


def _template_names(loaded, template_id):
    """'abadoned_greatwork:ruined_circle_1' -> 载入后的 jigsaw name 集合。"""
    if ":" not in template_id:
        return set()
    namespace, path = template_id.split(":", 1)
    if namespace != "abadoned_greatwork":
        return set()
    root = loaded.get(path)
    if root is None:
        return set()
    return {jig["name"] for jig in sc.jigsaw_infos(root)}


def check_nbt(loaded, known_blocks, vanilla_loot, hex_loot, loot_paths):
    for name, root in loaded.items():
        label = f"{name}.nbt"
        if "DataVersion" not in root:
            err(f"{label}: 缺少 DataVersion")
        elif int(root["DataVersion"]) != sc.DATA_VERSION:
            warn(f"{label}: DataVersion={int(root['DataVersion'])}（1.20.1 为 {sc.DATA_VERSION}）")

        size = tuple(int(v) for v in root["size"])
        if min(size) <= 0:
            err(f"{label}: size={size} 非法")
        palette = sc.palette_ids(root)
        if not palette:
            err(f"{label}: palette 为空")
        blocks = root["blocks"]
        if len(blocks) == 0:
            err(f"{label}: 结构为空（0 方块）")
            continue

        for block_id in set(palette):
            if block_id == "minecraft:jigsaw":
                continue
            if block_id in known_blocks:
                continue
            if block_id in LEGACY_THIRD_PARTY:
                warn(f"{label}: 历史遗留的第三方方块 {block_id}（本次未改动）")
            else:
                err(f"{label}: 方块 {block_id} 不在 原版/hexcasting {sc.HEX_VERSION} 白名单里")

        seen = set()
        chests = 0
        for pos, block_id, props, nbt in sc.block_entries(root):
            if any(coord < 0 or coord >= limit for coord, limit in zip(pos, size)):
                err(f"{label}: 方块 {block_id} 越界 pos={pos} size={size}")
            if pos in seen:
                err(f"{label}: 重复方块坐标 {pos}")
            seen.add(pos)
            if block_id == "minecraft:chest":
                chests += 1
                if nbt is None or "LootTable" not in nbt:
                    warn(f"{label}: 宝箱 {pos} 没有 LootTable")
                    continue
                table = str(nbt["LootTable"])
                namespace, _, path = table.partition(":")
                resolved = False
                if namespace == "abadoned_greatwork":
                    resolved = path in loot_paths
                elif namespace == "minecraft":
                    resolved = table in vanilla_loot or path in loot_paths
                elif namespace == "hexcasting":
                    resolved = table in hex_loot
                if not resolved:
                    err(f"{label}: 宝箱 {pos} 的战利品表 {table} 不存在")
        if chests == 0:
            info(f"{label}: 无宝箱")

        for jig in sc.jigsaw_infos(root):
            if jig["pool"].startswith("miehex:"):
                err(f"{label}: jigsaw {jig['pos']} 的 pool 仍指向旧命名空间 {jig['pool']}")
            if jig["name"].startswith("miehex:"):
                err(f"{label}: jigsaw {jig['pos']} 的 name 仍指向旧命名空间 {jig['name']}")
            if jig["target"].startswith("miehex:"):
                err(f"{label}: jigsaw {jig['pos']} 的 target 仍指向旧命名空间 {jig['target']}")


def check_worldgen(loaded, known_pools, pool_templates, structure_names):
    structures = {}
    for path in sorted((sc.WORLDGEN / "structure").glob("*.json")):
        data = sc.read_json(path)
        structures[path.stem] = data
        label = f"structure/{path.stem}.json"
        if data.get("type") != "minecraft:jigsaw":
            err(f"{label}: type 应为 minecraft:jigsaw")
        pool = data.get("start_pool", "")
        if pool not in known_pools:
            err(f"{label}: start_pool {pool} 不存在")
            continue
        start_name = data.get("start_jigsaw_name")
        templates = pool_templates.get(pool, [])
        if not templates:
            err(f"{label}: start_pool {pool} 没有任何 single_pool_element")
        for template in templates:
            names = _template_names(loaded, template)
            if not names:
                err(f"{label}: 元素模板 {template} 无法解析或没有 jigsaw 锚点")
                continue
            if start_name and start_name not in names:
                err(f"{label}: 元素 {template} 缺少 start_jigsaw_name={start_name} 锚点")
        biomes = data.get("biomes", "")
        if isinstance(biomes, str) and biomes.startswith("#"):
            if biomes not in BIOME_TAGS:
                err(f"{label}: biomes 标签 {biomes} 不存在")
        if "start_height" not in data:
            err(f"{label}: 缺少 start_height")

    pools = {}
    pool_templates_all = {}
    for path in sorted((sc.WORLDGEN / "template_pool").glob("*.json")):
        data = sc.read_json(path)
        pools[path.stem] = data
        label = f"template_pool/{path.stem}.json"
        if data.get("name") != f"abadoned_greatwork:{path.stem}":
            err(f"{label}: name 字段为 {data.get('name')}，应为 abadoned_greatwork:{path.stem}")
        fallback = data.get("fallback", "")
        if fallback not in ("minecraft:empty",) and fallback not in known_pools:
            err(f"{label}: fallback {fallback} 不存在")
        if not data.get("elements"):
            err(f"{label}: elements 为空")
        for template in _pool_templates(data):
            if template.startswith("abadoned_greatwork:") and template.split(":", 1)[1] not in loaded:
                err(f"{label}: 元素模板 {template} 无对应 NBT")
        pool_templates_all[f"abadoned_greatwork:{path.stem}"] = _pool_templates(data)

    # 连接器连通性：某个 jigsaw 的 target 必须能在它自己声明的 pool 里被同名连接器接住
    names_by_pool = {}
    for pool_name, templates in pool_templates_all.items():
        names = set()
        for template in templates:
            names |= _template_names(loaded, template)
        names_by_pool[pool_name] = names
    checked_templates = set()
    for templates in pool_templates_all.values():
        for template in templates:
            if template in checked_templates:
                continue
            checked_templates.add(template)
            path_name = template.split(":", 1)[1] if ":" in template else template
            root = loaded.get(path_name)
            if root is None:
                continue
            for jig in sc.jigsaw_infos(root):
                if jig["target"] in ("", "minecraft:empty"):
                    continue
                if jig["pool"] not in names_by_pool:
                    err(f"{path_name}.nbt: jigsaw target {jig['target']} 指向的 pool {jig['pool']} 不存在")
                elif jig["target"] not in names_by_pool[jig["pool"]]:
                    err(f"{path_name}.nbt: jigsaw target {jig['target']} 在 pool {jig['pool']} 里没有同名连接器可接")

    for path in sorted((sc.WORLDGEN / "structure_set").glob("*.json")):
        data = sc.read_json(path)
        label = f"structure_set/{path.stem}.json"
        for entry in data.get("structures", []):
            if entry.get("structure") not in structure_names:
                err(f"{label}: 引用不存在的结构 {entry.get('structure')}")
        placement = data.get("placement", {})
        kind = placement.get("type")
        if kind not in ("minecraft:random_spread", "minecraft:concentric_rings"):
            err(f"{label}: 未知 placement 类型 {kind}")
        if kind == "minecraft:random_spread":
            spacing = placement.get("spacing")
            separation = placement.get("separation")
            if not isinstance(spacing, int) or spacing <= 0:
                err(f"{label}: spacing 非法 {spacing}")
            if not isinstance(separation, int) or separation < 0:
                err(f"{label}: separation 非法 {separation}")
            if isinstance(spacing, int) and isinstance(separation, int) and separation >= spacing:
                err(f"{label}: separation({separation}) 必须小于 spacing({spacing})")
        if "salt" not in placement:
            err(f"{label}: 缺少 salt")
    return structures, pools


def check_lang():
    zh = sc.lang_keys("zh_cn")
    en = sc.lang_keys("en_us")
    for key in sorted(en - zh):
        err(f"lang: en_us 有但 zh_cn 缺少 {key}")
    for key in sorted(zh - en):
        err(f"lang: zh_cn 有但 en_us 缺少 {key}")

    referenced = set()
    for path in sorted((sc.MOD_DATA / "advancements").glob("*.json")):
        data = sc.read_json(path)
        display = data.get("display", {})
        for field in ("title", "description"):
            value = display.get(field, {})
            if isinstance(value, dict) and "translate" in value:
                referenced.add(value["translate"])

    books = sc.ROOT / "common" / "src" / "main" / "resources" / "assets" / "hexcasting" / "patchouli_books" / "thehexbook" / "en_us" / "entries"
    for path in sorted(books.rglob("*.json")):
        data = sc.read_json(path)
        if "name" in data:
            referenced.add(data["name"])
        for page in data.get("pages", []):
            if "text" in page:
                referenced.add(page["text"])

    for key in sorted(referenced):
        if key not in zh or key not in en:
            err(f"lang: 被引用但缺失的键 {key}（advancements 或手册）")


def main():
    loaded = _load_all_structures()
    known_blocks = sc.known_block_ids()
    vanilla_loot = sc.vanilla_loot_ids()
    hex_loot = sc.hexcasting_loot_ids()
    loot_paths = set()
    for base in (sc.RESOURCES_DATA / "minecraft" / "loot_tables", sc.MOD_DATA / "loot_tables"):
        if base.is_dir():
            loot_paths.update(p.relative_to(base).with_suffix("").as_posix() for p in base.rglob("*.json"))
    BIOME_TAGS.update(sc.vanilla_biome_tag_ids())
    BIOME_TAGS.update(sc.mod_biome_tag_ids())

    known_pools = {f"abadoned_greatwork:{path.stem}" for path in (sc.WORLDGEN / "template_pool").glob("*.json")}
    known_pools.add("minecraft:empty")
    structure_names = {f"abadoned_greatwork:{path.stem}" for path in (sc.WORLDGEN / "structure").glob("*.json")}
    pool_templates = {}
    for path in (sc.WORLDGEN / "template_pool").glob("*.json"):
        pool_templates[f"abadoned_greatwork:{path.stem}"] = _pool_templates(sc.read_json(path))

    check_nbt(loaded, known_blocks, vanilla_loot, hex_loot, loot_paths)
    check_worldgen(loaded, known_pools, pool_templates, structure_names)
    check_lang()

    print(f"结构 NBT: {len(loaded)} 个；方块白名单: {len(known_blocks)} 个")
    print(f"结构: {len(structure_names)} 个；模板池: {len(known_pools) - 1} 个")
    if INFOS:
        print("\n[INFO]")
        for message in INFOS:
            print("  " + message)
    if WARNINGS:
        print("\n[WARN]")
        for message in WARNINGS:
            print("  " + message)
    if ERRORS:
        print("\n[ERROR]")
        for message in ERRORS:
            print("  " + message)
        print(f"\n失败：{len(ERRORS)} 个错误，{len(WARNINGS)} 个警告")
        return 1
    print(f"\n通过：0 个错误，{len(WARNINGS)} 个警告")
    return 0


if __name__ == "__main__":
    sys.exit(main())