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

## Engine

The engine executes lanes and decides nothing. `SegueDeck` (pure Kotlin) is the sound, used both by
the phone and by the desktop render, so what is measured is what plays.

On the phone, `SegueProcessor` runs it at the head of each player's audio chain. An audio processor
acts ahead of the player's buffer, so it would be heard late if it followed the clock; here every
sample's gain is fixed by the sample's place in the song, which `SegueSink` reads from each buffer's
presentation time. The lock on the beats nudges only the player's speed, downstream of the chain, so
the lanes stay on the song's musical time. Unarmed, the stage copies its input exactly. It exists only
in the Mix Lab build; other builds keep their chain as it was.

- The incoming deck is armed when prepared: before its window its level lane is null, so its run-up is silent.
- The outgoing side is armed `SEGUE_ARM_LEAD_MS` before its audio chain reaches the cue, once both
  songs are placed and buffered. The chain runs ahead of the player by its whole buffer, from 0.5 to 3 s
  on the emulator, and what it has passed can no longer change, so this is counted from the chain's position.
  Under `SEGUE_ARM_MIN_MS` without that, the deck is dropped while the song is still whole, and the
  plan is computed again.
- If the incoming song stalls during the window, the outgoing one returns to its plain sound
  (`holdRampUs`), then both re-enter through the existing gentle fade.
- After the window the incoming tempo returns to its own as with other plans (`restoreRatePerBeat`,
  `restoreMinBeats`). This is not in the plan: it comes after the window and depends only on the rate.

Shelves and the core bell are state-variable filters: stable while they move, and exactly the identity
at 0 dB. Sends tap the signal ahead of the fader, so a tail outlives the song's level. The policy
does not write sends yet, as no class's treatment calls for them.

Next, and the provisional fade used while an analysis is missing, stay on the earlier plans: they
have no analysis to fill `audioKey` from, or no time for a policy to run.

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

## Stretches

A mix needs one good run of bars, not a good song. Locked candidates are therefore judged on their
own beats (`SegueStretch.kt`), not on a grid fitted to the song's whole ending: a tempo that wanders
elsewhere, or a bridge without drums, no longer rules out the part that holds.

A stretch is a whole number of bars. It needs most of its beats from the model, a straight line
through them that they stay close to, bars that do not slide off that line, and downbeats the model
is sure of. The line gives the stretch its own tempo and start. Only the incoming song changes speed:
the rate is the ratio of the two stretches' lengths in time, so their bar lines meet at both ends, and
between half and double tempos one has half as many bars as the other.

The expected phase error is no longer a mix of two songs' averages: the beats of the sparser stretch
are measured against the nearest beats of the denser one, at the rate the plan will use. Plans carry
`beatKey`, which covers the bars and the model's beats, so a new analysis of them invalidates the plan.

## Shapes

How a transition sounds is its lanes, so the engine has nothing to know of a shape (`SegueShape`).

| Shape | Class | What is heard |
|---|---|---|
| `BASS_SWAP` | locked | The bass changes hands at one point, over level ramps |
| `THREE_BAND` | locked | Bass, then middle, then top change hands |
| `CUT_ON_BEAT` | locked | One song, then the other, on a shared downbeat |
| `FILTER_OPEN` | locked | The incoming song opens from a closed filter up to the bass hand-over |
| `SWEEP_OUT` | filtered | The outgoing song filtered away while the incoming one rises |
| `EVEN_FADE` | filtered | Both levels equal-power, the bass handed over |
| `QUICK_OUT` | filtered | The outgoing song leaves early, for a voice in its way |
| `LATE_OUT` | filtered | The incoming song is up early, for a voice coming in it |

What the music asks for comes first: two voices in a short window meet on a cut, and a cut is refused
across a loudness jump. Otherwise the shape comes from a hash of the two audio keys and the anchors,
so a pair always sounds the same and a playlist does not repeat one effect.

A fade's length starts from the longest that stays clear of both songs' voices, instead of always
the longest allowed.

## Policy

Order: generate candidates, reject by guards, score, keep the best if it clears its class's
threshold, otherwise go to the next level. A crossed guard can never be bought back by good notes.
Choosing a pair is joint: pairs (exit, entry) are evaluated, at most `candidatesPerSide` per side,
and the same request always gives the same plan.

