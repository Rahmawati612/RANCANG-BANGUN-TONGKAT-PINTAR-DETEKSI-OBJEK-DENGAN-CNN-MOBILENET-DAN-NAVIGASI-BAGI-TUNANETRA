import os
os.environ['PROTOCOL_BUFFERS_PYTHON_IMPLEMENTATION'] = 'python'
import tensorflow as tf


# ============================================================
# STEP 1: Export SavedModel dari checkpoint dulu
# ============================================================
print("🔄 Step 1: Export dari checkpoint...")
os.system(
    "python models/research/object_detection/export_tflite_graph_tf2.py "
    "--pipeline_config_path=ssd_mobilenet_v2_320x320_coco17_tpu-8/pipeline.config "
    "--trained_checkpoint_dir=training_output "
    "--output_directory=tflite_export"
)

# ============================================================
# STEP 2: Convert ke TFLite (script kamu)
# ============================================================
print("\n⏳ Step 2: Konversi ke TFLite...")
converter = tf.lite.TFLiteConverter.from_saved_model(
    'tflite_export/saved_model'
)
converter.optimizations = [tf.lite.Optimize.DEFAULT]
tflite_model = converter.convert()

with open('model.tflite', 'wb') as f:
    f.write(tflite_model)

size_mb = os.path.getsize('model.tflite') / 1024 / 1024
print(f"✅ model.tflite berhasil! Ukuran: {size_mb:.2f} MB")

# ============================================================
# STEP 3: Buat labels.txt
# ============================================================
labels = ['orang','mobil','motor','sepeda','bangku','lubang','tembok','pohon']
with open('labels.txt', 'w') as f:
    f.write('\n'.join(labels))

print("✅ labels.txt berhasil!")
print("\n📱 Copy ke Android:")
print("   app/src/main/assets/model.tflite")
print("   app/src/main/assets/labels.txt")