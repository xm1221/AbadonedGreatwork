#!/usr/bin/env python3
"""把结构 NBT 里 jigsaw 方块的 name/pool/target 和结构 JSON 的 start_jigsaw_name
从旧命名空间 miehex: 统一改成 abadoned_greatwork:。

默认 dry-run，加 --apply 才落盘。改动后必须跑 scripts/validate_structures.py。
"""
from __future__ import annotations

import sys
from pathlib import Path

from nbtlib import String

sys.path.insert(0, str(Path(__file__).resolve().parent))

import struct_common as sc

OLD = "miehex:"
NEW = "abadoned_greatwork:"


def rewrite_nbt(apply):
    changed = []
    for path in sorted(sc.STRUCT_DIR.glob("*.nbt")):
        root = sc.load_structure(path)
        hits = []
        for pos, block_id, props, nbt in sc.block_entries(root):
            if block_id != "minecraft:jigsaw" or nbt is None:
                continue
            for key in ("name", "pool", "target"):
                if key not in nbt:
                    continue
                value = str(nbt[key])
                if value.startswith(OLD):
                    replacement = value.replace(OLD, NEW, 1)
                    nbt[key] = String(replacement)
                    hits.append(f"{key}: {value} -> {replacement}")
        if not hits:
            continue
        changed.append((path.name, hits))
        if apply:
            sc.save_structure(path, root)
    return changed


def rewrite_json(apply):
    changed = []
    for path in sorted((sc.WORLDGEN / "structure").glob("*.json")):
        data = sc.read_json(path)
        value = data.get("start_jigsaw_name")
        if isinstance(value, str) and value.startswith(OLD):
            data["start_jigsaw_name"] = value.replace(OLD, NEW, 1)
            changed.append((path.name, f"start_jigsaw_name: {value} -> {data['start_jigsaw_name']}"))
            if apply:
                sc.write_json(path, data)
    return changed


def main(apply):
    nbt_changes = rewrite_nbt(apply)
    json_changes = rewrite_json(apply)
    for name, hits in nbt_changes:
        print(f"{name}: {len(hits)} 处")
        for hit in hits:
            print(f"    {hit}")
    for name, hit in json_changes:
        print(f"{name}: {hit}")
    print(f"\n共 {len(nbt_changes)} 个 NBT、{len(json_changes)} 个 JSON 需要修改"
          + ("" if apply else "（dry-run，未落盘）"))
    return 0


if __name__ == "__main__":
    sys.exit(main("--apply" in sys.argv))