#!/usr/bin/env python3
"""Drive the Genesis host build into a real fight and poke at live work RAM.

This is the research / reference tool behind the cheat switches: it loads the
same libretro core the APK ships, calls the same C cheat engine
(core/src/main/cpp/cheat_engine.c, exported as sega_cheats_*) and reports what
the game's memory actually did.

    python3 tools/cheat_lab.py LIBGENESIS ROM --out DIR --frames 900 \
            [--cheats health,run,time,p2_ko] [--mode idle|attack|run]

No copyrighted ROM is stored by this script; pass a local copy.
"""
import argparse
import ctypes as C
import json
from pathlib import Path

from PIL import Image

# --------------------------------------------------------------------------
# Mega Drive work RAM is mapped at 0xFF0000; the numbers below are offsets.
# Verified against the retail ROM (see docs/CHEATS.md).
MATCH_FLAG = 0xEF6A     # 0 in every menu, 1 for a whole match
ADDR = {
    'p1_health': 0xF228,   # 0xA6 == full bar, 0x00 == K.O.
    'p2_health': 0xF39A,
    'p1_run':    0xF22A,   # 0x30 == full run meter
    'p2_run':    0xF39C,
    'time_tens': 0xEF66,   # BCD tens digit of the round clock
    'time_units': 0xEF68,
}
CHEAT_IDS = {'health': 0, 'run': 1, 'time': 2, 'p2_ko': 3}
START_BIT, A_BIT, RIGHT_BIT, Y_BIT = 3, 1, 7, 9


class Info(C.Structure):
    _fields_ = [('library_name', C.c_char_p), ('library_version', C.c_char_p),
                ('valid_extensions', C.c_char_p), ('need_fullpath', C.c_bool),
                ('block_extract', C.c_bool)]


class Game(C.Structure):
    _fields_ = [('path', C.c_char_p), ('data', C.c_void_p), ('size', C.c_size_t),
                ('meta', C.c_char_p)]


ENV = C.CFUNCTYPE(C.c_bool, C.c_uint, C.c_void_p)
VIDEO = C.CFUNCTYPE(None, C.c_void_p, C.c_uint, C.c_uint, C.c_size_t)
AUDIO = C.CFUNCTYPE(C.c_size_t, C.POINTER(C.c_int16), C.c_size_t)
POLL = C.CFUNCTYPE(None)
INPUT = C.CFUNCTYPE(C.c_int16, C.c_uint, C.c_uint, C.c_uint, C.c_uint)


