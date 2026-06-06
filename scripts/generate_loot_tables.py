#!/usr/bin/env python3
"""
Generate loot table JSON files from chest.js KubeJS data.
Output to: common/src/main/resources/data/abadoned_greatwork/loot_tables/chests/
"""
import re, json, os

# ===== Parse chest.js =====
JS_PATH = r"D:\rd\casting\.minecraft\versions\1.20.1-Fabric 0.18.0\kubejs\server_scripts\src\chest.js"
OUT_DIR = r"c:\Users\Administrator\Desktop\BigPack\AbadonedGreatwork\common\src\main\resources\data\abadoned_greatwork\loot_tables\chests"

with open(JS_PATH, 'r', encoding='utf-8') as f:
    content = f.read()

# Extract CYPHER_TEMPLATES
start = content.find("const CYPHER_TEMPLATES = {")
end = content.find("};", start)
block = content[start:end+2]
pattern = r"'([^']+)':\s*'(\{.+?\})',?\s*\n"
cypher_templates = {}
for m in re.finditer(pattern, block, re.DOTALL):
    name = m.group(1)
    nbt_raw = m.group(2)
    # Convert JS escapes to real chars
    nbt = nbt_raw.replace("\\'", "'").replace('\\"', '"')
    cypher_templates[name] = nbt

print(f"Extracted {len(cypher_templates)} cypher templates: {list(cypher_templates.keys())}")

VARIANTS = [1, 2, 3, 4, 5, 6, 7]

# ===== Helper functions =====
def item_entry(item_id, weight=1, nbt=None):
    """Create a standard item entry with optional NBT."""
    entry = {"type": "minecraft:item", "name": item_id, "weight": weight}
    if nbt:
        entry["functions"] = [{"function": "minecraft:set_nbt", "tag": nbt}]
    return entry

def tag_entry(tag_id, weight=1):
    """Create a tag entry."""
    return {"type": "minecraft:tag", "name": tag_id, "expand": True, "weight": weight}

def pool(entries):
    """Create a pool with 1 roll from a list of entries."""
    return {"rolls": 1, "entries": entries}

def loot_table(pools_list):
    """Create a full loot table JSON."""
    return {"type": "minecraft:chest", "pools": pools_list}

def make_variant_entries(item_id, variants, weight=1, extra_nbt=""):
    """Generate variant item entries: focus, artifact, trinket with {variant:N}"""
    entries = []
    for v in variants:
        nbt = "{" + extra_nbt + f"variant:{v}" + "}"
        entries.append(item_entry(item_id, weight, nbt))
    return entries

def make_cypher_entries(template_name, weight=1):
    """Generate cypher entries for all 7 variants."""
    template = cypher_templates[template_name]
    entries = []
    for v in VARIANTS:
        nbt = template.replace("VARIANT", str(v))
        entries.append(item_entry("hexcasting:ancient_cypher", weight, nbt))
    return entries

def make_amethyst_armor_entries(weight=1):
    """Generate amethyst armor entries."""
    pieces = ["helmet", "chestplate", "leggings", "boots"]
    return [item_entry(f"hexchanting:amethyst_{p}", weight, "{Damage:0}") for p in pieces]

# ===== Generate files =====

os.makedirs(OUT_DIR, exist_ok=True)

# ---------- ruined_circles_shulk.json ----------
# Pools: amethyst_armor, focus_variants, artifact_variants, 驾雾, 云腾, 丝绸之触, 提取
ruined_circles_shulk = loot_table([
    pool(make_amethyst_armor_entries()),
    pool(make_variant_entries("hexcasting:focus", VARIANTS)),
    pool(make_variant_entries("hexcasting:artifact", VARIANTS)),
    pool(make_cypher_entries("远古杂件：驾雾")),
    pool(make_cypher_entries("远古杂件：云腾")),
    pool(make_cypher_entries("远古杂件：丝绸之触")),
    pool(make_cypher_entries("远古杂件：提取")),
])

with open(os.path.join(OUT_DIR, "ruined_circles_shulk.json"), 'w', encoding='utf-8') as f:
    json.dump(ruined_circles_shulk, f, indent=2, ensure_ascii=False)
print("Created ruined_circles_shulk.json")

# ---------- abadoned_akasha.json ----------
# Pool 1: evaluator, debugger(enchanted), scroll(brainsweep), scroll(allay_mix), quenched_allay_shard, slate, slate_block
abadoned_akasha_pools = []

# Pool 1
abadoned_akasha_pools.append(pool([
    item_entry("hexdebug:evaluator"),
    item_entry("hexdebug:debugger", nbt="{Enchantments:[{id:\"minecraft:bane_of_arthropods\",lvl:1s}]}"),
    item_entry("hexcasting:scroll", nbt='{op_id:"hexcasting:brainsweep"}'),
    item_entry("hexcasting:scroll", nbt='{op_id:"miehex:allay_mix"}'),
    item_entry("hexcasting:quenched_allay_shard"),
    item_entry("hexcasting:slate"),
    item_entry("hexcasting:slate_block"),
]))

