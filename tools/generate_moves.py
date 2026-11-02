#!/usr/bin/env python3
"""Build the UMK3 finisher / special move table used by the Android app.

The source pages are fetched once and cached in a local directory (third-party HTML is
never committed). Usage:

    mkdir -p tools/move-lists && cd tools/move-lists
    base=https://www.mortalkombatwarehouse.com
    curl -sSLo umk3_fatalities.html $base/umk3/fatalities/
    for c in ermac jade kitana mileena noobsaibot rain reptile scorpion smoke subzero; do
        curl -sSLo p_$c.html $base/umk3/$c/
    done
    for c in cyrax jax kabal kano kunglao liukang nightwolf sektor shangtsung sheeva \
             sindel sonya stryker; do
        curl -sSLo m_$c.html $base/mk3/$c/
    done
    cd - && python3 tools/generate_moves.py

That rewrites core/src/main/java/com/midoali/atari/core/MoveData.java and docs/MOVES.md.
Directions assume the fighter faces right; the app mirrors them at runtime.
"""
import json
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RESEARCH = Path(os.environ.get('UMK3_MOVE_SOURCES', ROOT / 'tools' / 'move-lists'))
OUT_JAVA = ROOT / 'core/src/main/java/com/midoali/atari/core/MoveData.java'
OUT_JSON = RESEARCH / 'moves.json'
OUT_DOC = ROOT / 'docs/MOVES.md'

# Genesis six-button pad in UMK3: A=Low Punch B=Block C=Low Kick X=High Punch Y=Run Z=High Kick
BUTTON = {'LP': 'a', 'BLK': 'b', 'LK': 'c', 'HP': 'x', 'RN': 'y', 'HK': 'z'}
DIRECTION = {'UP': 'U', 'DOWN': 'D', 'FORWARD': 'R', 'BACK': 'L'}
TOKEN = {'UP': 'U', 'DOWN': 'D', 'FORWARD': 'R', 'BACK': 'L',
         'LP': 'a', 'HP': 'x', 'LK': 'c', 'HK': 'z', 'BLK': 'b', 'BL': 'b',
         'RN': 'y', 'RUN': 'y',
         'U': 'U', 'D': 'D', 'F': 'R', 'B': 'L'}

ORDER = 'UDLRabcxyz'

CHARACTERS = [
    # (page, display name, source kind)
    ('m_cyrax', 'Cyrax', 'mk3'),
    ('p_ermac', 'Ermac', 'umk3'),
    ('p_jade', 'Jade', 'umk3'),
    ('m_jax', 'Jax', 'mk3'),
    ('m_kabal', 'Kabal', 'mk3'),
    ('m_kano', 'Kano', 'mk3'),
    ('p_kitana', 'Kitana', 'umk3'),
    ('m_kunglao', 'Kung Lao', 'mk3'),
    ('m_liukang', 'Liu Kang', 'mk3'),
    ('p_mileena', 'Mileena', 'umk3'),
    ('m_nightwolf', 'Nightwolf', 'mk3'),
    ('p_noobsaibot', 'Noob Saibot', 'umk3'),
    ('p_rain', 'Rain', 'umk3'),
    ('p_reptile', 'Reptile', 'umk3'),
    ('p_scorpion', 'Scorpion', 'umk3'),
    ('m_sektor', 'Sektor', 'mk3'),
    ('m_shangtsung', 'Shang Tsung', 'mk3'),
    ('m_sheeva', 'Sheeva', 'mk3'),
    ('m_sindel', 'Sindel', 'mk3'),
    ('p_smoke', 'Smoke', 'umk3'),
    ('m_sonya', 'Sonya', 'mk3'),
    ('m_stryker', 'Stryker', 'mk3'),
    ('p_subzero', 'Sub-Zero', 'umk3'),
]
FATALITY_ID = {'cyrax': 'm_cyrax', 'jax': 'm_jax', 'kabal': 'm_kabal', 'kano': 'm_kano',
               'kunglao': 'm_kunglao', 'liukang': 'm_liukang', 'nightwolf': 'm_nightwolf',
               'sektor': 'm_sektor', 'shangtsung': 'm_shangtsung', 'sheeva': 'm_sheeva',
               'sindel': 'm_sindel', 'sonya': 'm_sonya', 'stryker': 'm_stryker',
               'ermac': 'p_ermac', 'jade': 'p_jade', 'kitana': 'p_kitana', 'mileena': 'p_mileena',
               'noobsaibot': 'p_noobsaibot', 'rain': 'p_rain', 'reptile': 'p_reptile',
               'scorpion': 'p_scorpion', 'smoke': 'p_smoke', 'subzero': 'p_subzero'}


