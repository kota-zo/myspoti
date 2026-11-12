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
| `bassBand` | The band under the isolator's crossover (fourth order, summed back flat): a bass kill or swap that removes the band | `db` | Neutral |
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

`loop` is the exception: the deck goes round a short slice of the outgoing song in its own buffer
while the player runs on, so nothing seeks. `atUs` is where in the song, `units` the slice's
microseconds. Only the Orpheon planner emits it.

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
the lanes stay on the song's musical time. Unarmed, the stage copies its input exactly.

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

How a transition sounds is its lanes, so the engine has nothing to know of a shape (`SegueShape`,
in `SegueCompact.kt`). Each is drawn once per pair from a hash of the two audio keys, so a pair
always sounds the same and a playlist does not repeat one effect.

| Shape | What is heard |
|---|---|
| `THIN_SWAP` | Both at full level; the incoming song opens down from a high-pass to the middle, the outgoing one thins up from it |
| `DARK_SWAP` | Both at full level; the incoming song opens up from a low-pass to the middle, the outgoing one darkens from it |
| `OVERLAP_SWAP` | Each song alone at its own end of the window, both over its middle fifth |
| `HANDOVER` | The incoming song rises to the middle, the outgoing one falls after it |
| `PLAIN_FADE` | An equal-power fade and nothing else, for songs whose pulses do not meet |

Every locked shape hands the bass over at the middle on the bass isolator (`BASS_BAND`, a 4th-order
crossover): the outgoing band is killed just before the change and the incoming one comes in after
it. Without the EQ only the two level shapes (`OVERLAP_SWAP`, `HANDOVER`) are written.

## Policy

The planner is `SeguePolicy.decide`, which runs the compact policy (`CompactPolicy`, `SegueCompact.kt`).
It is the only planner: the earlier one, with its levels scored on many terms, has been removed.

- Locked mixes are tried first. A song whose beat cannot be relied on (no stretch of its own
  beats, see Stretches) is never locked, whatever its partner.
- Two songs lock when their stretches' tempos differ by at most `lockMaxTempo` (10 %), with half or
  double time allowed where the faster tempo is in `pairFoldMinBpm`..`pairFoldMaxBpm`. Only the
  incoming song changes speed, by the ratio of the two stretches' lengths.
- A locked window is 2, 4, 8 or 16 bars of the measured bars. It ends shortly before the end of the
  outgoing music (`exitBestFrom`..`exitBestTo` bars, a step for each bar further away) and starts
  a couple of bars into the incoming song (`entryBestFrom`..`entryBestTo`). An instrumental
  ending is preferred to a sung one.
- Each candidate's cost is a fixed sum of steps: its length past `lengthFreeSec`, a key clash, the
  tempo gap, its place in each song, sung bars, and the phase error. Costs are quantised to
  `step`, so near-equal candidates are equal: then the longest window wins, then the earliest exit,
  then the earliest entry.
- Level names: `LOCKED_LONG` for 16 bars, `LOCKED_BEAT` for 2 bars, `LOCKED_SHORT` otherwise.
- Otherwise, a plain fade of `fadeUs` (5 s) centred on the end of the outgoing music (`fadeEnd`,
  the last bar at least `fadeEndDb` loud, moved by `fadeEndOffsetUs`). The incoming song starts
  `fadeLeadUs` before its first sound. This is `SIMPLE_FADE`.
- Nothing falls to plain playback except where the music says so: an album whose songs run on
  without a gap (`ALBUM_CONTINUITY`), or a fade that does not fit the file (`NORMAL`). Each level
  left behind records its cause in `SegueDecision.attempts`, outside the fingerprint.
- Deterministic: the same request gives the same plan. The listener's settings may only tighten its
  limits (`maxRatePpm`, `maxBars`).

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

## Parameters

