import os, json, shutil
os.environ['PROTOCOL_BUFFERS_PYTHON_IMPLEMENTATION'] = 'python'
from pathlib import Path

# ============================================================
# RENAME MAP
# ============================================================
RENAME_MAP = {
    'person'     : 'orang',
    'car'        : 'mobil',
    'motorcycle' : 'motor',
    'bicycle'    : 'sepeda',
    'bench'      : 'bangku',
    'lubang'     : 'lubang',
    'tembok'     : 'tembok',
    'pohon'      : 'pohon',
}

ALLOWED_CLASSES = {
    'orang', 'mobil', 'motor', 'sepeda',
    'bangku', 'lubang', 'tembok', 'pohon'
}

# ============================================================
# URUTAN CATEGORY — harus sama dengan label_map.pbtxt & Android
# ============================================================
CATEGORY_ORDER = [
    'orang',   # ID 1
    'mobil',   # ID 2
    'motor',   # ID 3
    'sepeda',  # ID 4
    'bangku',  # ID 5
    'lubang',  # ID 6
    'tembok',  # ID 7
    'pohon',   # ID 8
]

# ============================================================
# PATH DATASET
# ============================================================
SOURCES_TRAIN = [
    {
        'ann': 'dataset/coco/train/_annotations.coco.json',
        'img': 'dataset/coco/train',
    },
    {
        'ann': 'dataset/roboflow/train/_annotations.coco.json',
        'img': 'dataset/roboflow/train',
    },
]

SOURCES_VALID = [
    {
        'ann': 'dataset/coco/val/_annotations.coco.json',
        'img': 'dataset/coco/val',
    },
    {
        'ann': 'dataset/roboflow/valid/_annotations.coco.json',
        'img': 'dataset/roboflow/valid',
    },
]

OUTPUT_DIR = 'dataset/merged'


def merge(sources, out_dir):
    Path(f"{out_dir}/images").mkdir(parents=True, exist_ok=True)

    # Inisialisasi categories dengan urutan yang sudah ditentukan
    cat_map = {name: i+1 for i, name in enumerate(CATEGORY_ORDER)}
    categories = [
        {'id': i+1, 'name': name, 'supercategory': 'object'}
        for i, name in enumerate(CATEGORY_ORDER)
    ]

    merged = {'images': [], 'annotations': categories.copy(), 'categories': categories}
    merged['annotations'] = []

    img_id = ann_id = 1

    for src in sources:
        if not Path(src['ann']).exists():
            print(f"  ⚠️  Skip (tidak ditemukan): {src['ann']}")
            continue

        with open(src['ann']) as f:
            data = json.load(f)

        # 1. Map category_id sumber → category_id merged
        local_cat = {}
        for cat in data['categories']:
            raw_name = cat['name']
            std_name = RENAME_MAP.get(raw_name.lower(), raw_name.lower()).strip()
            if std_name in cat_map:
                local_cat[cat['id']] = cat_map[std_name]

        # 2. Filter anotasi
        valid_img_ids = set()
        valid_anns = []

        for ann in data['annotations']:
            if ann['category_id'] not in local_cat:
                continue
            valid_anns.append(ann)
            valid_img_ids.add(ann['image_id'])

        # 3. Salin gambar
        local_img_map = {}
        skipped_img = 0

        for img in data['images']:
            if img['id'] not in valid_img_ids:
                continue

            src_path = Path(src['img']) / img['file_name']
            if not src_path.exists():
                alt_path = Path(src['img']) / Path(img['file_name']).name
                if alt_path.exists():
                    src_path = alt_path
                else:
                    skipped_img += 1
                    continue

            new_name = f"{img_id:08d}_{Path(img['file_name']).name}"
            dst_path = Path(f"{out_dir}/images/{new_name}")
            shutil.copy2(src_path, dst_path)

            merged['images'].append({
                **img,
                'id': img_id,
                'file_name': f"images/{new_name}"
            })
            local_img_map[img['id']] = img_id
            img_id += 1

        if skipped_img:
            print(f"  ⚠️  {skipped_img} gambar tidak ditemukan di {src['img']}")

        # 4. Tulis anotasi dengan ID baru
        for ann in valid_anns:
            if ann['image_id'] not in local_img_map:
                continue
            merged['annotations'].append({
                **ann,
                'id'          : ann_id,
                'image_id'    : local_img_map[ann['image_id']],
                'category_id' : local_cat[ann['category_id']]
            })
            ann_id += 1

        print(f"  ✅ Merged: {src['ann']}")

    # Simpan JSON
    with open(f"{out_dir}/_annotations.coco.json", 'w') as f:
        json.dump(merged, f)

    # Ringkasan
    counts = {}
    for ann in merged['annotations']:
        name = CATEGORY_ORDER[ann['category_id'] - 1]
        counts[name] = counts.get(name, 0) + 1

    print(f"\n  📊 Hasil → {out_dir}")
    print(f"     Gambar      : {len(merged['images'])}")
    print(f"     Anotasi     : {len(merged['annotations'])}")
    print(f"     Sebaran class:")
    for name in CATEGORY_ORDER:
        v = counts.get(name, 0)
        bar = '█' * (v // 100)
        print(f"       ID{CATEGORY_ORDER.index(name)+1} {name:12s}: {v:5d}  {bar}")

    return counts


# ============================================================
# EKSEKUSI UTAMA
# ============================================================
if Path(OUTPUT_DIR).exists():
    shutil.rmtree(OUTPUT_DIR)
    print("🗑️  Hapus folder merged lama...\n")

print("🔀 Merging Train Dataset...")
train_counts = merge(SOURCES_TRAIN, f'{OUTPUT_DIR}/train')

print("\n🔀 Merging Valid Dataset...")
valid_counts = merge(SOURCES_VALID, f'{OUTPUT_DIR}/valid')

print("\n" + "="*50)
print("🎉 Merge selesai!")
print("="*50)
print(f"\n{'ID':<5} {'Class':<14} {'Train':>8} {'Valid':>8}")
print("-" * 38)
for i, cls in enumerate(CATEGORY_ORDER, 1):
    t = train_counts.get(cls, 0)
    v = valid_counts.get(cls, 0)
    flag = ' ⚠️' if t < 800 or v == 0 else ''
    print(f"ID{i:<4} {cls:<14} {t:>8} {v:>8}{flag}")