def clean(text):
    return re.sub(r'\s+', ' ', re.sub(r'<[^>]+>', '', text)).strip()


def parse_image_tokens(tokens):
    """Turn ['hold','blk','up','down','release','lp'] into (hold_mask, [step, ...])."""
    held, steps = [], []
    holding = False
    hold_collected = False
    join_next = False
    index = 0
    while index < len(tokens):
        tok = tokens[index].strip().lower()
        index += 1
        if tok == 'hold':
            holding = True
            hold_collected = False
            continue
        if tok == 'release':
            if index < len(tokens):
                index += 1
            holding = False
            continue
        if tok == 'plus':
            join_next = True
            continue
        if tok not in ('up', 'down', 'forward', 'back', 'lp', 'hp', 'lk', 'hk', 'blk', 'run'):
            raise ValueError('unknown image token: ' + tok)
        code = TOKEN[tok.upper()]
        is_button = code.islower()
        if holding and is_button and (not hold_collected or join_next):
            held.append(code)
            hold_collected = True
            join_next = False
            continue
        if holding and is_button and hold_collected:
            holding = False  # the button is held, further presses are separate steps
        if join_next and steps:
            steps[-1] = ''.join(sorted(set(steps[-1] + code), key=ORDER.index))
            join_next = False
            continue
        holding = False
        steps.append(code)
    return ''.join(sorted(set(held), key='abcxyz'.index)), steps


def parse_text_tokens(text):
    """Turn 'D B LP' / '[BLK] F F D U' / 'B+LP+BLK+LK' into (hold_mask, [step, ...])."""
    text = re.sub(r'\([^)]*\)', ' ', text)                      # drop notes
    text = text.replace('&amp;', '&')
    held, steps = [], []
    hold_active = False
    # bracket-held buttons: [BLK] ... [/BLK]
    for match in re.finditer(r'\[([A-Z/]+)\]', text):
        code = match.group(1).strip('/')
        if code in BUTTON:
            held.append(BUTTON[code])
        elif 'FOR' in code:
            return None  # charge move, cannot be scripted reliably
    text = re.sub(r'\[[A-Z/]+\]', ' ', text)
    words = [w for w in re.split(r'[\s,]+', text) if w]
    if not words:
        return None
    if re.search(r'seconds|in air|tap ', text, re.I):
        return None
    for word in words:
        parts = [p for p in word.split('+') if p]
        codes = []
        for part in parts:
            if part not in TOKEN:
                return None
            codes.append(TOKEN[part])
        if len(codes) == 1 and codes[0].isdecimal():
            continue
        steps.append(''.join(sorted(codes, key='UDLRabcxyz'.index)))
    if not steps:
        return None
    return ''.join(sorted(set(held), key='abcxyz'.index)), steps


def load_fatalities():
    html = (RESEARCH / 'umk3_fatalities.html').read_text(encoding='utf-8', errors='replace')
    sections = re.split(r'<h2 id="([^"]+)">', html)
    table = {}
    for i in range(1, len(sections), 2):
        ident, body = sections[i], sections[i + 1]
        name = clean(body[:body.find('</a></h2>')])
        name = re.split(r'\s{2,}', name)[-1].strip()
        moves, pos = [], 0
        while True:
            c = body.find('class="cname"', pos)
            if c < 0:
                break
            label = clean(body[body.find('>', c) + 1:body.find('</div>', c)])
            m = body.find('class="cmove12"', c)
            if m < 0:
                break
            imgs = re.findall(r'alt="([^"]+)"', body[m:body.find('</div>', m)])
            moves.append({'label': label, 'tokens': imgs})
            pos = body.find('</div>', c)
        table[ident] = {'name': name, 'moves': moves}
    return table


def load_movelist(page):
    path = RESEARCH / (page + '.html')
    if not path.is_file():
        return []
    html = path.read_text(encoding='utf-8', errors='replace')
    start = html.find('id="moves"')
    if start < 0:
        return []
    end = html.find('id="ending"', start)
    segment = html[start:end if end > 0 else start + 8000]
    return [(clean(a), clean(b)) for a, b in
            re.findall(r'<dt>(.*?)</dt>\s*<dd>(.*?)</dd>', segment, re.S)]


