import json, os

dir_path = r'common\src\main\resources\data\abadoned_greatwork\loot_tables\chests'
for fname in sorted(os.listdir(dir_path)):
    fpath = os.path.join(dir_path, fname)
    with open(fpath, 'r', encoding='utf-8') as f:
        data = json.load(f)
    pool_count = len(data.get('pools', []))
    entry_count = sum(len(p.get('entries', [])) for p in data.get('pools', []))
    print(f'{fname}: type={data["type"]}, pools={pool_count}, total_entries={entry_count}, size={os.path.getsize(fpath)} bytes')
