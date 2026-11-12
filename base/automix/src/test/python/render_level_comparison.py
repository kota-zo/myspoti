#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Describe level motion of before/after SegueBench WAVs; this is not a perceptual metric."""
import argparse
import json
from pathlib import Path
import wave
import numpy as np


def matrix(path):
    rows = {}
    for line in path.read_text().splitlines():
        fields = line.split('\t')
        if len(fields) >= 10 and ',' in fields[0]:
            rows[fields[0]] = dict(seconds=float(fields[2]), exit=float(fields[3]), entry=float(fields[4]), shape=fields[8])
    if not rows:
        raise ValueError('No SegueBench matrix rows')
    return rows


def read(path):
    with wave.open(str(path)) as wav:
        if wav.getsampwidth() != 2:
            raise ValueError('Expected SegueBench PCM16 WAV')
        pcm = np.frombuffer(wav.readframes(wav.getnframes()), dtype='<i2').astype(float)
        return pcm.reshape(-1, wav.getnchannels()) / 32768, wav.getframerate()


def db(pcm):
    return float(10 * np.log10(np.mean(pcm * pcm) + 1e-12))


def metrics(path, seconds, preroll):
    pcm, rate = read(path)
    end = int((preroll + seconds) * rate)
    start = int(preroll * rate)
    block = int(.2 * rate)
    if start < block or end + block > len(pcm):
        raise ValueError('Render lacks required pre/post context')
    levels = [db(pcm[k:k + block]) for k in range(start - block, end + block, block)
              if len(pcm[k:k + block]) == block]
    return dict(peak=float(np.abs(pcm).max()), clipped=int((np.abs(pcm) >= .999969).sum()),
        end_jump_200ms_db=abs(db(pcm[end - block:end]) - db(pcm[end:end + block])),
        max_adjacent_200ms_db=max(abs(b - a) for a, b in zip(levels, levels[1:])),
        whole_window_jump_400ms_db=abs(db(pcm[start-int(.4*rate):start]) - db(pcm[end:end+int(.4*rate)])))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('before', 'after', 'before-matrix', 'after-matrix', 'output'):
        parser.add_argument('--' + name, required=True, type=Path)
    parser.add_argument('--preroll', type=float, default=12.0)
    args = parser.parse_args()
    before = matrix(args.before_matrix); after = matrix(args.after_matrix)
    result = []
    for pair in sorted(before.keys() & after.keys()):
        outgoing, incoming = pair.split(',')
        name = f'mix-{outgoing}-{incoming}.wav'
        result.append(dict(pair=pair, before_plan=before[pair], after_plan=after[pair],
            before=metrics(args.before / name, before[pair]['seconds'], args.preroll),
            after=metrics(args.after / name, after[pair]['seconds'], args.preroll)))
    report = dict(method='RMS of identical-source SegueBench renders; 200ms adjacent/edge windows; 400ms whole-window comparison.',
        limitations='Different plans can cover different musical content and duration. Percussion can dominate adjacent RMS steps. Full-window level change is not a discontinuity. No human listening or Android timing claim.', pairs=result)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
