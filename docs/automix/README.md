# AutoMix

DJ-style transitions between the songs of the local player: extension streams, downloads and the
phone's files. Spotify songs play in the Web Player, whose audio the app never has: no AutoMix there.
Settings → Crossfade → AutoMix (or the AutoMix switch under Crossfade in the classic settings).

This is the fourth AutoMix and a rewrite: nothing is kept from the earlier ones' playback.

## Why the earlier AutoMix broke the sound

The earlier one rendered each transition into a WAV file and spliced it into the queue between the
two songs (the outgoing song clipped while it played, the WAV, then the incoming song from an
offset). Each seam sat on a decoder packet, not a sample, so the audio could jump by up to a packet
(23 ms for AAC, 93 ms for a 4096-sample FLAC block) at both ends of every transition, and a sample
rate change between songs reopened the audio output. It also measured only short windows of each
song, with strict checks, so most pairs fell back to a plain fade.

Two Media3 facts shaped the new design (read in Media3 1.8.0, the version the app uses):
- A speed change through the sink's Sonic drains the processors and starts a new Sonic: the
  join is a hard switch, and the new speed is heard only after the AudioTrack buffer
  (250–750 ms). With `setEnableAudioTrackPlaybackParams(true)` the speed is the
  AudioTrack's own: applied at once, without a drain.
- An AudioProcessor sees no timestamps and acts a whole buffer before the sound is heard; a
  player's volume (`AudioTrack.setVolume`) and an audio effect on its session act on what is heard now.

## How it works now

**Analysis** (`base/automix`, pure Kotlin, the same code on the phone and on the desktop bench).
Each song is decoded once through the player's own data source (downloads decrypted, extension
streams resolved, DASH answers read as one file; streams only on unmetered networks unless "Analyse on
mobile data" is on, never under the data saver), to mid and side at 22.05 kHz.
- Onsets: spectral flux on 40 log bands, plus the bands under 150 Hz (kicks, 808s).
- Beat This! small0 (MIT, int8 ONNX, ONNX Runtime) on the whole song gives the metrical level
  (pure onset autocorrelation hesitates between the beat and ¾ or 4⁄3 of it on trap hi-hats) and
  the downbeats.
- A constant grid is fitted on the onsets with the model's period (coarse search, then weighted
  least squares on the onset peaks), moved onto the model's beats (same instant for every song),
  and checked: the share of beats on the grid, the drift over 8 beats, and the agreement with the
  model. A song made on a fixed tempo gets one grid; otherwise the start and the end get grids of
  their own where they hold: over 60–90 s, else 45, else 25 s from the edge (a mix needs no more),
  fitted on the onsets first, and only then on the model's beats alone (its extra off-beat marks
  left out; it can be wrong where the onsets are right). When none holds, the journal says why.
- Bars (downbeats voted by the model near the start and near the end), per-bar level, low and
  high band, voice-band centre (mid over side), novelty (4 bars against the 4 before), local
  structural boundaries and an 8-bar phrase fallback; key (Krumhansl–Kessler on the chroma),
  loudness, where the sound starts and ends. Voice-like activity uses existing spectral features,
  including absolute centre and mono uncertainty: it is a heuristic, not a vocal separation model.
- The result is cached (`cache/automix4/`, format 4_09), about 10 KB a song. Source metadata/path
  and local file size/modification time identify the recording; expiring access tokens do not.
- Speed: on the S24, 5 to 11 s a song (decoding 3–6 s, analysis 3–5 s). The decoder never waits on
  the codec while it has work; the onset features (in slices) and Beat This (its 15-s chunks side by
  side, one ONNX thread each) run at once on all cores but two. The song after the next one is
  analysed too, so a skip finds both of its songs ready. Beat This final0 (10× the parameters) would
  take 40–80 s a song for better metrical decisions on a few hard songs: not used.

**Settings** (Transitions → AutoMix, both looks; `MixPlanner.Settings` from `AutoMix.settings()`):
- *Transition style*: Beat blend (the styles below, chosen per pair), Cut on the beat (always CUT when
  A has a grid, else FADE; a skip cuts too), Smooth fade (always FADE, tempos untouched).
