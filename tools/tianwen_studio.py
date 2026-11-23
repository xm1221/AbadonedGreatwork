#!/usr/bin/env python3
"""
Tianwen Studio Local Backend (问天 · 演示编辑器本地服务)

Zero-dependency local HTTP server for AbadonedGreatwork's Tianwen (Hex Ponder) GUI editor:
- Serves tools/tianwen_editor.html at http://127.0.0.1:<port>/
- Reads/writes live categories, scenes, and 1-to-1 paired zh_cn.json & en_us.json files
- Auto-generates & prunes canonical hexponder.* translation keys from natural-language inputs
- Serves real 16x16 item PNG sprites from workspace resources & cached Hex Casting / Minecraft jars
- Runs workspace validation (scripts/validate_truth_crystals.py + Tianwen integrity checks)
"""
from __future__ import annotations

import argparse
import glob
import io
import json
import os
import re
import struct
import subprocess
import sys
import urllib.parse
import webbrowser
import zipfile
import zlib
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

try:
    from PIL import Image  # Optional: only used to crop animated vertical sprite strips
except Exception:  # pragma: no cover
    Image = None  # type: ignore

ROOT = Path(__file__).resolve().parent.parent
RESOURCES = ROOT / "common/src/main/resources"
PONDER_ROOT = RESOURCES / "data/abadoned_greatwork/hex_ponder"
CATS_DIR = PONDER_ROOT / "categories"
SCENES_DIR = PONDER_ROOT / "scenes"
IOTAS_DIR = PONDER_ROOT / "iotas"
LANG_DIR = RESOURCES / "assets/abadoned_greatwork/lang"
LANG_ZH = LANG_DIR / "zh_cn.json"
LANG_EN = LANG_DIR / "en_us.json"
EDITOR_HTML = ROOT / "tools/tianwen_editor.html"
VALIDATE_SCRIPT = ROOT / "scripts/validate_truth_crystals.py"

SLUG_RE = re.compile(r"^[a-z0-9_]+$")

CURATED_MC_ITEMS: list[tuple[str, str]] = [
    ("minecraft:ender_eye", "末影之眼"),
    ("minecraft:comparator", "红石比较器"),
    ("minecraft:clock", "时钟"),
    ("minecraft:barrier", "屏障"),
    ("minecraft:glowstone_dust", "荧石粉"),
    ("minecraft:amethyst_shard", "紫水晶碎片"),
    ("minecraft:amethyst_cluster", "紫水晶簇"),
    ("minecraft:budding_amethyst", "紫水晶母岩"),
    ("minecraft:book", "书"),
    ("minecraft:writable_book", "书与笔"),
    ("minecraft:written_book", "成书"),
    ("minecraft:bookshelf", "书架"),
    ("minecraft:chiseled_bookshelf", "雕纹书架"),
    ("minecraft:compass", "指南针"),
    ("minecraft:recovery_compass", "追溯指针"),
    ("minecraft:spyglass", "望远镜"),
    ("minecraft:redstone", "红石粉"),
    ("minecraft:repeater", "红石中继器"),
    ("minecraft:observer", "侦测器"),
    ("minecraft:beacon", "信标"),
    ("minecraft:nether_star", "下界之星"),
    ("minecraft:prismarine_shard", "海晶碎片"),
    ("minecraft:prismarine_crystals", "海晶砂粒"),
    ("minecraft:echo_shard", "回响碎片"),
    ("minecraft:ender_pearl", "末影珍珠"),
    ("minecraft:blaze_rod", "烈焰棒"),
    ("minecraft:blaze_powder", "烈焰粉"),
    ("minecraft:stick", "木棍"),
    ("minecraft:feather", "羽毛"),
    ("minecraft:ink_sac", "墨囊"),
    ("minecraft:glow_ink_sac", "荧光墨囊"),
    ("minecraft:paper", "纸"),
    ("minecraft:filled_map", "地图"),
    ("minecraft:map", "空地图"),
    ("minecraft:diamond", "钻石"),
    ("minecraft:emerald", "绿宝石"),
    ("minecraft:gold_ingot", "金锭"),
    ("minecraft:lapis_lazuli", "青金石"),
    ("minecraft:quartz", "下界石英"),
    ("minecraft:candle", "蜡烛"),
    ("minecraft:lantern", "灯笼"),
    ("minecraft:soul_lantern", "灵魂灯笼"),
    ("minecraft:lectern", "讲台"),
    ("minecraft:enchanting_table", "附魔台"),
    ("minecraft:lodestone", "磁石"),
    ("minecraft:sculk_sensor", "幽匿感测体"),
    ("minecraft:calibrated_sculk_sensor", "校频幽匿感测体"),
    ("minecraft:respawn_anchor", "重生锚"),
    ("minecraft:end_crystal", "末地水晶"),
    ("minecraft:experience_bottle", "附魔之瓶"),
    ("minecraft:totem_of_undying", "不死图腾"),
    ("minecraft:torch", "火把"),
    ("minecraft:soul_torch", "灵魂火把"),
    ("minecraft:redstone_torch", "红石火把"),
    ("minecraft:name_tag", "命名牌"),
    ("minecraft:lead", "拴绳"),
]

SKIP_MC_PATTERNS = (
    re.compile(r"_spawn_egg$"),
    re.compile(r"^clock_\d+$"),
    re.compile(r"^compass_\d+$"),
    re.compile(r"^recovery_compass_\d+$"),
    re.compile(r"^bow_pulling_\d+$"),
    re.compile(r"^crossbow_"),
    re.compile(r"_blocking$"),
    re.compile(r"_in_hand$"),
    re.compile(r"^fishing_rod_cast$"),
    re.compile(r"^bundle_filled$"),
    re.compile(r"^filled_map_markings$"),
)


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def atomic_write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(tmp, path)


def safe_resolve_under(base: Path, rel_str: str) -> Path | None:
    if not rel_str:
        return None
    candidate = (base / rel_str).resolve()
    base_res = base.resolve()
    try:
        candidate.relative_to(base_res)
    except ValueError:
        return None
    return candidate


def _split_rl(rl: str, default_ns: str = "minecraft") -> tuple[str, str]:
    rl = rl.strip()
    if ":" in rl:
        ns, path = rl.split(":", 1)
        return ns or default_ns, path
    return default_ns, rl


def update_lang_files(
    *,
    remove_prefixes: list[str] | None = None,
    remove_exact: list[str] | None = None,
    updates_zh: dict[str, str] | None = None,
    updates_en: dict[str, str] | None = None,
) -> dict[str, int]:
    """
    Atomically update zh_cn.json and en_us.json while preserving unrelated keys,
    maintaining clean key insertion position, and enforcing 1-to-1 key parity.
    """
    prefixes = tuple(remove_prefixes or [])
    exact = set(remove_exact or [])
    up_zh = dict(updates_zh or {})
    up_en = dict(updates_en or {})
    if set(up_zh.keys()) != set(up_en.keys()):
        raise ValueError("Internal lang update mismatch between zh_cn and en_us key sets")

    zh_orig: dict[str, str] = read_json(LANG_ZH)
    en_orig: dict[str, str] = read_json(LANG_EN)

    def apply_one(orig: dict[str, str], up: dict[str, str]) -> dict[str, str]:
        managed = set(up.keys()) | exact
        items = list(orig.items())
        insert_idx = len(items)
        kept: list[tuple[str, str]] = []
        found_slot = False
        for k, v in items:
            is_removed = (k in managed) or (prefixes and k.startswith(prefixes))
            if is_removed:
                if not found_slot:
                    insert_idx = len(kept)
                    found_slot = True
            else:
                kept.append((k, v))
        new_items = list(up.items())
        merged = kept[:insert_idx] + new_items + kept[insert_idx:]
        return dict(merged)

    zh_new = apply_one(zh_orig, up_zh)
    en_new = apply_one(en_orig, up_en)

    if set(zh_new.keys()) != set(en_new.keys()):
        only_zh = sorted(set(zh_new.keys()) - set(en_new.keys()))[:5]
        only_en = sorted(set(en_new.keys()) - set(zh_new.keys()))[:5]
        raise ValueError(f"Lang parity check failed: only_zh={only_zh}, only_en={only_en}")

    atomic_write_json(LANG_ZH, zh_new)
    atomic_write_json(LANG_EN, en_new)
    return {"zh_count": len(zh_new), "en_count": len(en_new)}


