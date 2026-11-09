#!/usr/bin/env python3
"""结构 NBT / worldgen JSON 的共用工具。

- 读写 1.20.1 结构 NBT（DataVersion 3465，gzip，根标签名为空）
- 从本机 gradle / loom 缓存里提取合法方块 id 白名单
- worldgen 目录、战利品表、lang 文件的定位
"""
from __future__ import annotations

import glob
import json
import os
import zipfile
from pathlib import Path

from nbtlib import Compound, File, Int, List, String

ROOT = Path(__file__).resolve().parent.parent
MOD_DATA = ROOT / "common" / "src" / "main" / "resources" / "data" / "abadoned_greatwork"
STRUCT_DIR = MOD_DATA / "structures"
WORLDGEN = MOD_DATA / "worldgen"
RESOURCES_DATA = ROOT / "common" / "src" / "main" / "resources" / "data"
CHEST_LOOT_DIR = RESOURCES_DATA / "minecraft" / "loot_tables" / "chests"
GAMEPLAY_LOOT_DIR = MOD_DATA / "loot_tables" / "gameplay"
LANG_DIR = ROOT / "common" / "src" / "main" / "resources" / "assets" / "abadoned_greatwork" / "lang"

DATA_VERSION = 3465
HEX_VERSION = "0.11.3"


# ---------------------------------------------------------------- NBT 读写

def load_structure(name_or_path):
    """载入结构 NBT，返回根 Compound。name 可带或不带 .nbt。"""
    path = Path(name_or_path)
    if not path.is_absolute():
        path = STRUCT_DIR / path.name if path.suffix == ".nbt" else STRUCT_DIR / f"{path.name}.nbt"
    return File.load(str(path), gzipped=True)[""]


def save_structure(name_or_path, root, force=True):
    """写出结构 NBT（先写临时文件再原子替换，避免中途失败留半个文件）。root 为根 Compound。"""
    path = Path(name_or_path)
    if not path.is_absolute():
        path = STRUCT_DIR / path.name if path.suffix == ".nbt" else STRUCT_DIR / f"{path.name}.nbt"
    if path.exists() and not force:
        raise FileExistsError(f"{path} 已存在（--force 才覆盖）")
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".nbt.tmp")
    File({"": root}, gzipped=True).save(str(tmp))
    os.replace(tmp, path)
    return path


def palette_ids(root):
    return [str(entry["Name"]) for entry in root["palette"]]


def block_entries(root):
    """遍历 blocks，返回 (pos, block_id, props, nbt) 四元组。"""
    pal = root["palette"]
    out = []
    for block in root["blocks"]:
        state = int(block["state"])
        entry = pal[state]
        block_id = str(entry["Name"])
        props = {}
        if "Properties" in entry:
            props = {str(k): str(v) for k, v in entry["Properties"].items()}
        nbt = block.get("nbt")
        out.append((tuple(int(v) for v in block["pos"]), block_id, props, nbt))
    return out


def jigsaw_infos(root):
    """返回结构里所有 jigsaw 方块的信息 dict 列表。"""
    out = []
    for pos, block_id, props, nbt in block_entries(root):
        if block_id != "minecraft:jigsaw" or nbt is None:
            continue
        out.append({
            "pos": pos,
            "name": str(nbt.get("name", "")),
            "pool": str(nbt.get("pool", "")),
            "target": str(nbt.get("target", "")),
            "joint": str(nbt.get("joint", "")),
            "final_state": str(nbt.get("final_state", "")),
        })
    return out


# ------------------------------------------------------------ 方块 id 白名单

def _jar_paths(pattern):
    return sorted(glob.glob(str(ROOT / pattern), recursive=True))


def _hex_jar(wrapper):
    pattern = (
        f"C:/Users/{os.environ.get('USERNAME', 'Administrator')}/.gradle/caches/modules-2/files-2.1/"
        f"at.petra-k.hexcasting/hexcasting-{wrapper}-1.20.1/{HEX_VERSION}/*/"
        f"hexcasting-{wrapper}-1.20.1-{HEX_VERSION}.jar"
    )
    found = sorted(glob.glob(pattern))
    if not found:
        pattern = pattern.replace(f"/{HEX_VERSION}/*/", "/*/*/")
        found = sorted(glob.glob(pattern))
    return Path(found[-1]) if found else None