- *DJ session* (on in the test branch): permits structural exits earlier in the second half,
  independent of overlap length. Off, late strong bars are preserved more strictly. Album order
  remains a separate setting and still takes precedence.
- *Mix length*: Short 4–8, Balanced preferably 8–16 (48 s cap), Long preferably 12–32 (90 s cap).
  A preferred-length blend must also satisfy vocal, tempo and harmonic checks; otherwise a shorter
  safe transition is allowed. A smooth fade lasts 4, 8 or 14 s.
- *Harmonic mixing* (on by default): the key distance weighs ×2 in the blend's score (×1 before this
  setting existed); off, ×0 (keys ignored). An unknown key carries no evidence of a clash.
- *Mix podcasts* (off by default): a podcast episode or an audiobook at either end (`LocalTrack.speech`:
  downloaded episodes, MediaStore's IS_PODCAST/IS_AUDIOBOOK) is neither analysed nor mixed, and Next cuts.
- *Analyse on mobile data* (on by default): each extension stream is read a second time, about the
  song's size again.
- Saving energy (`AutoMix.frugal`: the app's energy saver, its data saver applying, Android's battery
  saver, or under 20 % unplugged): the song playing and the next one only, two threads.
- Spotify songs, with AutoMix on, change with a 3-s fade (`TransitionPref.SPOTIFY_FADE_SECONDS`),
  not with the crossfade length kept for later.
- `AutoMix.whyNot()` says why the next song is not mixed (Off, Cast, SpotifyStream, Podcast, Album,
  MobileData, Frugal, NotAnalyzed), for the screens. Casting: no deck (any is dropped when the cast starts).

**Planning** (`MixPlanner`): exit cues on A's checked downbeats in the second half, up to three
minutes before the end in DJ session mode (local boundaries, phrase starts and level drops),
entry cues on B's first downbeat or a phrase start in its intro (a skip costs, less over a quiet
intro, or the point that puts B's strong beat right at the end of the mix); 32, 24, 16, 12, 8, 6, 4 or 2
bars (within the length setting), the whole mix inside the span where both grids were checked, scored first for B's strong beat
(the drop, the first verse: where its level and bass jump) landing when A is gone, then for: A's loud bars
left out, aligned voice-like activity, key distance on the Camelot wheel and tempo change.
Per-song level prefixes, remaining loud bars and drop scores are prepared once, with B's candidate
scores shared across A's exits. `planAfter` excludes missed cues after a seek or late preparation.
Styles:
- **BLEND** when both grids hold and the tempo change is at most 8 % (half and double time count):
  B comes in under A with its bass cut and its top end a little down, the bass moves from A to B
  over one beat at a shared structural downbeat between 25–75% of the mix, or its middle. A fades out with
  its top end going down. B louder than A is brought to A's level, then back to its own over 8 s.
  Summed squared fader gains are bounded by one, including when DJ EQ is unavailable. This bounds
  the energy envelope; it does not guarantee that correlated sample peaks cannot clip.
- **CUT** when A has a grid but the tempos are too far apart: B starts whole on A's downbeat (on
  its own first downbeat or a later phrase), A's bass goes at once and A is out within two beats.
- **FADE** without a grid: 6 s equal power, A's bass out halfway, from a bar of A (its bars come from
  the model's beats), B on its first downbeat when it has one.
  Two soft passages with little percussion and low vocal risk may get a longer unsynced fade
  across distant tempos; rhythmic passages retain a cut rather than false beat matching.

**Playback** (`LocalPlayer`, "AutoMix" part): two decks, no song ever handed from one player to the other.
- 15 s before the mix the next song is prepared on a second player at its entry point less a
  2.5 s run-up, its speed set (AudioTrack playback parameters) so its beats fall on A's, volume 0.
- 2.5 s before the mix it starts, muted, and a phase-locked loop compares both positions every
  20 ms and nudges its speed (up to 8 % while muted, 2 % once heard; a seek if it is 400 ms off).