class AssetResolver:
    """Resolves item icons and mod/vanilla lang dictionaries from workspace & cached jars."""

    def __init__(self) -> None:
        self.hc_jar: Path | None = None
        self.mc_jar: Path | None = None
        self._hc_names: set[str] = set()
        self._mc_names: set[str] = set()
        self._icon_cache: dict[str, bytes | None] = {}
        self._model_cache: dict[str, dict[str, Any] | None] = {}
        self._items_cache: list[dict[str, str]] | None = None
        self._hc_zh: dict[str, str] = {}
        self._hc_en: dict[str, str] = {}
        self._mc_en: dict[str, str] = {}
        self._locate_jars()

    def _locate_jars(self) -> None:
        hc_candidates = glob.glob(
            str(ROOT / ".gradle/loom-cache/remapped_mods/remapped/at/petra-k/hexcasting/**/*.jar"),
            recursive=True,
        )
        for cand in sorted(hc_candidates):
            if cand.endswith("-sources.jar"):
                continue
            try:
                with zipfile.ZipFile(cand) as z:
                    names = set(z.namelist())
                    if any(n.startswith("assets/hexcasting/textures/item/") for n in names):
                        self.hc_jar = Path(cand)
                        self._hc_names = names
                        if "assets/hexcasting/lang/zh_cn.json" in names:
                            self._hc_zh = json.loads(z.read("assets/hexcasting/lang/zh_cn.json").decode("utf-8"))
                        if "assets/hexcasting/lang/en_us.json" in names:
                            self._hc_en = json.loads(z.read("assets/hexcasting/lang/en_us.json").decode("utf-8"))
                        break
            except Exception:
                continue

        mc_candidates = glob.glob(
            str(ROOT / ".gradle/loom-cache/minecraftMaven/net/minecraft/**/*.jar"),
            recursive=True,
        ) + glob.glob(os.path.expanduser("~/.gradle/caches/fabric-loom/1.20.1/*.jar"))
        for cand in mc_candidates:
            if cand.endswith("-sources.jar"):
                continue
            try:
                with zipfile.ZipFile(cand) as z:
                    names = set(z.namelist())
                    if "assets/minecraft/models/item/barrier.json" in names:
                        self.mc_jar = Path(cand)
                        self._mc_names = names
                        if "assets/minecraft/lang/en_us.json" in names:
                            self._mc_en = json.loads(z.read("assets/minecraft/lang/en_us.json").decode("utf-8"))
                        break
            except Exception:
                continue

    def read_asset(self, rel_path: str) -> bytes | None:
        rel_path = rel_path.lstrip("/")
        local = safe_resolve_under(RESOURCES, rel_path)
        if local is not None and local.is_file():
            return local.read_bytes()
        if self.hc_jar and rel_path in self._hc_names:
            with zipfile.ZipFile(self.hc_jar) as z:
                return z.read(rel_path)
        if self.mc_jar and rel_path in self._mc_names:
            with zipfile.ZipFile(self.mc_jar) as z:
                return z.read(rel_path)
        return None

    def _read_model(self, rel_path: str) -> dict[str, Any] | None:
        if rel_path in self._model_cache:
            return self._model_cache[rel_path]
        raw = self.read_asset(rel_path)
        if not raw:
            self._model_cache[rel_path] = None
            return None
        try:
            obj = json.loads(raw.decode("utf-8"))
        except Exception:
            obj = None
        self._model_cache[rel_path] = obj if isinstance(obj, dict) else None
        return self._model_cache[rel_path]

    def _resolve_model_texture(
        self,
        model_rel: str,
        model_ns: str,
        inherited_tex: dict[str, str] | None = None,
        depth: int = 0,
    ) -> str | None:
        if depth >= 7:
            return None
        obj = self._read_model(model_rel)
        if not obj:
            return None

        merged: dict[str, str] = {}
        raw_tex = obj.get("textures")
        if isinstance(raw_tex, dict):
            for k, v in raw_tex.items():
                if isinstance(v, str):
                    merged[k] = v
        if inherited_tex:
            # Child definitions override parent mappings
            for k, v in inherited_tex.items():
                merged[k] = v

        def deref(val: str) -> str | None:
            cur = val
            seen: set[str] = set()
            while cur.startswith("#"):
                key = cur[1:]
                if key in seen or key not in merged:
                    return None
                seen.add(key)
                cur = merged[key]
            return cur

        pref_keys = ["layer0", "all", "front", "side", "top", "north", "south", "east", "west", "particle"]
        for k in pref_keys + list(merged.keys()):
            if k in merged:
                concrete = deref(merged[k])
                if concrete:
                    tns, tpath = _split_rl(concrete, model_ns)
                    return f"assets/{tns}/textures/{tpath}.png"

        # Walk parent (for block items or multi-level parents)
        parent = obj.get("parent")
        if isinstance(parent, str) and parent:
            pns, ppath = _split_rl(parent, "minecraft")
            if ppath not in ("item/generated", "item/handheld", "item/handheld_rod", "builtin/generated", "builtin/entity"):
                hit = self._resolve_model_texture(
                    f"assets/{pns}/models/{ppath}.json", pns, merged, depth + 1
                )
                if hit:
                    return hit

        # Handle predicate overrides (e.g. hexcasting:slate)
        overrides = obj.get("overrides")
        if isinstance(overrides, list) and overrides:
            first_mod = overrides[0].get("model") if isinstance(overrides[0], dict) else None
            if isinstance(first_mod, str) and first_mod:
                ons, opath = _split_rl(first_mod, model_ns)
                hit = self._resolve_model_texture(
                    f"assets/{ons}/models/{opath}.json", ons, None, depth + 1
                )
                if hit:
                    return hit
        return None

    def resolve_icon_png(self, item_id: str) -> bytes | None:
        key = item_id.strip()
        if not key or key.lower() == "none":
            return None
        if key in self._icon_cache:
            return self._icon_cache[key]

        ns, path = _split_rl(key, "minecraft")
        candidates: list[str] = []
        tex_rel = self._resolve_model_texture(f"assets/{ns}/models/item/{path}.json", ns)
        if tex_rel:
            candidates.append(tex_rel)
        candidates.extend([
            f"assets/{ns}/textures/item/{path}.png",
            f"assets/{ns}/textures/block/{path}.png",
        ])
        if path == "chest" or path == "ender_chest":
            candidates.append(f"assets/{ns}/textures/entity/chest/{'ender' if path == 'ender_chest' else 'normal'}.png")

        png_bytes: bytes | None = None
        for rel in candidates:
            raw = self.read_asset(rel)
            if raw and raw.startswith(b"\x89PNG"):
                png_bytes = self._crop_animated(raw)
                break

        self._icon_cache[key] = png_bytes
        return png_bytes

    @staticmethod
    def _crop_animated(raw: bytes) -> bytes:
        if Image is None:
            return raw
        try:
            with Image.open(io.BytesIO(raw)) as im:
                w, h = im.size
                if w > 0 and h > w and h % w == 0:
                    buf = io.BytesIO()
                    im.crop((0, 0, w, w)).save(buf, format="PNG")
                    return buf.getvalue()
        except Exception:
            pass
        return raw

    def get_action_dicts(self) -> tuple[dict[str, str], dict[str, str]]:
        az = {k: v for k, v in self._hc_zh.items() if k.startswith("hexcasting.action.")}
        ae = {k: v for k, v in self._hc_en.items() if k.startswith("hexcasting.action.")}
        local_zh = read_json(LANG_ZH) if LANG_ZH.is_file() else {}
        local_en = read_json(LANG_EN) if LANG_EN.is_file() else {}
        for k, v in local_zh.items():
            if k.startswith("hexcasting.action."):
                az[k] = v
        for k, v in local_en.items():
            if k.startswith("hexcasting.action."):
                ae[k] = v
        return az, ae

    def list_items(self) -> list[dict[str, str]]:
        if self._items_cache is not None:
            return self._items_cache

        items: list[dict[str, str]] = []
        seen: set[str] = set()
        local_zh = read_json(LANG_ZH) if LANG_ZH.is_file() else {}
        local_en = read_json(LANG_EN) if LANG_EN.is_file() else {}

        # 1. AbadonedGreatwork items
        abw_models_dir = RESOURCES / "assets/abadoned_greatwork/models/item"
        if abw_models_dir.is_dir():
            for mf in sorted(abw_models_dir.glob("*.json")):
                stem = mf.stem
                iid = f"abadoned_greatwork:{stem}"
                dot = stem.replace("/", ".")
                n_zh = local_zh.get(f"item.abadoned_greatwork.{dot}") or local_zh.get(f"block.abadoned_greatwork.{dot}") or stem
                n_en = local_en.get(f"item.abadoned_greatwork.{dot}") or local_en.get(f"block.abadoned_greatwork.{dot}") or stem
                seen.add(iid)
                items.append({"id": iid, "ns": "abadoned_greatwork", "name_zh": n_zh, "name_en": n_en, "group": "abw"})

        # 2. Hex Casting items
        hc_prefix = "assets/hexcasting/models/item/"
        hc_stems = sorted(
            n[len(hc_prefix):-5]
            for n in self._hc_names
            if n.startswith(hc_prefix) and n.endswith(".json")
        )
        for m in hc_stems:
            dot = m.replace("/", ".")
            k_item = f"item.hexcasting.{dot}"
            k_block = f"block.hexcasting.{dot}"
            k_raw = f"item.hexcasting.{m}"
            has_lang = (k_item in self._hc_en) or (k_block in self._hc_en) or (k_raw in self._hc_en)
            if not has_lang and not m.startswith("staff/"):
                continue
            iid = f"hexcasting:{m}"
            if iid in seen:
                continue
            n_zh = self._hc_zh.get(k_item) or self._hc_zh.get(k_block) or self._hc_zh.get(k_raw) or m
            n_en = self._hc_en.get(k_item) or self._hc_en.get(k_block) or self._hc_en.get(k_raw) or m
            seen.add(iid)
            items.append({"id": iid, "ns": "hexcasting", "name_zh": n_zh, "name_en": n_en, "group": "hex"})

        # 3. Curated Minecraft items first (with Chinese names)
        for iid, n_zh in CURATED_MC_ITEMS:
            if iid in seen:
                continue
            path = iid.split(":", 1)[1]
            n_en = self._mc_en.get(f"item.minecraft.{path}") or self._mc_en.get(f"block.minecraft.{path}") or path
            seen.add(iid)
            items.append({"id": iid, "ns": "minecraft", "name_zh": n_zh, "name_en": n_en, "group": "mc"})

        # 4. Remaining Minecraft items (for full icon coverage)
        mc_prefix = "assets/minecraft/models/item/"
        mc_stems = sorted(
            n[len(mc_prefix):-5]
            for n in self._mc_names
            if n.startswith(mc_prefix) and n.endswith(".json")
        )
        for m in mc_stems:
            if any(p.search(m) for p in SKIP_MC_PATTERNS):
                continue
            iid = f"minecraft:{m}"
            if iid in seen:
                continue
            n_en = self._mc_en.get(f"item.minecraft.{m}") or self._mc_en.get(f"block.minecraft.{m}") or m
            seen.add(iid)
            items.append({"id": iid, "ns": "minecraft", "name_zh": n_en, "name_en": n_en, "group": "mc"})

        self._items_cache = items
        return items