CATEGORY_LABELS = [
    (r'^Fatality 1', ('FATALITY 1', 'FATALITY')),
    (r'^Fatality 2', ('FATALITY 2', 'FATALITY')),
    (r'^Stage', ('STAGE FATALITY', 'FATALITY')),
    (r'^Friendship', ('FRIENDSHIP', 'EXTRA')),
    (r'^Babality', ('BABALITY', 'EXTRA')),
    (r'^Animality', ('ANIMALITY', 'EXTRA')),
    (r'^UMK3 Fatality', ('FATALITY 1', 'FATALITY')),
]


def classify(label):
    for pattern, result in CATEGORY_LABELS:
        if re.match(pattern, label, re.I):
            return result
    return None


def distance_of(label):
    m = re.search(r'\((.*?)\)', label)
    text = (m.group(1) if m else '').lower()
    for key, value in (('close', 'CLOSE'), ('sweep', 'SWEEP'), ('screen', 'FAR'),
                       ('jump', 'FAR'), ('anywhere', 'ANYWHERE')):
        if key in text:
            return value
    return 'CLOSE'


def main():
    fatalities = load_fatalities()
    result = []
    problems = []
    for page, display, kind in CHARACTERS:
        key = next((k for k, v in FATALITY_ID.items() if v == page), None)
        finishers = []
        if key and key in fatalities:
            seen = set()
            for move in fatalities[key]['moves']:
                label = move['label']
                info = classify(label)
                if not info:
                    continue
                name, category = info
                if name in seen:
                    continue
                if 'not in Arcade' in label:
                    continue
                try:
                    parsed = parse_image_tokens(move['tokens'])
                except ValueError as exc:
                    problems.append((display, label, str(exc)))
                    continue
                title = re.sub(r'^[^:]*:\s*', '', label)
                title = re.sub(r'\s*\(.*?\)\s*', '', title).strip()
                pretty = name.title().replace('Fatality 1', 'Fatality 1') if False else name.title()
                pretty = {'Fatality 1': 'Fatality 1', 'Fatality 2': 'Fatality 2',
                          'Stage Fatality': 'Stage Fatality',
                          'Friendship': 'Friendship', 'Babality': 'Babality',
                          'Animality': 'Animality'}.get(pretty, pretty)
                seen.add(name)
                finishers.append({'name': pretty, 'title': title,
                                  'category': category, 'distance': distance_of(label),
                                  'held': parsed[0], 'steps': parsed[1]})
        specials = []
        for name, text in load_movelist(page):
            if classify(name):
                continue
            if re.search(r'brutality', name, re.I):
                continue
            if name.startswith('(') and not re.search(r'air', name, re.I):
                continue
            parsed = parse_text_tokens(text)
            if parsed is None:
                problems.append((display, name, 'unparsed: ' + text))
                continue
            if not parsed[1]:
                problems.append((display, name, 'empty: ' + text))
                continue
            specials.append({'name': name, 'title': name, 'category': 'SPECIAL',
                             'distance': 'ANYWHERE', 'held': parsed[0], 'steps': parsed[1]})
        result.append({'id': page, 'name': display, 'finishers': finishers,
                       'specials': specials})

    total_moves = sum(len(c['finishers']) + len(c['specials']) for c in result)
    print(f'characters={len(result)} moves={total_moves} problems={len(problems)}')
    for p in problems:
        print('  PROBLEM', p)
    if len(problems) > 25:
        print('too many problems', file=sys.stderr)
        return 1
    OUT_JSON.write_text(json.dumps(result, indent=1))
    write_java(result)
    write_doc(result)
    return 0


def java_string(value):
    return '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'


