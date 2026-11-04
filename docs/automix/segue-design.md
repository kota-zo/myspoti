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