| Level | Class | Requires |
|---|---|---|
| `LOCKED_LONG` | locked | Reachable tempos, reliable grids, phrases on both sides, the longest length |
| `LOCKED_SHORT` | locked | The same, shorter |
| `LOCKED_BEAT` | locked | Any shared downbeat, 4 or 2 bars, and only if the bass is managed |
| `FILTERED` | filtered | No alignment; filtered exit, limited overlap, progressive entry, without two voices |
| `HARD` | hard | Clear boundaries, close loudness, on a downbeat; an anti-click fade alone |
| `SIMPLE_FADE` | fade | A short fade from a safe point |
| `NORMAL` | none | Plain playback. Not a failure: no effect beats a bad effect |

Each level left behind records its cause in `SegueDecision.attempts`, outside the fingerprint. Within
a level the longest length that works is kept: a window is shortened only when it has to be.

The score is `Σ weight × reliability × note − Σ penalty × defect`. Notes and defects run from 0 to 1.
Scores compare only within a class; between classes the fallback order decides.

| Term | Kind | Role | Form |
|---|---|---|---|
| Rhythm | Note, strong | Makes the passage forgettable | Falls from `rhythmFullUs` to `phaseMedianMaxUs` |
| Phrase | Note, strong | Makes the sections coincide | 0.5 per side on a boundary at the start, `phraseNoteInside` at the middle or end |
| Energy | Note, medium | Avoids holes in the tension | Gaussian on the level gap, `energySigmaDb` |
| Exit | Note, medium | Leaves at the end of a phrase or on a calm section | Share without voice in the window's second half |
| Entry | Note, medium | Avoids entering mid-phrase | 1 on the first attack, less on a later section, less with the time skipped |
| Key | Note, weak | Reduces clashes | Distance on the wheel of fifths |
| Speed | Defect, medium | Prefers the least alteration | Rises from `rateCostFreePpm` to the guard |
| Voice | Defect, strong | Goes beyond the guard | Overlap weighted by audibility, over `voiceOverlapMaxUs` |
| Voice cut | Defect, medium | Leaves no voice unheard | A's voice after the window and B's before the entry, as shares of each song's |
| Bass | Defect, medium | Limits masking | Common energy over total energy, 20–150 Hz, after levels and shelves |
| Lost | Defect, weak to medium | Prefers keeping the music | Seconds removed over `contentCutMaxUs` |
| Sobriety | Defect, weak | Avoids gratuitous effects | Effect lanes over `sobrietyFullEffects` |

`SegueTuning.kt` documents each parameter, with its label and range, and is the only place for them.

## Measuring

No value here counts until measured on the project's corpus. The bench (`tools/automix/README.md`)
covers the objective part, in the protocol's order:

- `split` cuts the corpus by artist and album into tuning, validation and final test (60/20/20 `[I]`),
  so a value is never tested on the songs it was tuned on.
- `measure` checks the render's integrity first (peak, clipping, loudness jump, clicks at the edges,
  silent holes), then compares the phase measured on the render with the plan's `residualUs`.
- `matrix` reports each pair's level and the causes of the levels left behind, and that a second run
  gives the same fingerprint.
- `stability` varies each parameter by ±20 % `[I]` and lists those that change no plan. Among the
  policy's parameters these are candidates for removal; the sound's own (filters, sends) never change a
  plan and are judged by ear.

Still to do, as they need people or annotations: fixing thresholds by controlled degradations in blind
listening; learning the weights from pairwise comparisons with a simple non-negative model,
cross-validated by artist; checking the analysis against annotated beats; freezing the parameters
before a single listen to the final test; then recording each value with its corpus, date and
interval, and relabelling it `[M]`.

The desktop render stretches with `atempo` and has no player clock, so it does not measure the phone's
own timing; `automix.status` reports the chain's position and the deck's distance on the phone.

