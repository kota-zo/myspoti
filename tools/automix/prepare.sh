#!/bin/sh
# Decodes every song of $1 into $2: sNN.f32 (stereo float 44.1 kHz, the bench's input) and sNN.wav
# (mono 22.05 kHz, for the Beat This references); names.txt maps sNN to the file.
set -e
src=$1; out=$2
[ -d "$src" ] && [ -n "$out" ] || { echo "usage: $0 <songs folder> <work folder>"; exit 1; }
mkdir -p "$out"; : > "$out/names.txt"
i=0
for f in "$src"/*; do
  i=$((i + 1)); n=$(printf "s%02d" $i)
  echo "$n|$(basename "$f")" >> "$out/names.txt"
  ffmpeg -v error -y -i "$f" -ac 2 -ar 44100 -f f32le "$out/$n.f32"
  ffmpeg -v error -y -i "$f" -ac 1 -ar 22050 "$out/$n.wav"
done
if command -v beat_this >/dev/null; then beat_this "$out"/*.wav; fi
