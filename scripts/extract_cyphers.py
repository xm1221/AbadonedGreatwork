import re

with open(r'D:\rd\casting\.minecraft\versions\1.20.1-Fabric 0.18.0\kubejs\server_scripts\src\chest.js', 'r', encoding='utf-8') as f:
    content = f.read()

# Find CYPHER_TEMPLATES block
start = content.find("const CYPHER_TEMPLATES = {")
end = content.find("};", start)
block = content[start:end+2]

# Extract each template - key is in single quotes, value is a JS string (single quoted with escaped chars)
# The pattern: 'key': 'value',
pattern = r"'([^']+)':\s*'(\{.+?\})',?\s*\n"
matches = list(re.finditer(pattern, block, re.DOTALL))

for m in matches:
    name = m.group(1)
    nbt_raw = m.group(2)
    # The raw NBT has JS escape sequences: \' for single quotes, \" for double quotes
    # We need to convert to proper SNBT string
    nbt = nbt_raw.replace("\\'", "'").replace('\\"', '"')
    print(f"=== {name} ===")
    print(f"Length: {len(nbt)}")
    print(f"Ends with: ...{nbt[-80:]}")
    print(f"Has pigment: {'pigment' in nbt}")
    print(f"Has variant: {'variant' in nbt}")
    print()
