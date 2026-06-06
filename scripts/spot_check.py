import json

with open(r'common\src\main\resources\data\abadoned_greatwork\loot_tables\chests\ruined_circles_shulk.json', 'r', encoding='utf-8') as f:
    data = json.load(f)

# Check cypher entry from pool 3 (index 3, first cypher pool)
cypher_entry = data['pools'][3]['entries'][0]
print('=== Cypher entry (pool 3, entry 0) ===')
print('name:', cypher_entry['name'])
print('weight:', cypher_entry['weight'])
nbt = cypher_entry['functions'][0]['tag']
print('NBT length:', len(nbt))
print('NBT starts:', nbt[:120])
print('NBT ends:', nbt[-80:])

# Check for villain:VARIANT not being replaced
if 'VARIANT' in nbt:
    print('ERROR: VARIANT not replaced!')
else:
    print('OK: VARIANT replaced')

# Check tag entries across all pools
print('\n=== Tag entries ===')
for i, pool_i in enumerate(data['pools']):
    for entry in pool_i['entries']:
        if entry['type'] == 'minecraft:tag':
            print(f'  Pool {i}: tag={entry["name"]}')

# Quick check abadoned_akasha for tags
print('\n=== abadoned_akasha tags ===')
with open(r'common\src\main\resources\data\abadoned_greatwork\loot_tables\chests\abadoned_akasha.json', 'r', encoding='utf-8') as f:
    data2 = json.load(f)
for i, pool_i in enumerate(data2['pools']):
    for entry in pool_i['entries']:
        if entry['type'] == 'minecraft:tag':
            print(f'  Pool {i}: tag={entry["name"]}')
