# AutoMix desktop bench

Runs the phone's analysis and planning code (`base/automix`) on real songs and renders the
transitions to WAV files, the way the phone mixes them (B stretched with ffmpeg's `atempo`, pitch kept).

Prepare a folder (songs are not in the repository):

```sh
tools/automix/prepare.sh <songs folder> <work folder>   # sNN.f32 per song, plus Beat This final0 references if beat_this is installed
```

Then, from `base/`:

```sh
./gradlew :automix:bench -Pargs="analyze <work folder>"                # tempo, grids, downbeats; agreement with the references
./gradlew :automix:bench -Pargs="plan <work folder> s01,s02 s03,s06"   # plans; beat alignment on the references
./gradlew :automix:bench -Pargs="plan <work folder> s01,s02 render"    # also mix-s01-s02.wav (12 s before, 15 s after)
```

The bench reads the app's Beat This model (`app/src/main/assets/automix/`) through the JVM build of
ONNX Runtime. References: `pip install beat_this` (PyTorch), then `beat_this <work folder>/*.wav`.