def write_java(characters):
    lines = [
        'package com.midoali.atari.core;',
        '',
        'import java.util.ArrayList;',
        'import java.util.Collections;',
        'import java.util.List;',
        '',
        '/**',
        ' * Ultimate Mortal Kombat 3 (Genesis) move table used by the one-tap move panel.',
        ' *',
        ' * <p>Inputs were transcribed from the community move lists at',
        ' * mortalkombatwarehouse.com (UMK3 arcade, which the Genesis port mirrors) and verified',
        ' * against the Genesis six-button mapping: A = Low Punch, B = Block, C = Low Kick,',
        ' * X = High Punch, Y = Run, Z = High Kick.',
        ' *',
        ' * <p>Directions assume the fighter faces right: {@code R} = forward, {@code L} = back.',
        ' * {@link #MOVE_LIST} is generated by {@code tools/generate_moves.py}; do not hand edit.',
        ' */',
        'public final class MoveData {',
        '    public static final int CATEGORY_FATALITY = 0, CATEGORY_EXTRA = 1, CATEGORY_SPECIAL = 2;',
        '',
        '    /** One scripted input sequence: {@code held} buttons stay down for every step. */',
        '    public static final class Move {',
        '        public final String character;',
        '        public final String name;',
        '        public final String title;',
        '        public final int category;',
        '        public final String distance;',
        '        public final String held;',
        '        public final String sequence;',
        '',
        '        Move(String character, String name, String title, int category, String distance,',
        '                String held, List<String> steps) {',
        '            this.character = character;',
        '            this.name = name;',
        '            this.title = title;',
        '            this.category = category;',
        '            this.distance = distance;',
        '            this.held = held;',
        '            this.sequence = String.join(" ", steps);',
        '        }',
        '',
        '        public String displayName() {',
        '            return title == null || title.isEmpty() || title.equalsIgnoreCase(name) ? name : title;',
        '        }',
        '    }',
        '',
        '    public static final String[] CHARACTER_NAMES = {',
    ]
    names = [c['name'] for c in characters]
    for i in range(0, len(names), 6):
        chunk = ', '.join(java_string(n) for n in names[i:i + 6])
        lines.append('            ' + chunk + (',' if i + 6 < len(names) else ''))
    lines.append('    };')
    lines.append('')
    lines.append('    private static final List<Move> MOVE_LIST = build();')
    lines.append('')
    lines.append('    private MoveData() { }')
    lines.append('')
    lines.append('    public static List<Move> moves() { return MOVE_LIST; }')
    lines.append('')
    lines.append('    public static List<Move> movesFor(String character) {')
    lines.append('        List<Move> result = new ArrayList<>();')
    lines.append('        for (Move move : MOVE_LIST) {')
    lines.append('            if (move.character.equals(character)) result.add(move);')
    lines.append('        }')
    lines.append('        return result;')
    lines.append('    }')
    lines.append('')
    lines.append('    public static int characterIndex(String character) {')
    lines.append('        for (int i = 0; i < CHARACTER_NAMES.length; ++i) {')
    lines.append('            if (CHARACTER_NAMES[i].equals(character)) return i;')
    lines.append('        }')
    lines.append('        return 0;')
    lines.append('    }')
    lines.append('')
    lines.append('    private static List<Move> build() {')
    lines.append('        List<Move> moves = new ArrayList<>();')
    for character in characters:
        for move in character['finishers'] + character['specials']:
            category = ('CATEGORY_FATALITY' if move['category'] == 'FATALITY'
                        else 'CATEGORY_SPECIAL' if move['category'] == 'SPECIAL'
                        else 'CATEGORY_EXTRA')
            steps = ', '.join(java_string(s) for s in move['steps'])
            lines.append('        moves.add(new Move(%s, %s, %s, %s, %s, %s, java.util.Arrays.asList(%s)));'
                         % (java_string(character['name']), java_string(move['name']),
                            java_string(move['title']), category, java_string(move['distance']),
                            java_string(move['held']), steps))
    lines.append('        return Collections.unmodifiableList(moves);')
    lines.append('    }')
    lines.append('}')
    OUT_JAVA.write_text('\n'.join(lines) + '\n')
    print('wrote', OUT_JAVA, OUT_JAVA.stat().st_size, 'bytes')


def write_doc(characters):
    out = ['# UMK3 move table', '',
           'Generated by `tools/generate_moves.py` from the community move lists at '
           'mortalkombatwarehouse.com. Directions assume the fighter faces right; '
           'the app can mirror them.', '',
           'Buttons: `a` = A (Low Punch), `b` = B (Block), `c` = C (Low Kick), '
           '`x` = X (High Punch), `y` = Y (Run), `z` = Z (High Kick), '
           '`L` = back, `R` = forward, `U` = up, `D` = down.', '']
    for character in characters:
        out.append('## ' + character['name'])
        out.append('')
        for move in character['finishers'] + character['specials']:
            held = 'hold ' + move['held'] + ' + ' if move['held'] else ''
            out.append('- **%s** (%s%s): `%s%s`' % (
                move['name'], move['category'].title(),
                ', ' + move['distance'].title() if move['category'] == 'FATALITY' else '',
                held, ' '.join(move['steps'])))
        out.append('')
    OUT_DOC.write_text('\n'.join(out) + '\n')
    print('wrote', OUT_DOC)


if __name__ == '__main__':
    sys.exit(main())