class Lab:
    def __init__(self, lib_path, rom_path, outdir='cheat-lab', cheats=(), log=None):
        self.out = Path(outdir)
        self.out.mkdir(parents=True, exist_ok=True)
        self.log = log or (lambda kind, **kw: None)
        self.mask = 0
        self.frame_no = 0
        self.last = None
        self.enabled = tuple(cheats)
        for name in self.enabled:
            assert name in CHEAT_IDS, f'unknown cheat {name}'
        self.lib = C.CDLL(lib_path)
        self._bind()
        self._boot(rom_path)
        self.ram = (C.c_ubyte * self.ram_size).from_address(self.ram_ptr)
        self.lib.sega_cheats_reset()
        for name in self.enabled:
            self.lib.sega_cheats_set(CHEAT_IDS[name], 1)
        assert self.lib.sega_cheats_mask() == self.mask_bitmap()

    def mask_bitmap(self):
        return sum(1 << CHEAT_IDS[n] for n in self.enabled)

    # -- plumbing ----------------------------------------------------------
    def _bind(self):
        lib = self.lib
        lib.sega_cheats_mask.restype = C.c_int
        lib.sega_cheats_stats.restype = C.c_int
        lib.sega_cheats_stats.argtypes = [C.c_int]
        lib.sega_cheats_apply.restype = C.c_int
        lib.sega_cheats_apply.argtypes = [C.c_void_p, C.c_size_t]
        lib.sega_cheats_set.argtypes = [C.c_int, C.c_int]
        lib.sega_cheats_hook_sync.restype = None
        lib.sega_cheats_hook_active.restype = C.c_int

    def _boot(self, rom_path):
        lib = self.lib
        self._callbacks = [ENV(self._env), VIDEO(self._video), AUDIO(lambda d, n: n),
                           POLL(lambda: None), INPUT(self._input)]
        for name, cb in zip(['retro_set_environment', 'retro_set_video_refresh',
                             'retro_set_audio_sample_batch', 'retro_set_input_poll',
                             'retro_set_input_state'], self._callbacks):
            getattr(lib, name)(cb)
        lib.retro_get_system_info.argtypes = [C.POINTER(Info)]
        self.info = Info()
        lib.retro_get_system_info(C.byref(self.info))
        lib.retro_init()
        lib.retro_load_game.argtypes = [C.POINTER(Game)]
        assert lib.retro_load_game(C.byref(Game(str(rom_path).encode(), None, 0, None))), \
            'ROM load failed'
        lib.retro_set_controller_port_device(0, 513)   # six-button pad
        lib.retro_get_memory_data.restype = C.c_void_p
        lib.retro_get_memory_size.restype = C.c_size_t
        self.ram_ptr = lib.retro_get_memory_data(2)    # RETRO_MEMORY_SYSTEM_RAM
        self.ram_size = lib.retro_get_memory_size(2)
        assert self.ram_size == 0x10000, self.ram_size

    def _env(self, cmd, data):
        if cmd == 10:                                  # PIXEL_FORMAT -> RGB565
            C.cast(data, C.POINTER(C.c_int)).contents.value = 2
            return True
        return cmd in (14, 16, 21, 22, 35, 36, 65587)

    def _video(self, data, w, h, pitch):
        if data:
            self.last = (w, h, pitch, C.string_at(data, pitch * h))

    def _input(self, port, device, index, ident):
        if ident == 256:
            return self.mask & 0xFFFF
        return int(bool(self.mask & (1 << ident)))

    # -- driving -----------------------------------------------------------
    def apply_cheats(self):
        """Exactly what nativeRun() does between frames."""
        self.lib.sega_cheats_hook_sync()
        return self.lib.sega_cheats_apply(self.ram_ptr, self.ram_size)

    def hook_active(self):
        return bool(self.lib.sega_cheats_hook_active())

    def run(self, n, apply=True):
        for _ in range(n):
            if apply:
                self.apply_cheats()
            self.lib.retro_run()
            self.frame_no += 1
            if apply:
                self.apply_cheats()

    def press(self, bit, frames=5, gap=60):
        self.mask = 1 << bit
        self.run(frames)
        self.mask = 0
        self.run(gap)

    def stats(self):
        return {name: self.lib.sega_cheats_stats(i) for name, i in CHEAT_IDS.items()}

    def peek(self, key):
        return self.ram[ADDR[key]]

    def state(self):
        return {k: f'{self.ram[o]:02X}' for k, o in ADDR.items()}

    def in_match(self):
        return bool(self.ram[MATCH_FLAG])

    # -- the route to a live fight -----------------------------------------
    def to_fight(self, limit=6000):
        self.run(1210)                   # Williams / Midway logos and title
        self.press(START_BIT, 5, 300)    # title -> main menu
        self.press(START_BIT, 5, 300)    # main menu -> play mode select
        self.press(A_BIT, 5, 300)        # mode select -> fighter select
        self.press(START_BIT, 5, 420)    # pick the highlighted fighter
        self.press(A_BIT, 5, 300)
        self.press(A_BIT, 5, 300)
        waited = 0
        while waited < limit and self.peek('p2_health') == 0:
            self.run(15)
            waited += 15
        self.run(120)                    # let the announcer clear
        if not self.in_match():
            return False
        self.log('fight', frame=self.frame_no, waited=waited, state=self.state())
        return True

    # -- input scripts used by the scenarios --------------------------------
    def attack(self, frames):
        """Walk into the opponent and kick them."""
        spent = 0
        while spent < frames:
            step = min(20, frames - spent)
            self.mask = 1 << RIGHT_BIT
            self.run(step)
            spent += step
            self.mask = 0
            for bit in (11, 11, 10, 8):          # HK, HK, HP, LK
                if spent >= frames:
                    break
                self.press(bit, 5, 14)
                spent += 19

    def sprint(self, frames):
        """Tap forward twice, then hold forward + RUN so the run meter drains."""
        spent = 0
        while spent < frames:
            self.press(RIGHT_BIT, 3, 3)
            self.press(RIGHT_BIT, 3, 3)
            spent += 20
            self.mask = (1 << RIGHT_BIT) | (1 << Y_BIT)
            step = min(90, frames - spent)
            self.run(step)
            spent += step
            self.mask = 0
            self.run(12)
            spent += 12

    # -- evidence ----------------------------------------------------------
    def rgb(self):
        w, h, pitch, raw = self.last
        mv = memoryview(raw)
        out = bytearray(w * h * 3)
        for y in range(h):
            row = y * pitch
            for x in range(w):
                o = row + x * 2
                p = mv[o] | (mv[o + 1] << 8)
                i = (y * w + x) * 3
                out[i] = ((p >> 11) & 31) * 255 // 31
                out[i + 1] = ((p >> 5) & 63) * 255 // 63
                out[i + 2] = (p & 31) * 255 // 31
        return Image.frombytes('RGB', (w, h), bytes(out))

    def image(self):
        return self.rgb()

    def shot(self, name):
        path = self.out / f'{name}.png'
        self.rgb().save(path)
        self.log('shot', name=name, frame=self.frame_no, file=str(path))
        return str(path)

    def bars(self, name=''):
        """Filled width in pixels of both life bars on the current frame."""
        img = self.rgb()
        px = img.load()
        w, h = img.size
        widths = []
        for x0, x1 in ((16, 154), (166, 304)):
            best = 0
            for y in range(20, min(34, h)):
                run = cur = 0
                for x in range(x0, min(x1, w)):
                    r, g, b = px[x, y]
                    lit = (b > 120 and b > r + 60 and b > g + 40) or \
                          (g > 130 and g > r + 60 and g > b + 20)
                    cur = cur + 1 if lit else 0
                    best = max(best, cur)
            widths.append(best)
        self.log('bars', name=name, frame=self.frame_no, p1=widths[0], p2=widths[1])
        return widths

    def close(self):
        self.lib.retro_unload_game()
        self.lib.retro_deinit()


