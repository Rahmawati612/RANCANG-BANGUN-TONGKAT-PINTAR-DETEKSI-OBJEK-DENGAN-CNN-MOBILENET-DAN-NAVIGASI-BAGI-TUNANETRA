import os, json, requests
from pathlib import Path
from zipfile import ZipFile

TARGET_CLASSES = {
    1:  'orang',       # person
    2:  'sepeda',      # bicycle
    3:  'mobil',       # car
    4:  'motor',       # motorcycle
    15: 'bangku',      # bench
}

# ✅ Target per class disesuaikan dengan kebutuhan
MAX_PER_CLASS_TRAIN = {
    1:  1500,   # orang  
    2:  1500,   # sepeda 
    3:  1500,   # mobil  
    4:  1500,   # motor  
    15: 1500,   # bangku 
}

MAX_PER_CLASS_VAL = {
    1:  200,
    2:  200,
    3:  200,
    4:  200,
    15: 200,
}

SAVE_DIR = Path("dataset/coco")
(SAVE_DIR / "train").mkdir(parents=True, exist_ok=True)
(SAVE_DIR / "val").mkdir(parents=True, exist_ok=True)

def download_file(url, dest):
    if dest.exists():
        print(f"✅ Skip {dest.name}"); return
    print(f"⬇️  Downloading {dest.name}...")
    r = requests.get(url, stream=True)
    total = int(r.headers.get('content-length', 0))
    done = 0
    with open(dest, 'wb') as f:
        for chunk in r.iter_content(8192):
            f.write(chunk); done += len(chunk)
            if total > 0:
                print(f"\r  {done/total*100:.1f}%", end='', flush=True)
    print()

# Download annotations
ann_zip = SAVE_DIR / "annotations.zip"
download_file(
    "http://images.cocodataset.org/annotations/annotations_trainval2017.zip",
    ann_zip
)
ann_dir = SAVE_DIR / "annotations"
if not ann_dir.exists():
    print("📦 Extracting annotations...")
    with ZipFile(ann_zip) as z: z.extractall(SAVE_DIR)
    print("✅ Annotations extracted!")

def filter_and_download(split, max_per_class_dict):
    ann_file = SAVE_DIR / f"annotations/instances_{split}2017.json"
    img_dir  = SAVE_DIR / ("val" if split == "val" else "train")
    out_ann  = img_dir / "_annotations.coco.json"

    # ✅ Hapus cache lama agar bisa download ulang dengan jumlah baru
    if out_ann.exists():
        print(f"🗑️  Hapus cache {split} lama untuk update jumlah...")
        out_ann.unlink()

    print(f"\n📂 Processing {split}...")
    with open(ann_file) as f: coco = json.load(f)

    valid_cat_ids = set(TARGET_CLASSES.keys())
    filtered_cats = [c for c in coco['categories'] if c['id'] in valid_cat_ids]

    # ✅ Rename ke nama Indonesia
    for cat in filtered_cats:
        cat['name'] = TARGET_CLASSES[cat['id']]

    filtered_anns = [a for a in coco['annotations'] if a['category_id'] in valid_cat_ids]

    # ✅ Pilih gambar per class sesuai target masing-masing
    img_ids_per_class = {cid: set() for cid in valid_cat_ids}
    for ann in filtered_anns:
        cid = ann['category_id']
        max_target = max_per_class_dict.get(cid, 500)
        if len(img_ids_per_class[cid]) < max_target:
            img_ids_per_class[cid].add(ann['image_id'])

    selected_ids    = set().union(*img_ids_per_class.values())
    filtered_imgs   = [i for i in coco['images'] if i['id'] in selected_ids]
    filtered_anns   = [a for a in filtered_anns if a['image_id'] in selected_ids]

    # ✅ Tampilkan distribusi per class
    print(f"\n📊 Distribusi per class ({split}):")
    for cid, ids in img_ids_per_class.items():
        nama   = TARGET_CLASSES[cid]
        target = max_per_class_dict.get(cid, 500)
        print(f"   {nama:<10}: {len(ids):>4} gambar (target: {target})")
    print(f"\n   Total: {len(filtered_imgs)} gambar | {len(filtered_anns)} anotasi")

    # ✅ Download gambar
    print(f"\n⬇️  Downloading {split} images...")
    ok = fail = skip = 0
    for img in filtered_imgs:
        dest = img_dir / img['file_name']
        if dest.exists():
            skip += 1; ok += 1; continue
        url = f"http://images.cocodataset.org/{split}2017/{img['file_name']}"
        try:
            r = requests.get(url, timeout=15)
            if r.status_code == 200:
                dest.write_bytes(r.content); ok += 1
            else:
                fail += 1
        except Exception as e:
            fail += 1
        print(f"\r  ✅ {ok} downloaded | ⏭️  {skip} skipped | ❌ {fail} failed", end='', flush=True)
    print()

    # ✅ Simpan annotations
    json.dump(
        {
            'images'     : filtered_imgs,
            'annotations': filtered_anns,
            'categories' : filtered_cats
        },
        open(out_ann, 'w')
    )
    print(f"✅ {split} selesai! Annotations disimpan.")

# Jalankan download
print("=" * 50)
print("🚀 COCO Download - Target Seimbang")
print("=" * 50)

filter_and_download('train', MAX_PER_CLASS_TRAIN)
filter_and_download('val',   MAX_PER_CLASS_VAL)

print("\n" + "=" * 50)
print("🎉 COCO download selesai!")
print("=" * 50)
print("\n📊 Target distribusi final:")
for cid, nama in TARGET_CLASSES.items():
    print(f"   {nama:<10}: ~{MAX_PER_CLASS_TRAIN[cid]} gambar dari COCO")
print("\n➡️  Selanjutnya jalankan: python merge_datasets.py")