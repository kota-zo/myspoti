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
./gradlew :automix:bench -Pargs="matrix <work folder> long"           # every ordered pair, current app's Long/Session DJ settings
./gradlew :automix:bench -Pargs="matrix <work folder> long whole"     # same, preserving the whole-song exit policy
```

`plan` and `matrix` keep analyses in `<work folder>/.automix-bench`. The cache is invalidated by
analysis version, source size/modification time and model size/modification time. A warm matrix
does not decode or run ONNX again. Add `fresh` to `matrix` to force a fresh analysis. Each track's
cache/read/analysis time and each pair's planning time are printed separately, followed by counts
of BLEND/CUT/FADE and blends of at least 12 bars. These are plans, not transitions heard on a phone.
The matrix does not render audio; `plan … render` remains explicit.

Plan-based transitions (`docs/automix/segue-design.md`), from `base/`, with `-Pthreads=<n>`:

```sh
./gradlew :automix:bench -Pargs="segue <work folder> split"                 # tuning, validation and test folds, by artist and album, from names.txt
./gradlew :automix:bench -Pargs="segue <work folder> matrix fold=tuning"    # every ordered pair: level, causes of each level left, refusals by guard, reproducibility
./gradlew :automix:bench -Pargs="segue <work folder> measure limit=100"     # renders pairs in memory: peak, clipping, loudness jump, clicks, silent holes, phase against the plan's own expectation
./gradlew :automix:bench -Pargs="segue <work folder> stability limit=60"    # each parameter at ±20 %: how many levels and plans change, and which variations change nothing
./gradlew :automix:bench -Pargs="segue <work folder> render s01,s02"        # mix-s01-s02.wav through the same sound as the phone
```

`names.txt` has one `id|title - artist.ext` or `id|title - artist - album.ext` per song. The same
pairs are drawn on every run. Keep the `test` fold out of any tuning.

The bench reads the app's Beat This model (`app/src/main/assets/automix/`) through the JVM build of
ONNX Runtime. References: `pip install beat_this` (PyTorch), then `beat_this <work folder>/*.wav`.
