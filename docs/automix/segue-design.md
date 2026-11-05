# Segue: transition plans and the policy that writes them

A segue is a transition between two songs described as data. The policy starts from two local
analyses and writes a plan; the audio engine executes the plan and decides nothing. This file says
why each field and each parameter exists.

**No value here is a result.** Each number is a starting parameter to be confirmed by measurement.
Labels: `[M]` measured on the project's corpus; `[U]` usual order of magnitude; `[I]` inferred, a
design proposal; `[R]` reused from a value already in this repository. Today nothing is `[M]`.

## Plan format

Times are integer microseconds and modulations are stored as physical quantities, without a
conversion table: a plan can be read and checked on its own, and a float never drifts between two
runs. The format scales are in `SegueFormat.kt`.

### `SeguePlan`

| Field | Why |
|---|---|
| `schema` | `segue/<major>`: a reader knows at once whether it understands the plan |
| `origin` | Engine and tuning corpus, so a plan can be reproduced; left out of the fingerprint |
| `outgoing`, `incoming` | Two `Side` objects handled alike, so neither song is a special case in the format |
| `span` | The common mixing window |
| `pulse` | States the beat alignment explicitly, including its absence |
| `lanes` | An extensible list of continuous modulations |
| `gestures` | Discrete actions, which ramps cannot express |

### `Side`

| Field | Why |
|---|---|
| `audioKey` | SHA-256 of the decoded signal, so identity does not depend on any catalogue, path or token |
| `gridKey` | SHA-256 of the beat grid used; the plan is invalid once that grid changes. Null without a grid |
| `anchorUs` | Start of the window in this song's source time, independent of speed |
| `ratePpm` | Alignment speed as an integer, 1 000 000 = nominal. A coarser scale is not enough: rounding at 0.1 % can reach 32 ms over 64 s, more than the phase limit, whereas parts per million stay under 32 µs |
| `listenerRate` | The listener's speed setting on the same scale, kept apart so a plan can be recomputed without confusing it with alignment |

### `Span`

| Field | Why |
|---|---|
| `lengthUs` | Reference length for rendering |
| `musicalUnits` | Musical length; null when it has no meaning (a fade in seconds) |
| `unitKind` | `bar` or `beat` |
| `clock` | `elapsed` or `source`, so the time base is never implied. `elapsed` is measured before `listenerRate`. The policy writes only `elapsed`; the engine refuses `source` for now |

### `Pulse`

| Field | Why |
|---|---|
| `state` | `locked`: alignment held through the window. `loose`: aligned on one strong beat, not held. `free`: no alignment |
| `tempoMicroPerMinute` | Common target tempo, null if `free` |
| `residualUs` | Expected phase error, comparable with what is measured on a render |

### `Lane`

| Field | Why |
|---|---|
| `side` | `outgoing` or `incoming` |
| `target` | The quantity modulated, below |
| `unit` | `db`, `hz` or `ratio`: rules out any implicit reading |
| `knots` | `[t, value, form]`; `t` from 0 to 1 000 000 over the window, first and last knots required so a lane is defined everywhere |

Values are decimals with at most 3 places, so each has one spelling. The `form` of a knot describes
the arrival on it from the previous knot: `step` (holds, then jumps), `ramp` (linear in the unit),
`octave` (geometric, for frequencies) and `ease:<k>` (position^(k/1000)).

| `target` | Need | Unit | `null` means |
|---|---|---|---|
| `level` | The song's level ramp | `db` | Silence, which has no value in dB |
| `shelfBottom`, `bandCore`, `shelfTop` | Sharing the spectrum, against clashing bass | `db` | Neutral |
| `sweepHz` | Filter cutoff sweep | `hz` | Filter open |
| `sweepPeak` | Accent of that sweep | `ratio` | Neutral |
| `sendRoom`, `sendEcho` | A sound tail as the song leaves | `ratio` | No send |

While a ramp runs to or from a null knot, null stands for `LEVEL_FLOOR_DB` (−60 dB `[I]`, inaudible
under music) or `SWEEP_OPEN_HZ` (20 kHz `[I]`, the top of hearing).

### `Gesture`

| Field | Why |
|---|---|
| `kind` | `repeat` only in this version: extends an ending that is too short |
| `atUs` | Position in source time |
| `units` | Length in musical units |

The format and the validation carry gestures; the policy does not emit them and the engine refuses
a plan that has one, because a player's seek would leave a gap and the mix's progress follows the
outgoing song's position.

### Serialisation

Canonical JSON in Unicode: sorted keys, no spaces, integers where possible. The fingerprint is the
SHA-256 of that text without `origin`, so two engines writing the same plan agree on it.

### Validation

`SegueValidator` returns every violation; none means the plan may run. `structural` needs the plan
alone, `against` also needs the current analyses:

- knot positions strictly increase within the range, and cover the window;
- the unit matches the target; `octave` applies to `hz` only; frequencies are positive;
- `locked` needs both `gridKey`s and a tempo; `free` has no tempo;
- rates are strictly positive;
- the window, its speed applied, runs past neither song;
- a changed `audioKey` or `gridKey` invalidates the plan: it is recomputed, never replayed;
- another major version is refused whole; within this major an unknown field is ignored, and takes
  no part in the fingerprint, so an older reader still agrees on it.

`null` has a meaning for all of today's targets, so that rule rejects nothing yet: each new target
must state its own in `Target`.

## Analysis the policy reads

Each quantity carries a reliability: an uncertain measure weighs less, it is not treated as an average.

