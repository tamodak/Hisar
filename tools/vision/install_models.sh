#!/bin/sh
# Puts the image models into app/src/main/assets/vision. They are kept out of git (about 300 MB):
# the outline mask's models come from the Photo Filter project (~/omer/android/photo-filter, made
# by its tools/ scripts); body_head.bin is made by tools/vision/head.py.
set -e
here="$(cd "$(dirname "$0")" && pwd)"
from="${PHOTO_FILTER:-$HOME/omer/android/photo-filter}/app/src/main/assets"
to="$here/../../app/src/main/assets/vision"
mkdir -p "$to"
for f in clip_vit_b32_int8.tflite clip_text_embeddings.bin efficientdet_lite2.tflite movenet_lightning_int8.tflite \
         clipseg_vision.tflite clipseg_decoder.tflite clipseg_text_embeddings.bin sam_encoder.tflite sam_decoder.tflite; do
  cp "$from/$f" "$to/$f"
done
echo "copied to $to"
