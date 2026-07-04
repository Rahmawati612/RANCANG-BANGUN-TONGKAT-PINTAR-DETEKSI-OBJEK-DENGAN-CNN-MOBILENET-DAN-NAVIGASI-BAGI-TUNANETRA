import re

config_path = "ssd_mobilenet_v2_320x320_coco17_tpu-8/pipeline.config"
with open(config_path) as f:
    config = f.read()

replacements = {
    r'num_classes: \d+'                : 'num_classes: 8',
    r'batch_size: \d+'                 : 'batch_size: 4',
    r'num_steps: \d+'                  : 'num_steps: 150000',
    r'fine_tune_checkpoint: ".*?"'     : 'fine_tune_checkpoint: "ssd_mobilenet_v2_320x320_coco17_tpu-8/checkpoint/ckpt-0"',
    r'fine_tune_checkpoint_type: ".*?"': 'fine_tune_checkpoint_type: "detection"',
    r'label_map_path: ".*?"'           : 'label_map_path: "label_map.pbtxt"',
}

for pattern, replacement in replacements.items():
    config = re.sub(pattern, replacement, config)

config = re.sub(r'cosine_decay_learning_rate \{.*?\}',
    '''cosine_decay_learning_rate {
        learning_rate_base: 0.02
        total_steps: 150000
        warmup_learning_rate: 0.005
        warmup_steps: 500
      }''', config, flags=re.DOTALL)

config = config.replace(
    'data_augmentation_options {\n    ssd_random_crop {\n    }\n  }',
    '''data_augmentation_options {
    ssd_random_crop {
    }
  }
  data_augmentation_options {
    random_adjust_brightness {
      max_delta: 0.2
    }
  }
  data_augmentation_options {
    random_adjust_contrast {
      min_delta: 0.8
      max_delta: 1.25
    }
  }'''
)

config = re.sub(r'(train_input_reader.*?input_path: )".*?"',
                r'\1"tfrecords/train.record"', config, flags=re.DOTALL)
config = re.sub(r'(eval_input_reader.*?input_path: )".*?"',
                r'\1"tfrecords/valid.record"', config, flags=re.DOTALL)

with open(config_path, 'w') as f:
    f.write(config)

print("✅ Pipeline config updated!")
print("   num_classes : 8")
print("   batch_size  : 4")
print("   num_steps   : 150000")
print("   checkpoint  : ckpt-0 (training baru)")
print("   lr_base     : 0.02")
print("   augmentasi  : crop + brightness + contrast")