classes = [
    'mobil',   # ID 1
    'motor',   # ID 2
    'sepeda',  # ID 3
    'bangku',  # ID 4
    'lubang',  # ID 5
    'orang',   # ID 6
    'pohon',   # ID 7
    'tembok',  # ID 8
]

with open('label_map.pbtxt', 'w') as f:
    for i, name in enumerate(classes, 1):
        f.write(f'item {{\n  id: {i}\n  name: \'{name}\'\n}}\n\n')

print("✅ label_map.pbtxt diperbarui!")
for i, name in enumerate(classes, 1):
    print(f"  ID {i} → {name}")