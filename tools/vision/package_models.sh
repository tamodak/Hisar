#!/bin/sh
# Packages the image models for the in-app download (they are not in the APK, to keep it small):
#   dist/models-$VERSION/   the files to attach to the GitHub release "models-$VERSION"
#   app/src/main/assets/vision-models.json   the list the app downloads and checks (name, size, SHA-256)
# Then: gh release create models-$VERSION dist/models-$VERSION/* --repo tamodak/Hisar
# and bump hisar.modelsVersion in gradle.properties if VERSION changed.
set -e
VERSION="${1:-1}"
here="$(cd "$(dirname "$0")" && pwd)"
root="$here/../.."
from="${PHOTO_FILTER:-$HOME/omer/android/photo-filter}/app/src/main/assets"
out="$root/dist/models-$VERSION"
mkdir -p "$out"
for f in clip_vit_b32_int8.tflite clip_text_embeddings.bin efficientdet_lite2.tflite movenet_lightning_int8.tflite \
         clipseg_vision.tflite clipseg_decoder.tflite clipseg_text_embeddings.bin sam_encoder.tflite sam_decoder.tflite; do
  cp "$from/$f" "$out/$f"
done
cp "$here/body_head.bin" "$out/body_head.bin"
cd "$out"
{
  printf '{\n  "version": %s,\n  "files": [\n' "$VERSION"
  first=1
  for f in *; do
    [ $first = 1 ] || printf ',\n'
    first=0
    printf '    { "name": "%s", "size": %s, "sha256": "%s" }' "$f" "$(stat -c %s "$f")" "$(sha256sum "$f" | cut -d' ' -f1)"
  done
  printf '\n  ]\n}\n'
} > "$root/app/src/main/assets/vision-models.json"
echo "packaged into $out"