# Pool 2: slate_block, air, air
abadoned_akasha_pools.append(pool([
    item_entry("hexcasting:slate_block"),
    item_entry("minecraft:air", 1),
    item_entry("minecraft:air", 1),
]))

# Pool 3: focus variants, trinket variants, air, air
pool3_entries = []
pool3_entries.extend(make_variant_entries("hexcasting:focus", VARIANTS))
pool3_entries.extend(make_variant_entries("hexcasting:trinket", VARIANTS))
pool3_entries.append(item_entry("minecraft:air", 1))
pool3_entries.append(item_entry("minecraft:air", 1))
abadoned_akasha_pools.append(pool(pool3_entries))

# Pool 4: SKIP (was addBooks)

# Pool 5: staves tag, air, air
abadoned_akasha_pools.append(pool([
    tag_entry("hexcasting:staves"),
    item_entry("minecraft:air", 1),
    item_entry("minecraft:air", 1),
]))

# Pool 6: hexpigmentplus tag, jeweler_hammer×3, media_jar
abadoned_akasha_pools.append(pool([
    tag_entry("hexpigmentplus:hexpigmentplus"),
    item_entry("hexcasting:jeweler_hammer", nbt="{Damage:0}"),
    item_entry("hexcasting:jeweler_hammer", nbt="{Damage:1000}"),
    item_entry("hexcasting:jeweler_hammer", nbt="{Damage:500}"),
    item_entry("hexical:media_jar", nbt="{BlockEntityTag:{media:6400000L}}"),
]))

# Pools 7-10: amethyst_dust, charged_amethyst (with air every other pool)
for i in range(4):
    entries = [
        item_entry("hexcasting:amethyst_dust"),
        item_entry("hexcasting:charged_amethyst"),
    ]
    if i % 2 == 0:
        entries.append(item_entry("minecraft:air", 1))
    abadoned_akasha_pools.append(pool(entries))

# Pool 11: amethyst_dust, charged_amethyst, plushies×5
abadoned_akasha_pools.append(pool([
    item_entry("hexcasting:amethyst_dust"),
    item_entry("hexcasting:charged_amethyst"),
    item_entry("hexical:plush_hexxy"),
    item_entry("hexical:plush_irissy"),
    item_entry("hexical:plush_pentxxy"),
    item_entry("hexical:plush_quadxxy"),
    item_entry("hexical:plush_thothy"),
]))

# Pool 12: quenched_allay_tiles, quenched_allay_bricks, air, battery×2, air, hex_machina tag
abadoned_akasha_pools.append(pool([
    item_entry("hexcasting:quenched_allay_tiles"),
    item_entry("hexcasting:quenched_allay_bricks"),
    item_entry("minecraft:air", 1),
    item_entry("hexcasting:battery", nbt='{"hexcasting:media":6400000L,"hexcasting:start_media":6400000L}'),
    item_entry("hexcasting:battery", nbt='{"hexcasting:media":6400000L,"hexcasting:start_media":64000000L}'),
    item_entry("minecraft:air", 1),
    tag_entry("hex_machina:hex_machina"),
]))

# Pool 13: focus variants, artifact variants, cyphers×4
pool13_entries = []
pool13_entries.extend(make_variant_entries("hexcasting:focus", VARIANTS))
pool13_entries.extend(make_variant_entries("hexcasting:artifact", VARIANTS))
pool13_entries.extend(make_cypher_entries("远古杂件：驾雾"))
pool13_entries.extend(make_cypher_entries("远古杂件：云腾"))
pool13_entries.extend(make_cypher_entries("远古杂件：丝绸之触"))
pool13_entries.extend(make_cypher_entries("远古杂件：提取"))
abadoned_akasha_pools.append(pool(pool13_entries))

abadoned_akasha = loot_table(abadoned_akasha_pools)
with open(os.path.join(OUT_DIR, "abadoned_akasha.json"), 'w', encoding='utf-8') as f:
    json.dump(abadoned_akasha, f, indent=2, ensure_ascii=False)
print("Created abadoned_akasha.json")

# ---------- abadoned_greatwork_room.json ----------
# Pool 1: SKIP (was addBooks)
# Pool 2: staves tag, air, air
# Pool 3: hexpigmentplus tag, jeweler_hammer×3, media_jar
# Pools 4-7: amethyst_dust, charged_amethyst (with air)
# Pool 8: amethyst_dust, charged_amethyst, plushies×5
# Pool 9: quenched_allay_tiles, quenched_allay_bricks, air, battery×2, air, hex_machina tag, scroll(allay_mix)
# Pool 10: focus variants, artifact variants, cyphers×4

abadoned_greatwork_room_pools = []

# Pool 1: staves tag, air, air
abadoned_greatwork_room_pools.append(pool([
    tag_entry("hexcasting:staves"),
    item_entry("minecraft:air", 1),
    item_entry("minecraft:air", 1),
]))