RESOLVER = AssetResolver()

# ==================== Iota SNBT / Binary NBT / Ascii85 Codec ====================
HEX_DIRS = ["NORTH_EAST", "EAST", "SOUTH_EAST", "SOUTH_WEST", "WEST", "NORTH_WEST"]
ANGLE_CHARS = ["w", "e", "d", "s", "a", "q"]
CHAR_TO_ANGLE = {c: i for i, c in enumerate(ANGLE_CHARS)}
_CATALOG_BY_SIG_CACHE: dict[str, dict[str, str]] | None = None


class NbtVal:
    def __init__(self, value: Any) -> None:
        self.value = value


class NbtByte(NbtVal): pass
class NbtShort(NbtVal): pass
class NbtLong(NbtVal): pass
class NbtFloat(NbtVal): pass
class NbtDouble(NbtVal): pass
class NbtByteArray(NbtVal): pass
class NbtIntArray(NbtVal): pass
class NbtLongArray(NbtVal): pass


def _get_catalog_by_sig() -> dict[str, dict[str, str]]:
    global _CATALOG_BY_SIG_CACHE
    if _CATALOG_BY_SIG_CACHE is not None:
        return _CATALOG_BY_SIG_CACHE
    out: dict[str, dict[str, str]] = {}
    if EDITOR_HTML.is_file():
        m = re.search(r"const HEX_CATALOG = (\[.*?\]);", EDITOR_HTML.read_text("utf-8"))
        if m:
            try:
                for item in json.loads(m.group(1)):
                    sig = item.get("sig", "")
                    if sig and sig not in out:
                        out[sig] = item
            except Exception:
                pass
    _CATALOG_BY_SIG_CACHE = out
    return out


def _get_catalog_by_id() -> dict[str, dict[str, str]]:
    by_sig = _get_catalog_by_sig()
    out = {v["id"]: v for v in by_sig.values() if "id" in v}
    if EDITOR_HTML.is_file():
        m = re.search(r"const HEX_CATALOG = (\[.*?\]);", EDITOR_HTML.read_text("utf-8"))
        if m:
            try:
                for item in json.loads(m.group(1)):
                    out[item["id"]] = item
            except Exception:
                pass
    return out


def parse_snbt(text: str) -> Any:
    s = text.strip()
    n = len(s)
    pos = [0]

    def skip_ws() -> None:
        while pos[0] < n and s[pos[0]].isspace():
            pos[0] += 1

    def parse_str() -> str:
        quote = s[pos[0]]
        pos[0] += 1
        out: list[str] = []
        while pos[0] < n:
            ch = s[pos[0]]
            pos[0] += 1
            if ch == "\\":
                if pos[0] < n:
                    esc = s[pos[0]]
                    pos[0] += 1
                    out.append({"n": "\n", "t": "\t", "r": "\r"}.get(esc, esc))
            elif ch == quote:
                return "".join(out)
            else:
                out.append(ch)
        raise ValueError("Unterminated string in SNBT")

    def parse_unquoted() -> str:
        start = pos[0]
        while pos[0] < n and (s[pos[0]].isalnum() or s[pos[0]] in "_.+-"):
            pos[0] += 1
        if pos[0] == start:
            raise ValueError(f"Unexpected token at {pos[0]}: {s[pos[0]:pos[0]+12]}")
        return s[start:pos[0]]

    def parse_primitive(tok: str) -> Any:
        low = tok.lower()
        if low == "true":
            return NbtByte(1)
        if low == "false":
            return NbtByte(0)
        if re.fullmatch(r"[+-]?\d+[bB]", tok):
            return NbtByte(int(tok[:-1]))
        if re.fullmatch(r"[+-]?\d+[sS]", tok):
            return NbtShort(int(tok[:-1]))
        if re.fullmatch(r"[+-]?\d+[lL]", tok):
            return NbtLong(int(tok[:-1]))
        if re.fullmatch(r"[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?[fF]", tok):
            return NbtFloat(float(tok[:-1]))
        if re.fullmatch(r"[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?[dD]", tok):
            return NbtDouble(float(tok[:-1]))
        if re.fullmatch(r"[+-]?\d+", tok):
            return int(tok)
        if re.fullmatch(r"[+-]?(?:\d+\.\d*|\.\d+)(?:[eE][+-]?\d+)?", tok):
            return NbtDouble(float(tok))
        return tok

    def parse_val() -> Any:
        skip_ws()
        if pos[0] >= n:
            raise ValueError("Unexpected end of SNBT")
        ch = s[pos[0]]
        if ch == "{":
            pos[0] += 1
            obj: dict[str, Any] = {}
            skip_ws()
            if pos[0] < n and s[pos[0]] == "}":
                pos[0] += 1
                return obj
            while True:
                skip_ws()
                k = parse_str() if s[pos[0]] in ('"', "'") else parse_unquoted()
                skip_ws()
                if pos[0] >= n or s[pos[0]] != ":":
                    raise ValueError(f"Expected ':' after key {k}")
                pos[0] += 1
                obj[k] = parse_val()
                skip_ws()
                if pos[0] < n and s[pos[0]] == ",":
                    pos[0] += 1
                    skip_ws()
                    if pos[0] < n and s[pos[0]] == "}":
                        pos[0] += 1
                        return obj
                    continue
                if pos[0] < n and s[pos[0]] == "}":
                    pos[0] += 1
                    return obj
                raise ValueError("Expected ',' or '}' in compound")
        if ch == "[":
            pos[0] += 1
            skip_ws()
            arr_kind: str | None = None
            if pos[0] + 1 < n and s[pos[0]] in ("B", "I", "L") and s[pos[0] + 1] == ";":
                arr_kind = s[pos[0]]
                pos[0] += 2
                skip_ws()
            items: list[Any] = []
            if pos[0] < n and s[pos[0]] == "]":
                pos[0] += 1
            else:
                while True:
                    items.append(parse_val())
                    skip_ws()
                    if pos[0] < n and s[pos[0]] == ",":
                        pos[0] += 1
                        skip_ws()
                        if pos[0] < n and s[pos[0]] == "]":
                            pos[0] += 1
                            break
                        continue
                    if pos[0] < n and s[pos[0]] == "]":
                        pos[0] += 1
                        break
                    raise ValueError("Expected ',' or ']' in list")
            if arr_kind == "B":
                return NbtByteArray([x.value if isinstance(x, NbtVal) else int(x) for x in items])
            if arr_kind == "I":
                return NbtIntArray([x.value if isinstance(x, NbtVal) else int(x) for x in items])
            if arr_kind == "L":
                return NbtLongArray([x.value if isinstance(x, NbtVal) else int(x) for x in items])
            return items
        if ch in ('"', "'"):
            return parse_str()
        return parse_primitive(parse_unquoted())

    return parse_val()


