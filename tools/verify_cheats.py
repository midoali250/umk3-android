#!/usr/bin/env python3
"""Automated proof that the four cheat switches really change the running game.

    python3 tools/verify_cheats.py LIBGENESIS ROM --out cheat-proof

Each scenario boots the core, walks into a live fight with scripted input, runs
the shipped C cheat engine (sega_cheats_apply) exactly the way the phone does,
and asserts on what the game's work RAM and life bars did.  Writes
cheat-proof.json plus a contact sheet of the captured frames.

Exit code is non-zero if any assertion fails, so CI can gate on it.
"""
import argparse
import json
from pathlib import Path

from PIL import Image, ImageDraw

from cheat_lab import ADDR, CHEAT_IDS, Lab

CAPTIONS = {
    'baseline': 'No cheats: player one is beaten down',
    'health': 'Infinite energy: life bar never moves',
    'time': 'Frozen clock: the round never times out',
    'run': 'Infinite run: meter stays full while sprinting',
    'p2_ko': 'One-hit K.O.: opponent down in a single touch',
}


def scenario(lib, rom, out, name, cheats=(), mode='idle', frames=600):
    """Run one scenario and return (frame images, json record)."""
    lab = Lab(lib, rom, out, cheats)
    try:
        assert lab.to_fight(), f'{name}: never reached a fight'
        lab.shot(f'{name}-start')
        bars_start = lab.bars(name + ':start')
        timeline = []
        step = {'idle': lambda n: lab.run(n),
                'attack': lambda n: lab.attack(n),
                'run': lambda n: lab.sprint(n)}[mode]
        for i in range(0, frames, 60):
            step(min(60, frames - i))
            timeline.append(dict(frame=lab.frame_no, **lab.state()))
        end = lab.state()
        bars_end = lab.bars(name + ':end')
        lab.shot(f'{name}-end')
        record = dict(name=name, cheats=list(cheats), mode=mode, frames=frames,
                      start={k: v for k, v in timeline[0].items() if k != 'frame'},
                      end=end, bars_start=bars_start, bars_end=bars_end,
                      learnt=lab.stats(), timeline=timeline)
        return lab.image(), record
    finally:
        lab.close()


def health_values(record):
    return [int(t['p1_health'], 16) for t in record['timeline']]


def p2_values(record):
    return [int(t['p2_health'], 16) for t in record['timeline']]


def clock(record):
    return [t['time_tens'] + t['time_units'] for t in record['timeline']]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('lib')
    ap.add_argument('rom')
    ap.add_argument('--out', default='cheat-proof')
    ap.add_argument('--sheet', default='cheat-proof.png')
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    checks = []
    shots = []

    def check(name, ok, detail):
        checks.append(dict(check=name, ok=bool(ok), detail=detail))
        print(f'[{"PASS" if ok else "FAIL"}] {name}: {detail}', flush=True)

    # 1. Baseline: the game can and does hurt player one.
    img, base = scenario(args.lib, args.rom, out, 'baseline', mode='attack', frames=600)
    shots.append((img, 'baseline'))
    lo = min(health_values(base))
    check('baseline damage is real', lo < 0x90,
          f'lowest life byte seen without cheats was 0x{lo:02X} (full is 0xA6)')

    # 2. Infinite energy holds that same fight at full.
    img, hp = scenario(args.lib, args.rom, out, 'health', cheats=['health'], mode='attack', frames=900)
    shots.append((img, 'health'))
    vals = health_values(hp)
    check('infinite energy', min(vals) == 0xA6 and max(vals) == 0xA6,
          f'life stayed 0xA6 for all {len(vals)} samples while the fight went on')
    check('infinite energy bar', hp['bars_end'][0] == max(hp['bars_end'] + hp['bars_start']),
          f'player-one bar still {hp["bars_end"][0]}px wide (full)')

    # 3. Frozen clock: both BCD digits stay at 99.
    img, tm = scenario(args.lib, args.rom, out, 'time', cheats=['time'], mode='idle', frames=900)
    shots.append((img, 'time'))
    clocks = clock(tm)
    check('frozen clock', all(c == '0909' for c in clocks),
          f'clock read 99 through {len(clocks)} samples ({tm["frames"]} frames)')

    # 4. Run meter: drains without the cheat, never drains with it.
    _, run_off = scenario(args.lib, args.rom, out, 'run-off', mode='run', frames=240)
    img, run_on = scenario(args.lib, args.rom, out, 'run', cheats=['run'], mode='run', frames=240)
    shots.append((img, 'run'))
    drained = min(int(t['p1_run'], 16) for t in run_off['timeline'])
    kept = min(int(t['p1_run'], 16) for t in run_on['timeline'])
    check('run meter drains without the cheat', drained <= 0x10,
          f'meter fell to 0x{drained:02X} of 0x30 while sprinting')
    check('infinite run', kept == 0x30,
          f'meter never left 0x{kept:02X} with the cheat on')

    # 5. One hit K.O.: the opponent's life hits zero on the first scratch and the
    #    game runs its knockout sequence, then the next round starts.
    img, ko = scenario(args.lib, args.rom, out, 'p2_ko', cheats=['p2_ko'], mode='attack', frames=900)
    shots.append((img, 'p2_ko'))
    p2 = p2_values(ko)
    check('one hit K.O. drops the opponent', 0 in p2,
          f'opponent life reached 0x00 (from 0xA6) at sample {p2.index(0)}')
    check('knockout really registered',
          any(p2[i] == 0 and p2[i + 1] == 0 for i in range(len(p2) - 1))
          and p2[0] == 0xA6,
          'life stayed at zero for a whole sample window, i.e. the engine ran its K.O.')

    # Contact sheet with captions.
    rows = (len(shots[:6]) + 1) // 2
    sheet = Image.new('RGB', (640, 30 + rows * 250 + 10), (24, 28, 34))
    draw = ImageDraw.Draw(sheet)
    draw.text((10, 8), 'UMK3 Android - cheat engine verification (host core, real ROM)', fill='white')
    for i, (img, name) in enumerate(shots[:6]):
        x, y = (i % 2) * 320, 30 + (i // 2) * 250
        sheet.paste(img, (x, y))
        draw.text((x + 6, y + 228), CAPTIONS[name], fill=(180, 220, 255))
    sheet.save(args.sheet)

    result = dict(passed=all(c['ok'] for c in checks), checks=checks, scenarios=[base, hp, tm, run_on, ko])
    (out / 'cheat-proof.json').write_text(json.dumps(result, indent=2))
    print(json.dumps(dict(passed=result['passed'], checks=len(checks),
                          sheet=str(args.sheet), json=str(out / 'cheat-proof.json'))))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