The compact planner's values are in `CompactTuning` (`SegueTuning.kt`), labelled as the other
parameters are. Each is `[M]` (measured on the project's corpus, fixed by the bench) or `[I]`
(proposal) or `[U]`, `[R]` (usual order, reused). The main ones:

| Parameter | Value | Why |
|---|---|---|
| `lockMaxTempo`, `lockFreeTempo`, `tempoCost` | 10 %, 4.5 %, 0.1 `[M]` / `[I]` | The tempo gap that may lock, the gap that costs nothing, and what it costs |
| `shortestBars`, `longestBars` | 2, 16 `[M]` | Locked window lengths; each doubles |
| `exitMinShare`, `entryMaxShare` | 0.67, 0.29 `[M]` | No exit before two thirds of the song, no entry past 29 % of B |
| `exitBestFrom`, `exitBestTo`, `exitNearBars`, `exitFarBars` | 0.5, 3, 8, 16 bars `[M]` | Where the window ends relative to the music |
| `entryBestFrom`, `entryBestTo`, `entryLastBar` | 2, 4, 12 bars `[M]` | Where the window starts in B |
| `lengthFreeSec`, `lengthCurve` | 5 s, 0.0002 `[M]` | Cost of a longer window |
| `step` | 0.025 `[M]` | Candidates closer than this are equal |
| `keyCost`, `clashCost` | 0.1 `[M]`, 0.1 `[I]` | A key clash; two voices together in the window |
| `fadeUs`, `fadeMinUs`, `fadeLeadUs` | 5 s, 2.51 s, 2.5 s `[M]` | The plain fade |
| `fadeEndDb`, `fadeEndOffsetUs` | −20 dB, 0.75 s `[M]` | Where the outgoing music ends |
| `introMinBars` | off (1000) `[M]` | Long quiet intros skipped; off until the arrival detection is tuned |
| `bassKillDb` | −80 dB `[I]` | The band gone, not a shelf turned down |
| `lowPassHz`, `highPassHz`, `jumpUs` | 150 Hz, 6 kHz `[M]`, 20 ms `[I]` | Filter ends of the shapes; how long a jump takes |
| `restoreMinBars`, `restoreMaxPerBar`, `restoreFromGap` | 4 bars, 0.5 %, 0.5 % `[I]` | B's gentle return to its own speed after the lock |

The engine's own sound (headroom, shelves, the isolator, sends) is in `SegueTuning`; the planner
does not write sends, so those values are for the deck only. The sends (`echoDelayUs`,
`roomCombUs`, `sendTailUs`) are played when a plan asks for them.

## The Orpheon planner

A second planner (`OrpheonPolicy`), behind the Mix Lab's switch for an A/B against the compact one.
It reads only `SongMap`, which any analyser can fill, and tries four families in turn: beat-locked,
smooth blend, silence join (the song plays to its end and the next one is butted against it), plain
fade. Its values are in `OrpheonTuning` and were set on the bench against reference plans
(`segue <dir> orpheon`), then on renders; none is taken from another product.

### Which pairs lock

A pair that should not lock plays to its end: that is a decision, not a failure. Two hip-hop songs
lock unless one is hardly danceable (`lockMinDanceHiphop`) or the outgoing one is acoustic; any other
pair locks only when both songs are plainly danceable (`lockMinDanceability`). When no analyser gives
a song's danceability it is taken to be what its family usually is (`assumeDance` and the like), which
on the phone means: hip-hop with hip-hop, dance with dance, pop with pop or dance, nothing with a
family that has no steady beat. An estimate from our own measurements (metre, weight of the low end
and of the top, voiced share) was fitted on 60 songs and ranked them at 0.62 against the reference
values, but placed fewer pairs in the right family than the family's usual value does: it was left out.

The tempo gap is read on the bars around the junction, not on the songs' stated tempos, which
analysers get wrong by a factor of two more often than they misplace a bar line.

### Where a locked window sits

- It ends on the last bar line of the outgoing song's body: the last point within `exitLoudDb` of the
  main level. A bar later mixes the song's tail, a bar sooner leaves music unplayed; both cost.
  Windows start on the song's half phrases counted from its first bar (`exitOnPhrase`).
- Each style says where in its window the incoming song takes the lead (`Arrival`): from the first
  bar for a blend, a quarter in for a long blend, one bar before the end where the outgoing song
  leaves on an echo, on the last bar line where it loops out. The planner lands that point on the
  start of a phrase of the incoming song, four bars or eight short ones (`phraseSec`), half-way
  through a phrase at a small cost. A blend that leads from its first bar plays the song from its
  start unless its first phrase is a quiet opening; any other style arrives on the second phrase,
  so some of the song is heard coming before it takes over.
- A style written for a family plays between two songs of that family; any other pair gets the
  general blends. Each style has the length it is written for, and of its bar counts the one nearest
  to it is used: a short style lasts about 7 s at any tempo.
- A style is drawn first, by its weight and how well its best place scores, then the best place for
  it: the draw varies the kind of transition from pair to pair, not the care it is placed with.

### Sound

Frequencies glide in octaves, so a sweep is heard at an even pace. The blend closes the outgoing
song's low-pass slowly then faster and opens the incoming song from its top downwards, a little
under at first. The delay out keeps the incoming song thin and low under the outgoing one, opens it
on a bar line and leaves the outgoing song as an echo of three quarters of a beat over the last bar.
The loop out goes round the outgoing song's last beat while its low-pass closes, the incoming song
opening fully on the window's last beat. Inside the window a side with a send keeps a share of the
headroom (`Headroom.sendPeak`), so its echo is neither crushed by the ceiling nor added on top of it.

### Ideas

`MixIdea` lists what is tried on top of the planner. Each is one bit of `SegueTuning.ideas` and one
switch of the Mix Lab (`automix.settings <name> on|off`), so it can be heard against the planner
without it; with none, plans are those of the planner alone. They add lanes and never replace one a
style wrote.

| Idea | What changes | Why |
|---|---|---|
| `bass-handover` | The incoming song's bass band is out until the outgoing song leaves (the isolator); a plain fade swaps the two in its middle | Two bass lines together mask each other and add up |
| `vocal-clear` | A lead vocal starting over another costs three times as much; where one still does, the outgoing song's core band is turned down under it | Two voices at once are the clash a listener notices first |
| `echo-tail` | The outgoing song's last beat is sent into an echo where a style would cut it dry | A cut is heard as a cut; a tail hides it |
| `phrased-fade` | A join or a fade that is not locked moves by half a beat at most so the incoming song's first downbeat falls on the outgoing song's beat | The pulse carries over the join although the tempos differ |
| `energy-style` | Blends are drawn more often into a quiet opening, short arrivals more often when both sides are loud | A hard arrival on a quiet opening sounds like a mistake |
| `gentle-tempo` | Neither song changes speed by more than `gentleMaxRate`; pairs further apart are not locked | Large stretches are audible on voices |

The Mix Lab starts with the first four. `energy-style` and `gentle-tempo` move plans away from the
reference (fewer locked pairs, other lengths), so they are off until listened to.

### Bench

Against the reference plans, before (the planner as first written) and after, on the reference's
own maps (236 pairs) and on our analyser's maps with catalogue genres (46 pairs):

| | Family agrees | Both ends within one bar | Exit off by | Entry off by | Length off by |
|---|---|---|---|---|---|
| Reference maps, before | 80.9 % | 40.3 % | 9.3 s | 4.4 s | 4.1 s |
| Reference maps, after | 83.5 % | 43.2 % | 6.2 s | 4.8 s | 3.5 s |
| Our maps, before | 84.8 % | 58.7 % | 7.1 s | 2.3 s | 4.2 s |
| Our maps, after | 82.6 % | 60.9 % | 5.4 s | 2.9 s | 4.3 s |

The reference draws its style and its place at random among the candidates it keeps, seeded by the
pair, so no planner can agree with it pair by pair on those: the family, the exit and the rule of
the entry are what can be matched. Among locked pairs of the same length class the exit is within
one bar for 45 % of the short ones (20 % before) and 62 % of the medium ones (12 % before). The
entry of a short style differs by exactly one bar between the two short styles, which the bench
counts as a miss when the bar is a few milliseconds long. On our maps one pair fewer falls in the
right family: two pairs of a hip-hop song after a pop or dance one, locked by the reference, that
the family's usual danceability refuses.

## Voices from lyrics

The spectral estimate in `barVocalRisk` mostly sees that a bar could hold a voice: on the bench corpus, checked against the songs' timed lyrics, it finds about a third of the sung bars at the threshold the policy uses, and lowering the threshold only approaches "every bar". When a song's timed lyrics are already on the device, `withSung` replaces it for that decision and nothing is fetched for it. On the corpus, with both songs' lyrics known, locked mixes where two voices meet for over 2 s fall from 53 % to under 1 %, windows get shorter by themselves, and the share of pairs reaching a locked level goes from 25 % to 19 %: these are the mixes that could not avoid a clash. The numbers are not an independent test, since the lyrics that judge are also the ones used, and a song without lyrics gets the estimate as before. The journal says which was used.

A song without lyrics gets `VocalRisk.learned` instead of the raw estimate: a small logistic model over the bar measurements, fitted on bars labelled from timed lyrics and checked on songs left out, where it ranks bars at 0.86 against 0.71. Judged on the corpus's lyrics with no lyrics given to the policy, locked mixes where voices meet for over 2 s fall from 53 % to 17 %, and fewer pairs lock. The weights come from the same corpus, mostly rap and pop, so a very different catalogue may do worse; the worst this does is a shorter, plainer transition.

## Small speed changes

Keeping the pitch while changing speed costs artefacts on music. A plan within 1 % goes by resampling alone (`mixParameters`), which moves the pitch by under 17 cents until it returns to the song's own speed.