def format_snbt(val: Any) -> str:
    if isinstance(val, NbtByte):
        return f"{val.value}b"
    if isinstance(val, NbtShort):
        return f"{val.value}s"
    if isinstance(val, NbtLong):
        return f"{val.value}L"
    if isinstance(val, NbtFloat):
        return f"{val.value}f"
    if isinstance(val, NbtDouble):
        v = float(val.value)
        s = repr(v)
        if "." not in s and "e" not in s and "E" not in s:
            s += ".0"
        return f"{s}d"
    if isinstance(val, NbtByteArray):
        return "[B;" + ",".join(f"{b}b" for b in val.value) + "]"
    if isinstance(val, NbtIntArray):
        return "[I;" + ",".join(str(x) for x in val.value) + "]"
    if isinstance(val, NbtLongArray):
        return "[L;" + ",".join(f"{x}L" for x in val.value) + "]"
    if isinstance(val, bool):
        return "1b" if val else "0b"
    if isinstance(val, int):
        return str(val)
    if isinstance(val, float):
        return f"{val}d"
    if isinstance(val, str):
        escaped = val.replace("\\", "\\\\").replace('"', '\\"')
        return f'"{escaped}"'
    if isinstance(val, list):
        return "[" + ",".join(format_snbt(x) for x in val) + "]"
    if isinstance(val, dict):
        parts = []
        for k, v in val.items():
            ks = k if re.fullmatch(r"[A-Za-z0-9_.+-]+", k) else ('"' + k.replace('"', '\\"') + '"')
            parts.append(f"{ks}:{format_snbt(v)}")
        return "{" + ",".join(parts) + "}"
    return '""'


def _decode_a85_bytes(s: str) -> bytes:
    out = bytearray()
    g = [0] * 5
    gi = 0
    for ch in s:
        if ch == "z":
            out.extend(b"\x00\x00\x00\x00")
            gi = 0
            continue
        code = ord(ch) - 33
        if code < 0 or code >= 85:
            raise ValueError(f"非法 Ascii85 字符: {ch!r}")
        g[gi] = code
        gi += 1
        if gi == 5:
            v = (((g[0] * 85 + g[1]) * 85 + g[2]) * 85 + g[3]) * 85 + g[4]
            out.extend(struct.pack(">I", v & 0xFFFFFFFF))
            gi = 0
    if gi > 0:
        for j in range(gi, 5):
            g[j] = 84
        v = (((g[0] * 85 + g[1]) * 85 + g[2]) * 85 + g[3]) * 85 + g[4]
        for j in range(gi - 1):
            out.append((v >> (24 - 8 * j)) & 0xFF)
    return bytes(out)


def _read_nbt_root(raw_nbt: bytes) -> dict[str, Any]:
    buf = io.BytesIO(raw_nbt)

    def r_bytes(k: int) -> bytes:
        b = buf.read(k)
        if len(b) != k:
            raise ValueError("NBT 数据截断")
        return b

    def r_str() -> str:
        ln = struct.unpack(">H", r_bytes(2))[0]
        return r_bytes(ln).decode("utf-8", errors="replace")

    def r_payload(tag_id: int) -> Any:
        if tag_id == 1:
            return NbtByte(struct.unpack(">b", r_bytes(1))[0])
        if tag_id == 2:
            return NbtShort(struct.unpack(">h", r_bytes(2))[0])
        if tag_id == 3:
            return struct.unpack(">i", r_bytes(4))[0]
        if tag_id == 4:
            return NbtLong(struct.unpack(">q", r_bytes(8))[0])
        if tag_id == 5:
            return NbtFloat(struct.unpack(">f", r_bytes(4))[0])
        if tag_id == 6:
            return NbtDouble(struct.unpack(">d", r_bytes(8))[0])
        if tag_id == 7:
            ln = struct.unpack(">i", r_bytes(4))[0]
            return NbtByteArray([struct.unpack("b", bytes([b]))[0] for b in r_bytes(ln)])
        if tag_id == 8:
            return r_str()
        if tag_id == 9:
            et = r_bytes(1)[0]
            ln = struct.unpack(">i", r_bytes(4))[0]
            return [r_payload(et) for _ in range(ln)]
        if tag_id == 10:
            comp: dict[str, Any] = {}
            while True:
                tid = r_bytes(1)[0]
                if tid == 0:
                    break
                name = r_str()
                comp[name] = r_payload(tid)
            return comp
        if tag_id == 11:
            ln = struct.unpack(">i", r_bytes(4))[0]
            return NbtIntArray(list(struct.unpack(f">{ln}i", r_bytes(4 * ln))))
        if tag_id == 12:
            ln = struct.unpack(">i", r_bytes(4))[0]
            return NbtLongArray(list(struct.unpack(f">{ln}q", r_bytes(8 * ln))))
        raise ValueError(f"未知 NBT Tag: {tag_id}")

    root_id = r_bytes(1)[0]
    if root_id != 10:
        raise ValueError("NBT 根节点必须是 CompoundTag")
    _ = r_str()
    return r_payload(10)


def _num_val(x: Any, default: float = 0.0) -> float:
    if isinstance(x, NbtVal):
        return float(x.value)
    if isinstance(x, (int, float)):
        return float(x)
    return default


def nbt_to_ast(tag: Any) -> dict[str, Any]:
    if not isinstance(tag, dict):
        return {"kind": "raw", "snbt": format_snbt(tag), "summary": format_snbt(tag)}
    htype = str(tag.get("hexcasting:type") or "")
    data = tag.get("hexcasting:data")

    if htype == "hexcasting:null":
        return {"kind": "null"}
    if htype == "hexcasting:double":
        return {"kind": "double", "value": _num_val(data, 0.0)}
    if htype == "hexcasting:boolean":
        b = bool(int(_num_val(data, 0)))
        return {"kind": "boolean", "value": b}
    if htype == "hexcasting:vec3" and isinstance(data, dict):
        return {
            "kind": "vec3",
            "x": _num_val(data.get("x"), 0.0),
            "y": _num_val(data.get("y"), 0.0),
            "z": _num_val(data.get("z"), 0.0),
        }
    if htype == "hexcasting:pattern" and isinstance(data, dict):
        sd_idx = int(_num_val(data.get("start_dir"), 0)) % 6
        start_dir = HEX_DIRS[sd_idx]
        raw_angles = data.get("angles")
        angles_bytes = raw_angles.value if isinstance(raw_angles, NbtByteArray) else (raw_angles or [])
        sig = "".join(ANGLE_CHARS[int(b) % 6] for b in angles_bytes)
        cat = _get_catalog_by_sig().get(sig)
        return {
            "kind": "pattern",
            "action": cat["id"] if cat else "",
            "dir": start_dir,
            "angles": sig,
            "zh": cat["zh"] if cat else "",
        }
    if htype == "hexcasting:list" and isinstance(data, list):
        return {"kind": "list", "items": [nbt_to_ast(el) for el in data]}

    return {"kind": "raw", "type": htype, "snbt": format_snbt(tag)}


def ast_to_nbt(ast: dict[str, Any]) -> dict[str, Any]:
    kind = str(ast.get("kind") or "null")
    if kind == "null":
        return {"hexcasting:type": "hexcasting:null", "hexcasting:data": {}}
    if kind == "double":
        return {"hexcasting:type": "hexcasting:double", "hexcasting:data": NbtDouble(float(ast.get("value", 0.0)))}
    if kind == "boolean":
        return {"hexcasting:type": "hexcasting:boolean", "hexcasting:data": NbtByte(1 if ast.get("value") else 0)}
    if kind == "vec3":
        return {
            "hexcasting:type": "hexcasting:vec3",
            "hexcasting:data": {
                "x": NbtDouble(float(ast.get("x", 0.0))),
                "y": NbtDouble(float(ast.get("y", 0.0))),
                "z": NbtDouble(float(ast.get("z", 0.0))),
            },
        }
    if kind == "pattern":
        act_id = str(ast.get("action") or "").strip()
        dir_name = str(ast.get("dir") or "NORTH_EAST").upper()
        angles = str(ast.get("angles") or "").strip()
        if act_id:
            cat = _get_catalog_by_id().get(act_id)
            if cat:
                dir_name = cat.get("dir", dir_name)
                angles = cat.get("sig", angles)
        sd_idx = HEX_DIRS.index(dir_name) if dir_name in HEX_DIRS else 0
        arr = [CHAR_TO_ANGLE[c] for c in angles if c in CHAR_TO_ANGLE]
        return {
            "hexcasting:type": "hexcasting:pattern",
            "hexcasting:data": {
                "angles": NbtByteArray(arr),
                "start_dir": NbtByte(sd_idx),
            },
        }
    if kind == "list":
        return {
            "hexcasting:type": "hexcasting:list",
            "hexcasting:data": [ast_to_nbt(it) for it in (ast.get("items") or [])],
        }
    if kind == "raw":
        parsed = parse_snbt(str(ast.get("snbt") or "{}"))
        if isinstance(parsed, dict):
            return parsed
    raise ValueError(f"无法序列化 Iota 类型: {kind}")


