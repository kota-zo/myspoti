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
  (250–750 ms). BitChord turned its live phase correction off for the same reason
  ("cracked on some phones"). With `setEnableAudioTrackPlaybackParams(true)` the speed is the
  AudioTrack's own: applied at once, without a drain.
- An AudioProcessor sees no timestamps and acts a whole buffer before the sound is heard; a
  player's volume (`AudioTrack.setVolume`) and an audio effect on its session act on what is heard now.

## How it works now

**Analysis** (`base/automix`, pure Kotlin, the same code on the phone and on the desktop bench).
Each song is decoded once through the player's own data source (downloads decrypted, extension
streams resolved; streams only on unmetered networks, no DASH answers), to mid and side at 22.05 kHz.
- Onsets: spectral flux on 40 log bands, plus the bands under 150 Hz (kicks, 808s).
- Beat This! small0 (MIT, int8 ONNX, ONNX Runtime) on the whole song gives the metrical level
  (pure onset autocorrelation hesitates between the beat and ¾ or 4⁄3 of it on trap hi-hats) and
  the downbeats.
- A constant grid is fitted on the onsets with the model's period (coarse search, then weighted
  least squares on the onset peaks), moved onto the model's beats (same instant for every song),
  and checked: the share of beats on the grid, the drift over 8 beats, and the agreement with the
  model. A song made on a fixed tempo gets one grid; otherwise the start and the end get grids of
  their own where they hold.
- Bars (downbeats voted by the model near the start and near the end), per-bar level, low and
  high band, voice-band centre (mid over side), novelty (4 bars against the 4 before) and the
  8-bar phrase offset; key (Krumhansl–Kessler on the chroma), loudness, where the sound starts and ends.
- The result is cached (`cache/automix4/`), about 10 KB a song.
- Speed: on the S24, 5 to 11 s a song (decoding 3–6 s, analysis 3–5 s). The decoder never waits on
  the codec while it has work; the onset features (in slices) and Beat This (its 15-s chunks side by
  side, one ONNX thread each) run at once on all cores but two. The song after the next one is
  analysed too, so a skip finds both of its songs ready. Beat This final0 (10× the parameters) would
  take 40–80 s a song for better metrical decisions on a few hard songs: not used.

**Planning** (`MixPlanner`): exit cues on A's downbeats in its last minute and a half (phrase starts,
a level drop after them, late; never past where its music really ends, before a long fade-out),
entry cues on B's first downbeat or a phrase start in its intro (a skip costs, less over a quiet
intro); 16, 8, 4 or 2 bars, scored for: B's next section starting when A is gone, A's loud bars
left out, two voices at once, two loud songs at once, key distance on the Camelot wheel, size of
the tempo change. Styles:
- **BLEND** when both grids hold and the tempo change is at most 8 % (half and double time count):
  B comes in under A with its bass cut and its top end a little down, the bass moves from A to B
  over one beat at a downbeat in the middle, A fades out (equal power) over the second half with
  its top end going down. B louder than A is brought to A's level, then back to its own over 8 s.
- **CUT** when A has a grid but the tempos are too far apart: B starts whole on A's downbeat (on
  its own first downbeat or a later phrase), A's bass goes at once and A is out within two beats.
- **FADE** without a grid: 6 s equal power, A's bass out halfway.

**Playback** (`LocalPlayer`, "AutoMix" part): two decks, no song ever handed from one player to the other.
- 15 s before the mix the next song is prepared on a second player at its entry point less a
  2.5 s run-up, its speed set (AudioTrack playback parameters) so its beats fall on A's, volume 0.
- 2.5 s before the mix it starts, muted, and a phase-locked loop compares both positions every
  20 ms and nudges its speed (up to 8 % while muted, 2 % once heard; a seek if it is 400 ms off).
- At the exit point the deck becomes the main player: the queue around its song, the listener,
  the audio focus. The old main player plays the mix out as the outgoing one, then stops.
- Gains are the two players' volumes; the bass swap and the top-end cuts are a three-band
  DynamicsProcessing EQ on each player's audio session (low under 180 Hz, high over 2.5 kHz).
  The phone refusing the effect leaves the gains alone.
- After the mix the deck's speed goes back to the song's own, about 0.3 % a beat.
- Not analysed in time: a 6 s fade on the songs' lengths, still on two decks.
- **Album order kept**: at the end of a song, no mix into the next song of the same album (the
  phone's files: the next track number, or the first track of the next disc; streams and downloads,
  which carry no track number: the album played as the list, the next song in its own order). The
  two songs run into each other as on the album. Two songs of one album that do not follow each
  other there are mixed as any others.
- **Next mixes too**: Next prepares the next song on the deck and mixes it in from A's next
  downbeat: two bars beat-matched (one when two last over 5.5 s), else a cut on the beat, else a
  2.5 s fade; this holds for songs of one album as well. Not ready within 2.5 s: the plain skip. A
  second Next skips at once.
- Seek, previous, a queue change or AutoMix off: the deck is dropped (before the mix) or the song
  coming in plays on at its own speed (during the mix).

## Checking it

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
Ellis, "Beat tracking by dynamic programming" (2007), Krumhansl and Kessler key profiles, BitChord's
two-player automix (GPL-3.0; its design only), kckDeepak/AI-DJ-Mixing-System (MIT; its rules).
