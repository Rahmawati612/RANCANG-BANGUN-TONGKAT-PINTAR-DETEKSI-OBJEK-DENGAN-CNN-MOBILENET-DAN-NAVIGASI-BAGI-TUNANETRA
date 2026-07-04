import os
os.environ['PROTOCOL_BUFFERS_PYTHON_IMPLEMENTATION'] = 'python'

try:
    from roboflow import Roboflow
except ImportError:
    print("📦 Installing roboflow...")
    os.system("pip install roboflow")
    from roboflow import Roboflow

SAVE_DIR = "dataset/roboflow"

print(f"📁 Dataset akan disimpan di: {SAVE_DIR}")
print("⬇️  Memulai download...\n")

try:
    rf      = Roboflow(api_key="tYbnWBPSOKXZTqfLYwBh")
    project = rf.workspace("deteksi-objek-isehc").project("my-first-project-ilkbz")
    project.version(5).download("coco", location=SAVE_DIR)

    print("\n" + "="*50)
    print("✅ Download selesai!")
    print(f"   Folder: {SAVE_DIR}")
    print("="*50)

except Exception as e:
    print(f"\n❌ Gagal download: {e}")