def ast_to_inline(ast: dict[str, Any]) -> str:
    kind = str(ast.get("kind") or "null")
    if kind == "null":
        return "null"
    if kind == "double":
        v = float(ast.get("value", 0.0))
        return f"double:{int(v) if v.is_integer() else v}"
    if kind == "vec3":
        return f"vec:{{{ast.get('x', 0)},{ast.get('y', 0)},{ast.get('z', 0)}}}"
    if kind == "pattern":
        act_id = str(ast.get("action") or "").strip()
        if act_id:
            return f"pattern:{act_id}"
        return f"pattern{{{str(ast.get('dir') or 'NORTH_EAST').upper()},{ast.get('angles') or ''}}}"
    if kind == "list":
        items = ast.get("items") or []
        inner = ",".join(ast_to_inline(it) for it in items)
        return f"iota:[{inner}]"
    if kind == "boolean":
        return format_snbt(ast_to_nbt(ast))
    if kind == "raw":
        return str(ast.get("snbt") or "{}")
    return "null"


def summarize_ast(ast: dict[str, Any]) -> str:
    kind = str(ast.get("kind") or "null")
    if kind == "null":
        return "Null"
    if kind == "double":
        v = float(ast.get("value", 0.0))
        return f"数值 {int(v) if v.is_integer() else v}"
    if kind == "boolean":
        return "布尔 " + ("True" if ast.get("value") else "False")
    if kind == "vec3":
        return f"向量 ({ast.get('x', 0)}, {ast.get('y', 0)}, {ast.get('z', 0)})"
    if kind == "pattern":
        if ast.get("zh"):
            return f"图案 {ast['zh']}"
        if ast.get("action"):
            return f"图案 {ast['action']}"
        return f"图案 [{ast.get('angles', '')}]"
    if kind == "list":
        items = ast.get("items") or []
        head = ", ".join(summarize_ast(it) for it in items[:3])
        more = ", ..." if len(items) > 3 else ""
        return f"列表 ({len(items)}项): {head}{more}"
    return f"NBT ({ast.get('type') or 'custom'})"


def _split_top_level(s: str) -> list[str]:
    out: list[str] = []
    depth = 0
    cur: list[str] = []
    for ch in s:
        if ch in "{[(":
            depth += 1
        elif ch in "}])":
            depth -= 1
        if ch == "," and depth == 0:
            part = "".join(cur).strip()
            if part:
                out.append(part)
            cur.clear()
        else:
            cur.append(ch)
    tail = "".join(cur).strip()
    if tail:
        out.append(tail)
    return out


def parse_iota_input_to_nbt(raw: str) -> dict[str, Any]:
    s = raw.strip()
    if not s:
        return ast_to_nbt({"kind": "null"})
    if s.startswith("{"):
        obj = json.loads(s) if s.startswith('{"nbt"') else None
        if isinstance(obj, dict) and "nbt" in obj:
            parsed = parse_snbt(str(obj["nbt"]))
        else:
            parsed = parse_snbt(s)
        if not isinstance(parsed, dict):
            raise ValueError("SNBT 根节点必须是对象")
        return parsed
    if s == "null":
        return ast_to_nbt({"kind": "null"})
    if s.startswith("double:"):
        return ast_to_nbt({"kind": "double", "value": float(s[7:].strip())})
    if s.startswith("vec:{") and s.endswith("}"):
        parts = [float(x.strip()) for x in s[5:-1].split(",")]
        if len(parts) != 3:
            raise ValueError("向量需要 3 个分量")
        return ast_to_nbt({"kind": "vec3", "x": parts[0], "y": parts[1], "z": parts[2]})
    if s.startswith("pattern:"):
        return ast_to_nbt({"kind": "pattern", "action": s[8:].strip()})
    if (s.startswith("pattern{") and s.endswith("}")) or (s.startswith("pattern[") and s.endswith("]")):
        parts = [x.strip() for x in s[8:-1].split(",")]
        return ast_to_nbt({"kind": "pattern", "dir": parts[0], "angles": parts[1] if len(parts) > 1 else ""})
    if s.startswith("iota:[") and s.endswith("]"):
        items = [parse_iota_input_to_nbt(p) for p in _split_top_level(s[6:-1])]
        return {"hexcasting:type": "hexcasting:list", "hexcasting:data": items}
    if s.startswith("iota:"):
        body = s[5:]
        if body.endswith("**n"):
            body = body[:-3]
        if body.endswith("*b") or body.endswith("*w"):
            body = body[:-2]
        elif len(body) >= 7 and body[-7] == "*" and re.fullmatch(r"[0-9a-fA-F]{6}", body[-6:]):
            body = body[:-7]
        if body.endswith(".json"):
            fname = body.split(":")[-1]
            p = safe_resolve_under(IOTAS_DIR, fname)
            if p is None or not p.is_file():
                raise ValueError(f"找不到预设文件: {fname}")
            return parse_iota_input_to_nbt(p.read_text("utf-8"))
        comp = _decode_a85_bytes(body)
        return _read_nbt_root(zlib.decompress(comp))
    raise ValueError("未识别的 Iota 格式，支持 null / double: / vec: / pattern: / iota:[...] / iota:<a85> / SNBT")


def inspect_iota_file(rel: str, path: Path) -> dict[str, Any]:
    try:
        nbt = parse_iota_input_to_nbt(path.read_text("utf-8"))
        ast = nbt_to_ast(nbt)
        return {
            "name": rel,
            "type": str(nbt.get("hexcasting:type") or "unknown"),
            "summary": summarize_ast(ast),
            "ast": ast,
            "snbt": format_snbt(nbt),
        }
    except Exception as e:
        return {"name": rel, "type": "error", "summary": f"解析失败: {e}", "ast": {"kind": "raw", "snbt": "{}"}, "snbt": ""}


def decode_iota_endpoint(body: dict[str, Any]) -> dict[str, Any]:
    raw = str(body.get("raw") or "").strip()
    nbt = parse_iota_input_to_nbt(raw)
    ast = nbt_to_ast(nbt)
    return {
        "ok": True,
        "type": str(nbt.get("hexcasting:type") or "unknown"),
        "summary": summarize_ast(ast),
        "ast": ast,
        "inline": ast_to_inline(ast),
        "snbt": format_snbt(nbt),
    }


def save_iota_preset(body: dict[str, Any]) -> dict[str, Any]:
    name = str(body.get("name") or "").strip()
    if name.endswith(".json"):
        name = name[:-5]
    if not SLUG_RE.match(name):
        raise ValueError("预设名称只能包含小写字母、数字和下划线 [a-z0-9_]")
    if "ast" in body and isinstance(body["ast"], dict):
        nbt = ast_to_nbt(body["ast"])
    elif "raw" in body:
        nbt = parse_iota_input_to_nbt(str(body["raw"]))
    else:
        raise ValueError("缺少 ast 或 raw 数据")

    snbt = format_snbt(nbt)
    dest = IOTAS_DIR / f"{name}.json"
    atomic_write_json(dest, {"nbt": snbt})
    ast = nbt_to_ast(nbt)
    return {
        "ok": True,
        "name": f"{name}.json",
        "ref": f"iota:{name}.json",
        "summary": summarize_ast(ast),
        "snbt": snbt,
    }


def delete_iota_preset(body: dict[str, Any]) -> dict[str, Any]:
    name = str(body.get("name") or "").strip()
    if not name.endswith(".json"):
        name += ".json"
    target = safe_resolve_under(IOTAS_DIR, name)
    if target is None or not target.is_file():
        raise ValueError(f"预设文件 '{name}' 不存在")
    target.unlink()
    return {"ok": True, "deleted": name}


