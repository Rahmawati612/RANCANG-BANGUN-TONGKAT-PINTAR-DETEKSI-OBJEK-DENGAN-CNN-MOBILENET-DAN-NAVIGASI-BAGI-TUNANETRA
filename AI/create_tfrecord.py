import os
os.environ['PROTOCOL_BUFFERS_PYTHON_IMPLEMENTATION'] = 'python'
import sys
sys.path.append('models/research')
sys.path.append('models/research/slim')
import json
import tensorflow as tf
from object_detection.utils import dataset_util, label_map_util
from pathlib import Path

def coco_to_tfrecord(ann_file, img_base_dir, output_path, label_map_path):
    label_map = label_map_util.get_label_map_dict(label_map_path)
    with open(ann_file) as f:
        coco = json.load(f)

    # Index: image_id → annotations
    ann_by_img = {}
    for ann in coco['annotations']:
        ann_by_img.setdefault(ann['image_id'], []).append(ann)

    # Index: category_id → name
    cat_id_to_name = {c['id']: c['name'] for c in coco['categories']}

    writer = tf.io.TFRecordWriter(output_path)
    skipped = skipped_ann = 0

    for img in coco['images']:
        # ✅ Fix path: hapus prefix 'images/' kalau sudah ada di file_name
        file_name = img['file_name']
        img_path = Path(img_base_dir) / file_name
        if not img_path.exists():
            skipped += 1
            continue

        with open(img_path, 'rb') as f:
            encoded = f.read()

        width  = img['width']
        height = img['height']
        anns   = ann_by_img.get(img['id'], [])

        xmins, xmaxs, ymins, ymaxs = [], [], [], []
        class_texts, class_labels  = [], []

        for ann in anns:
            x, y, w, h = ann['bbox']

            # ✅ Validasi bbox agar tidak out of range
            xmin = max(0.0, x / width)
            ymin = max(0.0, y / height)
            xmax = min(1.0, (x + w) / width)
            ymax = min(1.0, (y + h) / height)

            # ✅ Skip bbox yang tidak valid
            if xmin >= xmax or ymin >= ymax:
                skipped_ann += 1
                continue

            name = cat_id_to_name.get(ann['category_id'], '')

            # ✅ Skip class yang tidak ada di label_map
            if name not in label_map:
                skipped_ann += 1
                continue

            xmins.append(xmin)
            ymins.append(ymin)
            xmaxs.append(xmax)
            ymaxs.append(ymax)
            class_texts.append(name.encode())
            class_labels.append(label_map[name])

        # ✅ Skip gambar tanpa anotasi valid
        if not class_labels:
            skipped += 1
            continue

        suffix = img_path.suffix.lower()
        fmt = b'jpeg' if suffix in ['.jpg', '.jpeg'] else b'png'

        feature = {
            'image/height':             dataset_util.int64_feature(height),
            'image/width':              dataset_util.int64_feature(width),
            'image/filename':           dataset_util.bytes_feature(str(img_path).encode()),
            'image/source_id':          dataset_util.bytes_feature(str(img['id']).encode()),
            'image/encoded':            dataset_util.bytes_feature(encoded),
            'image/format':             dataset_util.bytes_feature(fmt),
            'image/object/bbox/xmin':   dataset_util.float_list_feature(xmins),
            'image/object/bbox/xmax':   dataset_util.float_list_feature(xmaxs),
            'image/object/bbox/ymin':   dataset_util.float_list_feature(ymins),
            'image/object/bbox/ymax':   dataset_util.float_list_feature(ymaxs),
            'image/object/class/text':  dataset_util.bytes_list_feature(class_texts),
            'image/object/class/label': dataset_util.int64_list_feature(class_labels),
        }

        writer.write(tf.train.Example(
            features=tf.train.Features(feature=feature)
        ).SerializeToString())

    writer.close()
    print(f"✅ {output_path}")
    print(f"   Records  : {len(coco['images']) - skipped}")
    print(f"   Skipped  : {skipped} gambar, {skipped_ann} anotasi invalid")

os.makedirs('tfrecords', exist_ok=True)

print("📦 Membuat train.record...")
coco_to_tfrecord(
    ann_file       = 'dataset/merged/train/_annotations.coco.json',
    img_base_dir   = 'dataset/merged/train',
    output_path    = 'tfrecords/train.record',
    label_map_path = 'label_map.pbtxt'
)

print("\n📦 Membuat valid.record...")
coco_to_tfrecord(
    ann_file       = 'dataset/merged/valid/_annotations.coco.json',
    img_base_dir   = 'dataset/merged/valid',
    output_path    = 'tfrecords/valid.record',
    label_map_path = 'label_map.pbtxt'
)

print("\n🎉 TFRecord selesai!")