| Quantity | Role | Reliability |
|---|---|---|
| Tempo and stability (`Grid`) | Whether alignment is possible; half and double time are explicit in `tempoMatch` | The grid's `steadiness` |
| Beats and metre | An alignment one beat off sounds wrong | `headMetre`/`tailMetre`: how clearly the downbeat votes chose one phase |
| Phase (`PhaseStats`) | Expected double attack: median and 95th percentile distance of the model's beats from the grid | Absent with too few beats, and then a locked candidate is refused |
| Structure and phrases | A change is expected at the end of a phrase | `sectionStrength`, or `reliabilityPhraseFallback` where only the fallback phrase grid speaks |
| Energy per bar | Avoids sudden drops or jumps | Direct measurement: 1 |
| Voice-like activity | Two voices together are hard to follow | `reliabilityVocalStereo`/`Mono`: a heuristic, not vocal separation, at bar resolution (about 2 s), which makes a limit of 1 s coarse |
| Key | Reduces clashes; fragile to detect. The engine time-stretches and keeps the pitch, so speed does not move the key | `keyStrength`, 0 when unknown |
| Spectrum (`barBassDb`, 20–150 Hz) | Two bass lines mask and interfere | Direct measurement |

`audioKey` hashes the decoded analysis signal. A different decoder may give a different hash: the
plan is then recomputed, which costs nothing but a calculation.

## Policy

Order: generate candidates, reject by guards, score, keep the best if it clears its class's
threshold, otherwise go to the next level. A crossed guard can never be bought back by good notes.
Choosing a pair is joint: pairs (exit, entry) are evaluated, at most `candidatesPerSide` per side,
and the same request always gives the same plan.

| Level | Class | Requires |
|---|---|---|
| `LOCKED_LONG` | locked | Reachable tempos, reliable grids, phrases on both sides, the longest length |
| `LOCKED_SHORT` | locked | The same, shorter |
| `LOCKED_BEAT` | locked | Any shared downbeat, and only if the bass is managed |
| `FILTERED` | filtered | No alignment; filtered exit, limited overlap, progressive entry, without two voices |
| `HARD` | hard | Clear boundaries, close loudness, on a downbeat; an anti-click fade alone |
| `SIMPLE_FADE` | fade | A short fade from a safe point |
| `NORMAL` | none | Plain playback. Not a failure: no effect beats a bad effect |

Each level left behind records its cause in `SegueDecision.attempts`, outside the fingerprint.

The score is `Σ weight × reliability × note − Σ penalty × defect`. Notes and defects run from 0 to 1.
Scores compare only within a class; between classes the fallback order decides.

| Term | Kind | Role | Form |
|---|---|---|---|
| Rhythm | Note, strong | Makes the passage forgettable | Falls from `rhythmFullUs` to `phaseMedianMaxUs` |
| Phrase | Note, strong | Makes the sections coincide | 0.5 per side on a boundary |
| Energy | Note, medium | Avoids holes in the tension | Gaussian on the level gap, `energySigmaDb` |
| Exit | Note, medium | Leaves at the end of a phrase or on a calm section | Share without voice in the window's second half |
| Entry | Note, medium | Avoids entering mid-phrase | 1 on the first attack, less on a later section, less with the time skipped |
| Key | Note, weak | Reduces clashes | Distance on the wheel of fifths |
| Speed | Defect, medium | Prefers the least alteration | Rises from `rateCostFreePpm` to the guard |
| Voice | Defect, strong | Goes beyond the guard | Overlap weighted by audibility, over `voiceOverlapMaxUs` |
| Bass | Defect, medium | Limits masking | Common energy over total energy, 20–150 Hz, after levels and shelves |
| Lost | Defect, weak to medium | Prefers keeping the music | Seconds removed over `contentCutMaxUs` |
| Sobriety | Defect, weak | Avoids gratuitous effects | Effect lanes over `sobrietyFullEffects` |

`SegueTuning.kt` documents each parameter, with its label and range, and is the only place for them.

### Parameters that were not in the design's tables

These had no value or only a word, and were added as proposals to measure. All are `[I]` unless
marked `[R]`, reused from this repository.

| Parameter | Value | Why it is needed |
|---|---|---|
| Strong, medium, weak, weak to medium | 1.0, 0.5, 0.2, 0.35 | The design names the weights qualitatively |
| `gridMinBeats`, `gridMinSteadiness`, `gridMaxDriftMs` | 16, 0.6, 30 ms `[R]` | The design leaves the grid's reliability floor to measurement; these are the existing `Grid.reliable` |
| `thresholdLocked` | 0.5 `[R]` | The earlier blend floor, on a different scale |
| `thresholdFiltered`, `thresholdHard` | 0.25 | Each class needs a threshold |
| `contentCutMaxUs` | 60 s | The design caps the cut in seconds without a value |
| `gaplessMaxSilenceUs` | 300 ms | What counts as a junction without silence |
| `entryNoteSection`, `entryNoteOther` | 0.6, 0.3 | A later entry must score below the first attack |
| `sobrietyFullEffects`, `bassSampleSteps`, `curveKnots` | 3, 32, 16 | Normalisation and sampling |
| `reliabilityPhraseFallback`, `reliabilityVocalStereo`, `reliabilityVocalMono` | 0.3, 0.6, 0.2 | Measures with no confidence of their own |
| `reliabilityForLong`, `reliabilityForMiddle` | 0.8, 0.6 | The lower the reliability, the shorter the window |
| `filteredMiddleUs` | 8 s | Between the design's 4 and 12 s |
| `simpleFadeUs`, `entryZoneUs`, `halfDoubleMaxBars` | 6 s, 40 s, 8 bars `[R]` | Values the player already uses |
| Lane shapes (`bassKillDb`, `outgoingTopDb`, `incomingBedGain`…) | see `SegueTuning` `[R]` | The curves the player already plays |
| `sweepClosedHz`, `sweepPeakRatio` | 400 Hz, 1.2 | The design asks for a filtered exit without values |