def load_workspace_payload() -> dict[str, Any]:
    zh_cn: dict[str, str] = read_json(LANG_ZH)
    en_us: dict[str, str] = read_json(LANG_EN)

    categories: list[dict[str, Any]] = []
    if CATS_DIR.is_dir():
        for cf in sorted(CATS_DIR.glob("*.json")):
            obj = read_json(cf)
            cid = str(obj.get("id") or cf.stem)
            tkey = str(obj.get("title") or f"hexponder.category.{cid}")
            dkey = str(obj.get("description") or f"hexponder.category.{cid}.desc")
            categories.append({
                "id": cid,
                "file": cf.name,
                "title": tkey,
                "description": dkey,
                "icon": str(obj.get("icon") or "hexcasting:staff/oak"),
                "sort_order": int(obj.get("sort_order", 100)),
                "title_zh": zh_cn.get(tkey, tkey),
                "title_en": en_us.get(tkey, ""),
                "desc_zh": zh_cn.get(dkey, ""),
                "desc_en": en_us.get(dkey, ""),
            })
    categories.sort(key=lambda c: (c["sort_order"], c["id"]))

    scenes: list[dict[str, Any]] = []
    if SCENES_DIR.is_dir():
        for sf in sorted(SCENES_DIR.rglob("*.json")):
            rel = sf.relative_to(SCENES_DIR).as_posix()
            obj = read_json(sf)
            sid = str(obj.get("id") or f"abadoned_greatwork:{sf.stem}")
            slug = sid.split(":", 1)[-1]
            tkey = str(obj.get("title") or "")
            scenes.append({
                "slug": slug,
                "id": sid,
                "file": rel,
                "category": str(obj.get("category") or "basics"),
                "sort_order": int(obj.get("sort_order", 100)),
                "icon": str(obj.get("icon") or "hexcasting:staff/oak"),
                "title_zh": zh_cn.get(tkey, tkey),
                "title_en": en_us.get(tkey, ""),
                "data": obj,
            })
    scenes.sort(key=lambda s: (s["category"], s["sort_order"], s["slug"]))

    iotas: list[str] = []
    iota_details: list[dict[str, Any]] = []
    if IOTAS_DIR.is_dir():
        for p in sorted(IOTAS_DIR.rglob("*.json")):
            rel = p.relative_to(IOTAS_DIR).as_posix()
            iotas.append(rel)
            iota_details.append(inspect_iota_file(rel, p))

    action_zh, action_en = RESOLVER.get_action_dicts()
    return {
        "mode": "studio",
        "workspace_root": str(ROOT),
        "categories": categories,
        "scenes": scenes,
        "zh_cn": zh_cn,
        "en_us": en_us,
        "action_zh": action_zh,
        "action_en": action_en,
        "iotas": iotas,
        "iota_details": iota_details,
        "items": RESOLVER.list_items(),
    }


def save_category(body: dict[str, Any]) -> dict[str, Any]:
    cid = str(body.get("id") or "").strip()
    old_id = str(body.get("old_id") or "").strip()
    if not SLUG_RE.match(cid):
        raise ValueError("分类 ID 只能包含小写字母、数字和下划线 [a-z0-9_]")

    title_zh = str(body.get("title_zh") or "").strip()
    if not title_zh:
        raise ValueError("分类中文名称不能为空")
    title_en = str(body.get("title_en") or "").strip() or title_zh
    desc_zh = str(body.get("desc_zh") or "").strip()
    desc_en = str(body.get("desc_en") or "").strip() or desc_zh
    icon = str(body.get("icon") or "hexcasting:staff/oak").strip() or "hexcasting:staff/oak"
    sort_order = int(body.get("sort_order", 0))

    if old_id and old_id != cid:
        if not SLUG_RE.match(old_id):
            raise ValueError("无效的旧分类 ID")
        if (CATS_DIR / f"{cid}.json").exists():
            raise ValueError(f"目标分类 '{cid}' 已存在")

    tkey = f"hexponder.category.{cid}"
    dkey = f"hexponder.category.{cid}.desc"
    cat_obj = {
        "id": cid,
        "title": tkey,
        "description": dkey,
        "icon": icon,
        "sort_order": sort_order,
    }
    atomic_write_json(CATS_DIR / f"{cid}.json", cat_obj)

    moved_scenes = 0
    remove_exact: list[str] = []
    if old_id and old_id != cid:
        remove_exact = [f"hexponder.category.{old_id}", f"hexponder.category.{old_id}.desc"]
        old_cat_file = CATS_DIR / f"{old_id}.json"
        if old_cat_file.is_file():
            old_cat_file.unlink()
        # Move scenes belonging to old_id under SCENES_DIR/<cid>/
        for sf in list(SCENES_DIR.rglob("*.json")):
            sdata = read_json(sf)
            if sdata.get("category") == old_id or sf.parent.name == old_id:
                sdata["category"] = cid
                dest = SCENES_DIR / cid / sf.name
                atomic_write_json(dest, sdata)
                if dest.resolve() != sf.resolve():
                    sf.unlink()
                moved_scenes += 1

    lang_res = update_lang_files(
        remove_exact=remove_exact,
        updates_zh={tkey: title_zh, dkey: desc_zh},
        updates_en={tkey: title_en, dkey: desc_en},
    )
    return {"ok": True, "id": cid, "moved_scenes": moved_scenes, **lang_res}


def delete_category(body: dict[str, Any]) -> dict[str, Any]:
    cid = str(body.get("id") or "").strip()
    move_to = str(body.get("move_scenes_to") or "").strip()
    if not SLUG_RE.match(cid):
        raise ValueError("无效的分类 ID")
    cat_file = CATS_DIR / f"{cid}.json"
    if not cat_file.is_file():
        raise ValueError(f"分类 '{cid}' 不存在")

    affected: list[Path] = []
    for sf in sorted(SCENES_DIR.rglob("*.json")):
        sdata = read_json(sf)
        if sdata.get("category") == cid:
            affected.append(sf)

    if affected:
        if not move_to or move_to == cid or not (CATS_DIR / f"{move_to}.json").is_file():
            raise ValueError(f"分类 '{cid}' 下还有 {len(affected)} 个章节，请先迁移或指定目标分类")
        for sf in affected:
            sdata = read_json(sf)
            sdata["category"] = move_to
            dest = SCENES_DIR / move_to / sf.name
            atomic_write_json(dest, sdata)
            if dest.resolve() != sf.resolve():
                sf.unlink()

    cat_file.unlink()
    lang_res = update_lang_files(
        remove_exact=[f"hexponder.category.{cid}", f"hexponder.category.{cid}.desc"]
    )
    return {"ok": True, "deleted": cid, "moved_scenes": len(affected), **lang_res}