# Pool 2: hexpigmentplus tag, jeweler_hammer×3, media_jar
abadoned_greatwork_room_pools.append(pool([
    tag_entry("hexpigmentplus:hexpigmentplus"),
    item_entry("hexcasting:jeweler_hammer", nbt="{Damage:0}"),
    item_entry("hexcasting:jeweler_hammer", nbt="{Damage:1000}"),
    item_entry("hexcasting:jeweler_hammer", nbt="{Damage:500}"),
    item_entry("hexical:media_jar", nbt="{BlockEntityTag:{media:6400000L}}"),
]))

# Pools 3-6: amethyst_dust, charged_amethyst (with air every other pool)
for i in range(4):
    entries = [
        item_entry("hexcasting:amethyst_dust"),
        item_entry("hexcasting:charged_amethyst"),
    ]
    if i % 2 == 0:
        entries.append(item_entry("minecraft:air", 1))
    abadoned_greatwork_room_pools.append(pool(entries))

# Pool 7: amethyst_dust, charged_amethyst, plushies×5
abadoned_greatwork_room_pools.append(pool([
    item_entry("hexcasting:amethyst_dust"),
    item_entry("hexcasting:charged_amethyst"),
    item_entry("hexical:plush_hexxy"),
    item_entry("hexical:plush_irissy"),
    item_entry("hexical:plush_pentxxy"),
    item_entry("hexical:plush_quadxxy"),
    item_entry("hexical:plush_thothy"),
]))

# Pool 8: quenched_allay_tiles, quenched_allay_bricks, air, battery×2, air, hex_machina tag, scroll
abadoned_greatwork_room_pools.append(pool([
    item_entry("hexcasting:quenched_allay_tiles"),
    item_entry("hexcasting:quenched_allay_bricks"),
    item_entry("minecraft:air", 1),
    item_entry("hexcasting:battery", nbt='{"hexcasting:media":6400000L,"hexcasting:start_media":6400000L}'),
    item_entry("hexcasting:battery", nbt='{"hexcasting:media":6400000L,"hexcasting:start_media":64000000L}'),
    item_entry("minecraft:air", 1),
    tag_entry("hex_machina:hex_machina"),
    item_entry("hexcasting:scroll", nbt='{op_id:"miehex:allay_mix"}'),
]))

# Pool 9: focus variants, artifact variants, cyphers×4
pool9_entries = []
pool9_entries.extend(make_variant_entries("hexcasting:focus", VARIANTS))
pool9_entries.extend(make_variant_entries("hexcasting:artifact", VARIANTS))
pool9_entries.extend(make_cypher_entries("远古杂件：驾雾"))
pool9_entries.extend(make_cypher_entries("远古杂件：云腾"))
pool9_entries.extend(make_cypher_entries("远古杂件：丝绸之触"))
pool9_entries.extend(make_cypher_entries("远古杂件：提取"))
abadoned_greatwork_room_pools.append(pool(pool9_entries))

abadoned_greatwork_room = loot_table(abadoned_greatwork_room_pools)
with open(os.path.join(OUT_DIR, "abadoned_greatwork_room.json"), 'w', encoding='utf-8') as f:
    json.dump(abadoned_greatwork_room, f, indent=2, ensure_ascii=False)
print("Created abadoned_greatwork_room.json")

# ---------- classroom.json ----------
# Pool 1: focus variants, artifact variants, cyphers×4
# Pools 2-11 (10 pools): amethyst_dust, charged_amethyst, book (with air every other pool)
# Pool 12: hexboard items (quartz_typeblock, redstone_typeblock, lapis_typeblock, board_staff×2)

classroom_pools = []

# Pool 1: focus, artifact, cyphers
pool1_entries = []
pool1_entries.extend(make_variant_entries("hexcasting:focus", VARIANTS))
pool1_entries.extend(make_variant_entries("hexcasting:artifact", VARIANTS))
pool1_entries.extend(make_cypher_entries("远古杂件：驾雾"))
pool1_entries.extend(make_cypher_entries("远古杂件：云腾"))
pool1_entries.extend(make_cypher_entries("远古杂件：丝绸之触"))
pool1_entries.extend(make_cypher_entries("远古杂件：提取"))
classroom_pools.append(pool(pool1_entries))

# Pools 2-11: amethyst_dust, charged_amethyst, book (with air every other)
for i in range(10):
    entries = [
        item_entry("hexcasting:amethyst_dust"),
        item_entry("hexcasting:charged_amethyst"),
        item_entry("minecraft:book"),
    ]
    if i % 2 == 0:
        entries.append(item_entry("minecraft:air", 1))
    classroom_pools.append(pool(entries))

# Pool 12: hexboard items
classroom_pools.append(pool([
    item_entry("hexboard:quartz_typeblock"),
    item_entry("hexboard:redstone_typeblock"),
    item_entry("hexboard:lapis_typeblock"),
    item_entry("hexboard:board_staff", 2),
]))

classroom = loot_table(classroom_pools)
with open(os.path.join(OUT_DIR, "classroom.json"), 'w', encoding='utf-8') as f:
    json.dump(classroom, f, indent=2, ensure_ascii=False)
print("Created classroom.json")

print("\nAll loot table files generated successfully!")