def _names_from_jar(path, prefix, suffix=".json"):
    with zipfile.ZipFile(path) as zf:
        return [n[len(prefix):-len(suffix)] for n in zf.namelist()
                if n.startswith(prefix) and n.endswith(suffix)]


def vanilla_block_ids():
    ids = set()
    for jar in _jar_paths(".gradle/loom-cache/minecraftMaven/**/minecraft-merged-*.jar"):
        for name in _names_from_jar(jar, "assets/minecraft/blockstates/"):
            ids.add("minecraft:" + name)
        if ids:
            break
    return ids


def hexcasting_block_ids():
    jar = _hex_jar("fabric") or _hex_jar("forge")
    if jar is None:
        return set()
    return {"hexcasting:" + name for name in _names_from_jar(str(jar), "assets/hexcasting/blockstates/")}


def vanilla_loot_ids():
    ids = set()
    for jar in _jar_paths(".gradle/loom-cache/minecraftMaven/**/minecraft-merged-*.jar"):
        for name in _names_from_jar(jar, "data/minecraft/loot_tables/"):
            ids.add("minecraft:" + name)
        if ids:
            break
    return ids


def hexcasting_loot_ids():
    jar = _hex_jar("common")
    if jar is None:
        return set()
    return {"hexcasting:" + name for name in _names_from_jar(str(jar), "data/hexcasting/loot_tables/")}


def vanilla_biome_tag_ids():
    """原版 worldgen/biome 标签，形如 '#minecraft:has_structure/village_plains'。"""
    ids = set()
    for jar in _jar_paths(".gradle/loom-cache/minecraftMaven/**/minecraft-merged-*.jar"):
        for name in _names_from_jar(jar, "data/minecraft/tags/worldgen/biome/"):
            ids.add("#minecraft:" + name)
        if ids:
            break
    return ids


def mod_biome_tag_ids():
    """本模组自己定义的 biome 标签。"""
    base = RESOURCES_DATA / "abadoned_greatwork" / "tags" / "worldgen" / "biome"
    if not base.is_dir():
        return set()
    return {"#abadoned_greatwork:" + path.stem for path in base.glob("*.json")}


def known_block_ids():
    return vanilla_block_ids() | hexcasting_block_ids()


def parse_block(text):
    """'minecraft:oak_fence[east=false]' -> ('minecraft:oak_fence', {'east': 'false'})"""
    text = text.strip()
    if "[" not in text:
        return text, {}
    block_id, rest = text.split("[", 1)
    props = {}
    for part in rest.rstrip("]").split(","):
        if "=" in part:
            key, value = part.split("=", 1)
            props[key.strip()] = value.strip()
    return block_id.strip(), props


def block_text(block_id, props=None):
    if not props:
        return block_id
    return block_id + "[" + ",".join(f"{k}={v}" for k, v in props.items()) + "]"


def make_palette_entry(text):
    block_id, props = parse_block(text)
    entry = Compound({"Name": String(block_id)})
    if props:
        entry["Properties"] = Compound({String(k): String(v) for k, v in props.items()})
    return entry


# --------------------------------------------------------------- JSON 工具

def worldgen_json(kind, name):
    return WORLDGEN / kind / f"{name}.json"


def read_json(path):
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(data, handle, indent=4, ensure_ascii=False)
        handle.write("\n")


def structure_json_names():
    return sorted(p.stem for p in (WORLDGEN / "structure").glob("*.json"))


def pool_names():
    return sorted(p.stem for p in (WORLDGEN / "template_pool").glob("*.json"))


def lang_keys(locale):
    with open(LANG_DIR / f"{locale}.json", "r", encoding="utf-8") as handle:
        return set(json.load(handle).keys())