MODES = {'idle': lambda lab, n: lab.run(n),
         'attack': lambda lab, n: lab.attack(n),
         'run': lambda lab, n: lab.sprint(n)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('lib')
    ap.add_argument('rom')
    ap.add_argument('--out', default='cheat-lab')
    ap.add_argument('--frames', type=int, default=900)
    ap.add_argument('--cheats', default='')
    ap.add_argument('--mode', default='idle', choices=sorted(MODES))
    ap.add_argument('--json-out')
    args = ap.parse_args()

    records = []

    def log(kind, **kw):
        kw['kind'] = kind
        records.append(kw)
        print(json.dumps(kw), flush=True)

    cheats = [c for c in args.cheats.split(',') if c]
    lab = Lab(args.lib, args.rom, args.out, cheats, log)
    try:
        assert lab.to_fight(), 'never reached a fight'
        lab.shot('fight-start')
        lab.bars('start')
        stepper = MODES[args.mode]
        timeline = []
        for i in range(0, args.frames, 60):
            stepper(lab, min(60, args.frames - i))
            timeline.append(dict(frame=lab.frame_no, **lab.state()))
        after = lab.state()
        lab.bars('end')
        lab.shot('fight-end')
        result = dict(cheats=cheats, mode=args.mode, frames=args.frames,
                      state_start=timeline[0], state_end=after,
                      learnt=lab.stats(), timeline=timeline)
        log('result', **result)
        if args.json_out:
            Path(args.json_out).write_text(json.dumps(result, indent=2))
    finally:
        lab.close()


if __name__ == '__main__':
    main()
