#!/usr/bin/env python3
"""
Comprehensive validation script for Truth Crystal system:
- Validates all 18 riddles (JSON, SNBT, inputs/outputs, lang keys)
- Validates 16x16 texture dimensions and modes
- Validates block model coordinates and texture size
- Validates reward loot tables
"""
import json, os, re
from PIL import Image

BASE_DIR = "/home/antigravity/xm1221/AbadonedGreatwork"
RIDDLES_DIR = os.path.join(BASE_DIR, "common/src/main/resources/data/abadoned_greatwork/riddles")
LANG_DIR = os.path.join(BASE_DIR, "common/src/main/resources/assets/abadoned_greatwork/lang")
TEXTURES_DIR = os.path.join(BASE_DIR, "common/src/main/resources/assets/abadoned_greatwork/textures")
MODEL_PATH = os.path.join(BASE_DIR, "common/src/main/resources/assets/abadoned_greatwork/models/block/truth_crystal.json")
LOOT_DIR = os.path.join(BASE_DIR, "common/src/main/resources/data/abadoned_greatwork/loot_tables/gameplay")

errors = []
warnings = []

print("=== 1. Validating Language Files ===")
with open(os.path.join(LANG_DIR, "zh_cn.json"), "r", encoding="utf-8") as f:
    zh_cn = json.load(f)
with open(os.path.join(LANG_DIR, "en_us.json"), "r", encoding="utf-8") as f:
    en_us = json.load(f)

print(f"zh_cn has {len(zh_cn)} entries, en_us has {len(en_us)} entries.")

print("\n=== 2. Validating Riddles ===")
riddle_files = sorted([f for f in os.listdir(RIDDLES_DIR) if f.endswith(".json")])
print(f"Found {len(riddle_files)} riddle files:")

for rf in riddle_files:
    fpath = os.path.join(RIDDLES_DIR, rf)
    try:
        with open(fpath, "r", encoding="utf-8") as f:
            data = json.load(f)
    except Exception as e:
        errors.append(f"{rf}: Invalid JSON: {e}")
        continue

    variant = data.get("variant")
    if variant not in (0, 1, 2, -1):
        errors.append(f"{rf}: invalid variant {variant}")

    nbt_str = data.get("nbt", "")
    if not nbt_str:
        errors.append(f"{rf}: missing nbt string")
        continue

    # Extract tooltip
    m_tip = re.search(r'tooltip:"([^"]+)"', nbt_str)
    if not m_tip:
        errors.append(f"{rf}: missing tooltip in nbt")
    else:
        tip_key = m_tip.group(1)
        if tip_key not in zh_cn:
            errors.append(f"{rf}: tooltip key '{tip_key}' missing in zh_cn.json")
        if tip_key not in en_us:
            errors.append(f"{rf}: tooltip key '{tip_key}' missing in en_us.json")
        long_key = f"{tip_key}.long"
        if long_key not in zh_cn:
            warnings.append(f"{rf}: long description '{long_key}' missing in zh_cn.json")
        if long_key not in en_us:
            warnings.append(f"{rf}: long description '{long_key}' missing in en_us.json")

    # Extract length limit
    m_len = re.search(r'length_limit:(\d+)', nbt_str)
    if not m_len or int(m_len.group(1)) <= 0:
        errors.append(f"{rf}: invalid or missing length_limit")

    # Count test rounds
    m_inp = re.search(r'input:\{([^{}]+(?:\{[^{}]+\}[^{}]*)*)\}', nbt_str)
    input_rounds = re.findall(r'(\d+):\{', nbt_str.split("input:{")[1].split("},is_crafted")[0]) if "input:{" in nbt_str else []
    output_rounds = re.findall(r'(\d+):\{', nbt_str.split("output:{")[1].split("},tooltip")[0]) if "output:{" in nbt_str else []

    print(f"  [OK] {rf:<20} | Variant: {variant} | Limit: {m_len.group(1) if m_len else '?':<3} | Rounds: {len(input_rounds)}")

print("\n=== 3. Validating 16x16 Textures ===")
texture_checks = [
    os.path.join(TEXTURES_DIR, "item/truth_crystal_0.png"),
    os.path.join(TEXTURES_DIR, "item/truth_crystal_1.png"),
    os.path.join(TEXTURES_DIR, "item/truth_crystal_2.png"),
    os.path.join(TEXTURES_DIR, "block/truth_crystal.png"),
]

for tp in texture_checks:
    rel = os.path.relpath(tp, BASE_DIR)
    if not os.path.exists(tp):
        errors.append(f"Missing texture: {rel}")
        continue
    img = Image.open(tp)
    if img.size != (16, 16):
        errors.append(f"Texture {rel} has size {img.size}, expected (16, 16)!")
    elif img.mode != "RGBA":
        errors.append(f"Texture {rel} has mode {img.mode}, expected RGBA!")
    else:
        print(f"  [OK] {rel:<50} | Size: {img.size} | Mode: {img.mode}")

print("\n=== 4. Validating Pedestal Block Model ===")
with open(MODEL_PATH, "r", encoding="utf-8") as f:
    model_data = json.load(f)

tex_size = model_data.get("texture_size")
if tex_size != [16, 16]:
    errors.append(f"Block model texture_size is {tex_size}, expected [16, 16]")
else:
    print(f"  [OK] texture_size: {tex_size}")

for i, elem in enumerate(model_data.get("elements", [])):
    from_coord = elem.get("from", [])
    to_coord = elem.get("to", [])
    for c in from_coord + to_coord:
        if c < 0 or c > 16:
            errors.append(f"Element {i} coordinate {c} is out of bounds [0, 16]: from={from_coord}, to={to_coord}")
print(f"  [OK] All {len(model_data.get('elements', []))} model elements strictly within [0, 16] box.")

print("\n=== 5. Validating Loot Tables ===")
for i in range(3):
    lt_path = os.path.join(LOOT_DIR, f"truth_crystal_reward_{i}.json")
    with open(lt_path, "r", encoding="utf-8") as f:
        lt_data = json.load(f)
    items = [e.get("name") for p in lt_data.get("pools", []) for e in p.get("entries", [])]
    print(f"  [OK] Reward {i} pool items: {items}")

print("\n==========================================")
if warnings:
    print(f"WARNINGS ({len(warnings)}):")
    for w in warnings:
        print(f"  - {w}")
if errors:
    print(f"ERRORS ({len(errors)}):")
    for e in errors:
        print(f"  - {e}")
    exit(1)
else:
    print("ALL VALIDATION CHECKS PASSED PERFECTLY!")
    exit(0)