def save_scene(body: dict[str, Any]) -> dict[str, Any]:
    slug = str(body.get("slug") or "").strip()
    old_slug = str(body.get("old_slug") or "").strip()
    old_file = str(body.get("old_file") or "").strip()
    if not SLUG_RE.match(slug):
        raise ValueError("章节 slug 只能包含小写字母、数字和下划线 [a-z0-9_]")

    raw_scene = body.get("scene")
    if not isinstance(raw_scene, dict):
        raise ValueError("缺少 scene 对象")
    raw_texts = body.get("texts") or {}
    if not isinstance(raw_texts, dict):
        raise ValueError("缺少 texts 对象")

    scene = json.loads(json.dumps(raw_scene))  # deep copy
    category = str(scene.get("category") or "basics").strip()
    if not SLUG_RE.match(category) or not (CATS_DIR / f"{category}.json").is_file():
        raise ValueError(f"所属分类 '{category}' 不存在")

    scene["id"] = f"abadoned_greatwork:{slug}"
    prefix = f"hexponder.scene.{slug}."
    new_zh: dict[str, str] = {}
    new_en: dict[str, str] = {}

    def put_pair(key: str, zh_val: str, en_val: str) -> str:
        zh_c = zh_val.strip()
        en_c = en_val.strip() or zh_c
        new_zh[key] = zh_c
        new_en[key] = en_c
        return key

    # Title & subtitle
    t_obj = raw_texts.get("title") or {}
    t_zh = str(t_obj.get("zh") or "").strip() or slug
    t_en = str(t_obj.get("en") or "").strip() or t_zh
    scene["title"] = put_pair(f"{prefix}title", t_zh, t_en)

    sub_obj = raw_texts.get("subtitle") or {}
    sub_zh = str(sub_obj.get("zh") or "").strip()
    sub_en = str(sub_obj.get("en") or "").strip()
    if sub_zh or sub_en:
        base_sub = sub_zh or sub_en
        scene["subtitle"] = put_pair(f"{prefix}subtitle", base_sub, sub_en or base_sub)
    else:
        scene.pop("subtitle", None)

    # Unlock rule & optional natural-language lock hint
    raw_unlock = scene.get("unlock")
    lh_zh = str(raw_texts.get("lock_hint_zh") or "").strip()
    lh_en = str(raw_texts.get("lock_hint_en") or "").strip()
    if isinstance(raw_unlock, dict) and raw_unlock.get("locked"):
        u_out: dict[str, Any] = {
            "locked": True,
            "mode": "any" if str(raw_unlock.get("mode") or "all").lower() == "any" else "all",
        }
        if lh_zh or lh_en:
            base_lh = lh_zh or lh_en
            u_out["hint"] = put_pair(f"{prefix}lock_hint", base_lh, lh_en or base_lh)
        conds_in = raw_unlock.get("conditions") or []
        clean_conds: list[dict[str, str]] = []
        for c in conds_in:
            if not isinstance(c, dict):
                continue
            ctype = str(c.get("type") or "manual").strip().lower()
            cid = str(c.get("id") or "").strip()
            if ctype == "manual" or cid:
                clean_conds.append({"type": ctype, "id": cid})
        u_out["conditions"] = clean_conds
        scene["unlock"] = u_out
    else:
        scene.pop("unlock", None)

    # Canonical key order at top level while preserving optional keys
    cards_in = scene.get("knowledge_cards") or []
    card_texts = raw_texts.get("cards") or []
    clean_cards: list[dict[str, Any]] = []
    for idx, card in enumerate(cards_in):
        if not isinstance(card, dict):
            continue
        ct = card_texts[idx] if idx < len(card_texts) and isinstance(card_texts[idx], dict) else {}
        kt_zh = str(ct.get("title_zh") or "").strip() or f"札记 {idx + 1}"
        kt_en = str(ct.get("title_en") or "").strip() or kt_zh
        kb_zh = str(ct.get("body_zh") or "").strip() or kt_zh
        kb_en = str(ct.get("body_en") or "").strip() or kb_zh
        c_out = dict(card)
        c_out["title"] = put_pair(f"{prefix}k{idx}.title", kt_zh, kt_en)
        c_out["body"] = put_pair(f"{prefix}k{idx}.body", kb_zh, kb_en)
        if not c_out.get("inline_iotas"):
            c_out.pop("inline_iotas", None)
        clean_cards.append(c_out)
    scene["knowledge_cards"] = clean_cards

    # Steps
    steps_in = scene.get("steps") or []
    step_texts = raw_texts.get("steps") or []
    chapter_map: dict[tuple[str, str], str] = {}
    clean_steps: list[dict[str, Any]] = []
    for idx, st in enumerate(steps_in):
        if not isinstance(st, dict):
            continue
        st_out = dict(st)
        tx = step_texts[idx] if idx < len(step_texts) and isinstance(step_texts[idx], dict) else {}

        ch_zh = str(tx.get("chapter_zh") or "").strip()
        ch_en = str(tx.get("chapter_en") or "").strip()
        if ch_zh or ch_en:
            cz = ch_zh or ch_en
            ce = ch_en or cz
            pair = (cz, ce)
            if pair not in chapter_map:
                ck = f"{prefix}ch{len(chapter_map)}"
                chapter_map[pair] = ck
                put_pair(ck, cz, ce)
            st_out["chapter"] = chapter_map[pair]
        else:
            st_out.pop("chapter", None)

        title_mode = str(tx.get("title_mode") or "auto")
        title_key = str(tx.get("title_key") or "").strip()
        title_zh = str(tx.get("title_zh") or "").strip()
        title_en = str(tx.get("title_en") or "").strip()
        if title_mode == "key" and title_key and not title_key.startswith("hexponder."):
            st_out["title"] = title_key
        elif title_mode == "custom" and (title_zh or title_en):
            sz = title_zh or title_en
            st_out["title"] = put_pair(f"{prefix}s{idx}.title", sz, title_en or sz)
        else:
            st_out.pop("title", None)

        co = st_out.get("callout")
        if isinstance(co, dict):
            tip_zh = str(tx.get("callout_zh") or "").strip()
            tip_en = str(tx.get("callout_en") or "").strip()
            if tip_zh or tip_en:
                co_out = dict(co)
                tz = tip_zh or tip_en
                co_out["text"] = put_pair(f"{prefix}s{idx}.tip", tz, tip_en or tz)
                st_out["callout"] = co_out
            else:
                st_out.pop("callout", None)
        clean_steps.append(st_out)
    scene["steps"] = clean_steps

    dest_rel = f"{category}/{slug}.json"
    dest_path = safe_resolve_under(SCENES_DIR, dest_rel)
    if dest_path is None:
        raise ValueError("非法的场景文件路径")

    old_path = safe_resolve_under(SCENES_DIR, old_file) if old_file else None
    if dest_path.exists() and (old_path is None or old_path != dest_path):
        raise ValueError(f"目标路径 '{dest_rel}' 已存在其他章节，请更换 slug 或先打开它编辑")

    remove_prefixes = [prefix]
    if old_slug and old_slug != slug and SLUG_RE.match(old_slug):
        remove_prefixes.append(f"hexponder.scene.{old_slug}.")

    atomic_write_json(dest_path, scene)
    if old_path is not None and old_path != dest_path and old_path.is_file():
        old_path.unlink()
        if old_path.parent != SCENES_DIR.resolve() and old_path.parent.is_dir() and not any(old_path.parent.iterdir()):
            old_path.parent.rmdir()

    lang_res = update_lang_files(
        remove_prefixes=remove_prefixes,
        updates_zh=new_zh,
        updates_en=new_en,
    )
    return {
        "ok": True,
        "slug": slug,
        "file": dest_rel,
        "scene": scene,
        "generated_keys": sorted(new_zh.keys()),
        **lang_res,
    }


def delete_scene(body: dict[str, Any]) -> dict[str, Any]:
    rel_file = str(body.get("file") or "").strip()
    slug = str(body.get("slug") or "").strip()
    if not SLUG_RE.match(slug):
        raise ValueError("无效的章节 slug")
    target = safe_resolve_under(SCENES_DIR, rel_file)
    if target is None or not target.is_file():
        raise ValueError(f"章节文件 '{rel_file}' 不存在")
    target.unlink()
    if target.parent != SCENES_DIR.resolve() and target.parent.is_dir() and not any(target.parent.iterdir()):
        target.parent.rmdir()
    lang_res = update_lang_files(remove_prefixes=[f"hexponder.scene.{slug}."])
    return {"ok": True, "deleted": rel_file, **lang_res}


def reorder_items(body: dict[str, Any]) -> dict[str, Any]:
    kind = str(body.get("kind") or "").strip()
    order = body.get("order")
    if not isinstance(order, list) or not order:
        raise ValueError("缺少 order 列表")

    updated = 0
    if kind == "categories":
        for idx, cid in enumerate(order):
            cid_str = str(cid).strip()
            if not SLUG_RE.match(cid_str):
                continue
            cf = CATS_DIR / f"{cid_str}.json"
            if cf.is_file():
                obj = read_json(cf)
                obj["sort_order"] = idx
                atomic_write_json(cf, obj)
                updated += 1
        return {"ok": True, "kind": "categories", "updated": updated}

    if kind == "scenes":
        cat = str(body.get("category") or "").strip()
        if not SLUG_RE.match(cat):
            raise ValueError("无效的分类 ID")
        slug_to_file: dict[str, Path] = {}
        for sf in sorted(SCENES_DIR.rglob("*.json")):
            obj = read_json(sf)
            if str(obj.get("category") or "") == cat:
                sid = str(obj.get("id") or sf.stem)
                slug = sid.split(":", 1)[-1]
                slug_to_file[slug] = sf
        for idx, slug in enumerate(order):
            sf = slug_to_file.get(str(slug).strip())
            if sf and sf.is_file():
                obj = read_json(sf)
                obj["sort_order"] = idx
                atomic_write_json(sf, obj)
                updated += 1
        return {"ok": True, "kind": "scenes", "category": cat, "updated": updated}

    raise ValueError(f"未知的排序类型: {kind}")