## Parameters that were not in the design's tables

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
| `stretchMinBeatShare`, `stretchMinDownConfidence` | 0.85, 0.5 | What a stretch must have to be relied on |
| `penaltyVoiceCut`, `phraseNoteInside` | 0.5, 0.25 | The two score terms added with stretches |
| `voiceBarRisk`, `lockedTinyBars` | 0.6 `[R]`, 2 | A bar counted as sung; the shortest locked length |
| `overlapMaxSum` | 1.25 | Two masters' peaks add up on a shared beat; 1 never clips but dips by 3 dB. Bench, 120 locked renders: unbounded 63 clipped, 1.25 46, 1.0 7 |
| `coreOutDb`, `coreInDb`, `topInDb`, `cutBeatShare` | −24, −12, −12 dB, 0.25 | The three-band and cut shapes |
| `blockFrames`, `shelfLowHz`, `shelfHighHz`, `holdRampUs` | 64, 180 Hz, 2.5 kHz, 400 ms `[R]` | Values the player and the render already use |
| `shelfQ`, `coreQ` | 0.707 `[U]`, 0.5 | The slope of a shelf, and a bell wide enough to span the middle band |
| `echoDelayUs`, `echoFeedback`, `sendTailUs` | 375 ms, 0.35, 3 s | The sends need a sound; the policy does not use them yet |
| `roomCombUs`, `roomAllPassUs`, `roomCombFeedback`, `roomAllPassGain` | Schroeder delays `[U]`, 0.75, 0.7 `[U]` | Same |

## Sends and the rising filter

Fades and locked mixes can now ring out. `riseHz` is a high-pass on a side, the mirror of `sweepHz`; a null knot leaves it open at 20 Hz. `echoUs` on the plan sets the echo send's delay from the outgoing song's beat, three quarters of a beat brought into 240-620 ms; a plan without it uses the engine's default. Sends leave out everything below 250 Hz so the tail does not sit on the incoming bass.

Four shapes use them. `ECHO_OUT` throws the outgoing song into the echo on the downbeat nearest 72 % of a fade. `WASH_OUT` thins it from below into the room. `RISE_OUT` and `ECHO_TAIL` are the same two ideas on a locked mix. A shape with a tail is offered only when the outgoing song runs on past the window for at least `sendTailUs`: its player has to keep feeding the deck for the tail to sound.

## What is skipped

The level a pair reaches is still judged on `score`, but the candidate within a level is chosen on `pick`: the score less a cost for the music nobody hears, the end of one song and the start of the other. Taking this out of the score keeps a locked mix from losing its level because it skips an intro. Fades pay more for it than locked mixes, which have a reason to skip. The latest exits always stay in the running, including those with a voice that lasts to the end, so the choice between the two is the score's. Fades run 4, 6 or 8 s.

## Voices from lyrics

The spectral estimate in `barVocalRisk` mostly sees that a bar could hold a voice: on the bench corpus, checked against the songs' timed lyrics, it finds about a third of the sung bars at the threshold the policy uses, and lowering the threshold only approaches "every bar". When a song's timed lyrics are already on the device, `withSung` replaces it for that decision and nothing is fetched for it. On the corpus, with both songs' lyrics known, locked mixes where two voices meet for over 2 s fall from 53 % to under 1 %, windows get shorter by themselves, and the share of pairs reaching a locked level goes from 25 % to 19 %: these are the mixes that could not avoid a clash. The numbers are not an independent test, since the lyrics that judge are also the ones used, and a song without lyrics gets the estimate as before. The journal says which was used.

A song without lyrics gets `VocalRisk.learned` instead of the raw estimate: a small logistic model over the bar measurements, fitted on bars labelled from timed lyrics and checked on songs left out, where it ranks bars at 0.86 against 0.71. Judged on the corpus's lyrics with no lyrics given to the policy, locked mixes where voices meet for over 2 s fall from 53 % to 17 %, and fewer pairs lock. The weights come from the same corpus, mostly rap and pop, so a very different catalogue may do worse; the worst this does is a shorter, plainer transition.

## The last levels

A cut that skips more than `hardLostMaxUs` of music, both songs together, is refused: a short fade costs less. `SIMPLE_FADE` stays at its usual length when it can; over a voice it is `simpleVoiceFadeUs` at the very end, without the voice guard. Nothing falls to plain playback any more on the bench corpus. A fade's candidates include one that ends with the music, not on a bar.

## Small speed changes

Keeping the pitch while changing speed costs artefacts on music. A plan within 1 % goes by resampling alone (`mixParameters`), which moves the pitch by under 17 cents until it returns to the song's own speed.