- At the exit point, once B is playing, sufficiently buffered and aligned, it becomes the main player: the queue around its song, the listener,
  the audio focus. The old main player plays the mix out as the outgoing one, then stops.
- Gains are the two players' volumes; the bass swap and the top-end cuts are a three-band
  DynamicsProcessing EQ on each player's audio session (low under 180 Hz, high over 2.5 kHz).
  The phone refusing the effect leaves the gains alone.
- After the mix the deck's speed goes back to the song's own, about 0.3 % a beat.
- Not analysed in time: a provisional 6 s fade, still improvable while its deck is prepared but
  silent. The plan becomes fixed at the muted run-up. A late deck can retry at a future cue, then
  fall back to a safe fade, with at most three preparation attempts per pair.
- Analysis cache hits are loaded before expensive calculations, for at most three tracks (two
  while saving energy). Temporary failures back off; cancellations do not blacklist a song.
  Turning AutoMix or local playback off cancels obsolete analysis. Worker budgets include nested
  feature tasks; no new neural model is added.
- If B buffers during a mix, A stays audible while it can. Once B recovers the players re-enter
  gently; they do not keep chasing an obsolete beat grid through the stall.
- **Album order kept**: at the end of a song, no mix into the next song of the same album (the
  phone's files: the next track number, or the first track of the next disc; streams and downloads,
  which carry no track number: the album played as the list, the next song in its own order). The
  two songs run into each other as on the album. Two songs of one album that do not follow each
  other there are mixed as any others.
- **Next mixes too**: Next prepares the next song on the deck and mixes it in from A's next
  downbeat: two bars beat-matched (one when two last over 5.5 s), else a cut on the beat, else a
  2.5 s fade; this holds for songs of one album as well. Not ready within 2.5 s: the plain skip. A
  second Next skips at once.
- At the hand-over the old player keeps its own song alone: at its end it stops (with the queue it
  went on to the next song by itself, the one the deck plays, from 0:00).
- The songs ahead are checked every 200 ms: a queue edit (added, removed, moved, "play next")
  plans the mix again for the new next song and asks for its analysis and the one after it.
- Seek, previous, a queue change or AutoMix off: the deck is dropped (before the mix) or the song
  coming in plays on at its own speed (during the mix).

## Plan-based transitions

With the beat blend style, a policy that writes each transition as data replaces the planner above,
and an engine stage plays it on the samples: see [segue-design.md](segue-design.md). It is on by
default, its numbers are unmeasured starting values, and a setting goes back to the planner.

## Known problems

- The cover now changes after a mix (the stuck cover came from the hand-over bug fixed on
  2026-11-03), but its animation during a mix is to be redone.

## Checking it

The isolated test app builds with `./gradlew --offline :app:assembleDebug -Pabis=arm64-v8a
-PautomixLab -PappVersionCode=3115` from `base/`. It is `app.myspoti.automixlab`, labelled
"Myspoti Mix Lab", and installs alongside the regular and dev apps with separate data. No audio
files or private benchmark inputs are included in the repository. Test branch status and measured
results are recorded in [LAB.md](LAB.md).

Desktop bench (real songs, no phone): see [tools/automix/README.md](../../tools/automix/README.md).
On the 12 songs it was tuned on (rap, trap, pop, an orchestral piece), against the Beat This final0
model as the reference: the tempo level matches on all 12; 10 get one grid for the whole song; the
beat-matched mixes put B's reference beats within 11–16 ms (median) of A's, about the reference's
own 20-ms resolution.

On the phone, through the command line (`docs/cli.md`): `automix on`, `automix.files <piece>…`
(phone songs as the queue), `automix.seek <ms>`, `automix.status` (state and journal: analyses,
plans, the deck's distance to its place once a second, the mix's largest gap).

## Sources

Methods only, no code copied: Mixxx (intro/outro model, sync), Zehren, Alunno and Bientinesi,
"Automatic detection of cue points for DJ mixing" (2020), Foscarin et al., "Beat This!" (2024),
Ellis, "Beat tracking by dynamic programming" (2007), Krumhansl and Kessler key profiles, kckDeepak/AI-DJ-Mixing-System (MIT; its rules).