def run_validation() -> dict[str, Any]:
    errors: list[str] = []
    warnings: list[str] = []

    zh_cn: dict[str, str] = read_json(LANG_ZH)
    en_us: dict[str, str] = read_json(LANG_EN)

    only_zh = sorted(set(zh_cn.keys()) - set(en_us.keys()))
    only_en = sorted(set(en_us.keys()) - set(zh_cn.keys()))
    if only_zh:
        errors.append(f"zh_cn.json 多出 {len(only_zh)} 条键: {only_zh[:6]}")
    if only_en:
        errors.append(f"en_us.json 多出 {len(only_en)} 条键: {only_en[:6]}")

    referenced_hp_keys: set[str] = set()
    cat_ids: set[str] = set()
    if CATS_DIR.is_dir():
        for cf in sorted(CATS_DIR.glob("*.json")):
            try:
                obj = read_json(cf)
                cid = str(obj.get("id") or "")
                cat_ids.add(cid)
                for field in ("title", "description"):
                    val = str(obj.get(field) or "")
                    if val.startswith("hexponder."):
                        referenced_hp_keys.add(val)
                        if val not in zh_cn or val not in en_us:
                            errors.append(f"分类 {cf.name} 缺少翻译键: {val}")
            except Exception as e:
                errors.append(f"分类 {cf.name} JSON 解析失败: {e}")

    scene_count = 0
    if SCENES_DIR.is_dir():
        for sf in sorted(SCENES_DIR.rglob("*.json")):
            scene_count += 1
            rel = sf.relative_to(SCENES_DIR).as_posix()
            try:
                sc = read_json(sf)
            except Exception as e:
                errors.append(f"场景 {rel} JSON 解析失败: {e}")
                continue
            cat = str(sc.get("category") or "")
            if cat not in cat_ids:
                errors.append(f"场景 {rel} 引用不存在的分类: '{cat}'")
            for f in ("title", "subtitle"):
                val = str(sc.get(f) or "")
                if val.startswith("hexponder."):
                    referenced_hp_keys.add(val)
                    if val not in zh_cn or val not in en_us:
                        errors.append(f"场景 {rel} 缺少翻译键: {val}")
            unlock_obj = sc.get("unlock")
            if isinstance(unlock_obj, dict):
                hint_val = str(unlock_obj.get("hint") or "")
                if hint_val.startswith("hexponder."):
                    referenced_hp_keys.add(hint_val)
                    if hint_val not in zh_cn or hint_val not in en_us:
                        errors.append(f"场景 {rel} 锁定提示缺少翻译键: {hint_val}")
            for card in sc.get("knowledge_cards") or []:
                for f in ("title", "body"):
                    val = str(card.get(f) or "")
                    if val.startswith("hexponder."):
                        referenced_hp_keys.add(val)
                        if val not in zh_cn or val not in en_us:
                            errors.append(f"场景 {rel} 札记缺少翻译键: {val}")
                for iota_ref in card.get("inline_iotas") or []:
                    if isinstance(iota_ref, str) and iota_ref.startswith("iota:"):
                        fname = iota_ref[5:]
                        if not fname.endswith(".json"):
                            fname += ".json"
                        if not (IOTAS_DIR / fname).is_file():
                            errors.append(f"场景 {rel} 引用不存在的预设 iota: {iota_ref}")
            for st in sc.get("steps") or []:
                for f in ("chapter", "title"):
                    val = str(st.get(f) or "")
                    if val.startswith("hexponder."):
                        referenced_hp_keys.add(val)
                        if val not in zh_cn or val not in en_us:
                            errors.append(f"场景 {rel} 步骤缺少翻译键: {val}")
                co = st.get("callout")
                if isinstance(co, dict):
                    tip = str(co.get("text") or "")
                    if tip.startswith("hexponder."):
                        referenced_hp_keys.add(tip)
                        if tip not in zh_cn or tip not in en_us:
                            errors.append(f"场景 {rel} 浮窗提示缺少翻译键: {tip}")
                    for iota_ref in co.get("inline_iotas") or []:
                        if isinstance(iota_ref, str) and iota_ref.startswith("iota:"):
                            fname = iota_ref[5:]
                            if not fname.endswith(".json"):
                                fname += ".json"
                            if not (IOTAS_DIR / fname).is_file():
                                errors.append(f"场景 {rel} 引用不存在的预设 iota: {iota_ref}")
                push_val = str(st.get("push") or "")
                if push_val.startswith("iota:"):
                    fname = push_val[5:]
                    if not fname.endswith(".json"):
                        fname += ".json"
                    if not (IOTAS_DIR / fname).is_file():
                        errors.append(f"场景 {rel} 压栈引用不存在的预设 iota: {push_val}")

    orphan_scene_keys = sorted(
        k for k in zh_cn.keys() if k.startswith("hexponder.scene.") and k not in referenced_hp_keys
    )
    if orphan_scene_keys:
        warnings.append(f"发现 {len(orphan_scene_keys)} 条未被引用的场景翻译键: {orphan_scene_keys[:6]}")

    script_exit = -1
    script_output = ""
    if VALIDATE_SCRIPT.is_file():
        proc = subprocess.run(
            [sys.executable, str(VALIDATE_SCRIPT)],
            cwd=str(ROOT),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        script_exit = proc.returncode
        script_output = (proc.stdout + "\n" + proc.stderr).strip()
        if script_exit != 0:
            errors.append(f"validate_truth_crystals.py 退出码 {script_exit}")

    return {
        "ok": len(errors) == 0,
        "errors": errors,
        "warnings": warnings,
        "script_exit_code": script_exit,
        "script_output": script_output,
        "summary": f"分类 {len(cat_ids)} · 章节 {scene_count} · 语言键 {len(zh_cn)}/{len(en_us)}",
    }


class StudioHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, format: str, *args: Any) -> None:  # quiet console
        pass

    def _send_bytes(self, code: int, ctype: str, data: bytes) -> None:
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def _send_json(self, code: int, obj: Any) -> None:
        raw = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self._send_bytes(code, "application/json; charset=utf-8", raw)

    def _read_json_body(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > 8 * 1024 * 1024:
            raise ValueError("请求体为空或超出长度限制")
        raw = self.rfile.read(length)
        obj = json.loads(raw.decode("utf-8"))
        if not isinstance(obj, dict):
            raise ValueError("JSON 请求体必须是对象")
        return obj

    def do_GET(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path in ("/", "/index.html", "/tianwen_editor.html"):
            if not EDITOR_HTML.is_file():
                self._send_bytes(HTTPStatus.NOT_FOUND, "text/plain; charset=utf-8", b"tianwen_editor.html not found")
                return
            self._send_bytes(HTTPStatus.OK, "text/html; charset=utf-8", EDITOR_HTML.read_bytes())
            return

        if parsed.path == "/api/workspace":
            try:
                self._send_json(HTTPStatus.OK, load_workspace_payload())
            except Exception as e:
                self._send_json(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": str(e)})
            return

        if parsed.path == "/api/icon":
            qs = urllib.parse.parse_qs(parsed.query)
            iid = (qs.get("id") or [""])[0]
            png = RESOLVER.resolve_icon_png(iid)
            if png:
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", "image/png")
                self.send_header("Content-Length", str(len(png)))
                self.send_header("Cache-Control", "max-age=3600")
                self.end_headers()
                self.wfile.write(png)
            else:
                self._send_bytes(HTTPStatus.NOT_FOUND, "text/plain; charset=utf-8", b"icon not found")
            return

        self._send_bytes(HTTPStatus.NOT_FOUND, "text/plain; charset=utf-8", b"Not Found")

    def do_POST(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        try:
            body = self._read_json_body() if parsed.path != "/api/validate" else {}
            if parsed.path == "/api/category/save":
                self._send_json(HTTPStatus.OK, save_category(body))
            elif parsed.path == "/api/category/delete":
                self._send_json(HTTPStatus.OK, delete_category(body))
            elif parsed.path == "/api/scene/save":
                self._send_json(HTTPStatus.OK, save_scene(body))
            elif parsed.path == "/api/scene/delete":
                self._send_json(HTTPStatus.OK, delete_scene(body))
            elif parsed.path == "/api/reorder":
                self._send_json(HTTPStatus.OK, reorder_items(body))
            elif parsed.path == "/api/iota/decode":
                self._send_json(HTTPStatus.OK, decode_iota_endpoint(body))
            elif parsed.path == "/api/iota/save":
                self._send_json(HTTPStatus.OK, save_iota_preset(body))
            elif parsed.path == "/api/iota/delete":
                self._send_json(HTTPStatus.OK, delete_iota_preset(body))
            elif parsed.path == "/api/validate":
                self._send_json(HTTPStatus.OK, run_validation())
            else:
                self._send_json(HTTPStatus.NOT_FOUND, {"error": "Unknown API endpoint"})
        except ValueError as e:
            self._send_json(HTTPStatus.BAD_REQUEST, {"error": str(e)})
        except Exception as e:
            self._send_json(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": str(e)})


def main() -> None:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    parser = argparse.ArgumentParser(description="问天 · 演示编辑器本地后端服务 (Tianwen Studio)")
    parser.add_argument("--port", type=int, default=12218, help="监听端口 (默认 12218)")
    parser.add_argument("--no-open", action="store_true", help="启动后不自动打开浏览器")
    args = parser.parse_args()

    try:
        httpd = ThreadingHTTPServer(("127.0.0.1", args.port), StudioHandler)
    except OSError as e:
        print(
            f"[Tianwen Studio] 启动失败（端口 {args.port} 可能已被占用，"
            f"请检查是否已有实例在 http://127.0.0.1:{args.port}/ 运行）: {e}",
            file=sys.stderr,
        )
        sys.exit(1)

    actual_port = httpd.server_address[1]
    url = f"http://127.0.0.1:{actual_port}/"
    print(f"[Tianwen Studio] 服务已启动: {url}")
    print(f"[Tianwen Studio] 工程目录: {ROOT}")
    print(f"[Tianwen Studio] Hex Casting Jar: {RESOLVER.hc_jar or '未找到'}")
    print(f"[Tianwen Studio] Minecraft Jar:   {RESOLVER.mc_jar or '未找到'}")
    print("[Tianwen Studio] 按 Ctrl+C 停止服务")
    sys.stdout.flush()

    if not args.no_open:
        webbrowser.open(url)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n[Tianwen Studio] 已退出。")
        httpd.server_close()


if __name__ == "__main__":
    main()
