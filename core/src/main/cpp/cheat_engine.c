/* Cheat engine for the bundled Ultimate Mortal Kombat 3 cartridge.
 *
 * Plain C on purpose: this file is compiled into the Android library *and* into
 * the host build, so tools/verify_cheats.py exercises the exact code that ships
 * on the phone instead of a model of it.
 *
 * All addresses are offsets inside Mega Drive work RAM (0xFF0000..0xFFFFFF,
 * exposed by the core as RETRO_MEMORY_SYSTEM_RAM).  They were measured against
 * the retail ROM — the classic Action Replay lists are one byte off in places
 * (FFF229/FFF39B are really 0xF228/0xF39A here; the run meter is the byte after
 * each life word; the clock is two BCD digits at 0xEF66/0xEF68).
 */
#include "cheat_engine.h"

#define RAM_MATCH_FLAG 0xEF6A  /* 0 in every menu, 1 for a whole match   */
#define RAM_P1_HEALTH  0xF228  /* 0xA6 == full bar, 0x00 == K.O.          */
#define RAM_P2_HEALTH  0xF39A
#define RAM_P1_RUN     0xF22A  /* 0x30 == full run meter                  */
#define RAM_TIME_TENS  0xEF66  /* BCD tens digit of the round clock       */
#define RAM_TIME_UNITS 0xEF68  /* BCD units digit                         */

static _Atomic int cheat_mask;
/* Highest value each meter has shown during the current match, i.e. the game's
 * own maximum for the selected fighter, so secret fighters with a longer bar
 * work too.  Cleared whenever the match flag drops. */
static unsigned max_health, max_run, max_p2;

void sega_cheats_reset(void) {
    atomic_store(&cheat_mask, 0);
    max_health = max_run = max_p2 = 0;
}

void sega_cheats_set(int id, int enabled) {
    if (id < 0 || id >= SEGA_CHEAT_COUNT) return;
    int bit = 1 << id;
    if (enabled) atomic_fetch_or(&cheat_mask, bit);
    else atomic_fetch_and(&cheat_mask, ~bit);
}

int sega_cheats_mask(void) { return atomic_load(&cheat_mask); }

int sega_cheats_stats(int id) {
    switch (id) {
        case SEGA_CHEAT_HEALTH: return (int)max_health;
        case SEGA_CHEAT_RUN: return (int)max_run;
        case SEGA_CHEAT_P2_KO: return (int)max_p2;
        default: return 0;
    }
}

int sega_cheats_in_match(const uint8_t *ram, size_t size) {
    return (ram && size > RAM_MATCH_FLAG && ram[RAM_MATCH_FLAG]) ? 1 : 0;
}

int sega_cheats_guard_needed(void) {
    int mask = atomic_load(&cheat_mask);
    return (mask & ((1 << SEGA_CHEAT_HEALTH) | (1 << SEGA_CHEAT_RUN))) ? 1 : 0;
}

/* The lock only holds while the opponent is still standing: once a round is
 * decided the game clears both meters as part of its own housekeeping, and
 * swallowing that would hang the transition. */
static int guarding(const uint8_t *ram, size_t size) {
    if (!sega_cheats_in_match(ram, size)) return 0;
    if (size <= RAM_P2_HEALTH) return 0;
    return ram[RAM_P2_HEALTH] > 0;
}

void sega_cheats_guard_byte(const uint8_t *ram, size_t size, unsigned offset, unsigned *value) {
    int mask = atomic_load(&cheat_mask);
    unsigned lane = offset ^ 1u;   /* 68K byte access lane inside the word array */
    if (!mask || !value || !guarding(ram, size)) return;
    if ((mask & (1 << SEGA_CHEAT_HEALTH)) && lane == RAM_P1_HEALTH && max_health &&
        (*value & 0xFFu) < max_health)
        *value = max_health;
    if ((mask & (1 << SEGA_CHEAT_RUN)) && lane == RAM_P1_RUN && max_run &&
        (*value & 0xFFu) < max_run)
        *value = max_run;
}

void sega_cheats_guard_word(const uint8_t *ram, size_t size, unsigned offset, unsigned *value) {
    int mask = atomic_load(&cheat_mask);
    if (!mask || !value || !guarding(ram, size)) return;
    if ((mask & (1 << SEGA_CHEAT_HEALTH)) && offset == RAM_P1_HEALTH && max_health &&
        (*value & 0xFFu) < max_health)
        *value = (*value & 0xFF00u) | max_health;
    if ((mask & (1 << SEGA_CHEAT_RUN)) && offset == RAM_P1_RUN && max_run &&
        (*value & 0xFFu) < max_run)
        *value = (*value & 0xFF00u) | max_run;
}

int sega_cheats_apply(uint8_t *ram, size_t size) {
    if (!ram || size <= RAM_MATCH_FLAG) return 0;
    if (!ram[RAM_MATCH_FLAG]) {
        max_health = max_run = max_p2 = 0;
        return 0;                      /* menus: never touch game memory */
    }
    if (size <= RAM_P2_HEALTH) return 1;
    /* Learn each meter's full value from the moment the round starts, whether or
     * not a switch is on, so flipping one in the middle of a beating heals to
     * the real maximum instead of freezing the damage in place. */
    unsigned health = ram[RAM_P1_HEALTH], run = ram[RAM_P1_RUN], p2 = ram[RAM_P2_HEALTH];
    if (health > max_health) max_health = health;
    if (run > max_run) max_run = run;
    if (p2 > max_p2) max_p2 = p2;
    int mask = atomic_load(&cheat_mask);
    if (!mask) return 1;               /* live, but nothing enabled */
    if ((mask & (1 << SEGA_CHEAT_HEALTH)) && max_health && health < max_health)
        ram[RAM_P1_HEALTH] = (uint8_t)max_health;
    if ((mask & (1 << SEGA_CHEAT_RUN)) && max_run && run < max_run)
        ram[RAM_P1_RUN] = (uint8_t)max_run;
    if (mask & (1 << SEGA_CHEAT_TIME)) {
        ram[RAM_TIME_TENS] = 0x09;
        ram[RAM_TIME_UNITS] = 0x09;
    }
    /* One hit K.O.: scratch the opponent and their life drops to zero, and it
     * is left there for a frame so the engine registers the knockout. */
    if ((mask & (1 << SEGA_CHEAT_P2_KO)) && max_p2 && p2 > 0 && p2 < max_p2)
        ram[RAM_P2_HEALTH] = 0;
    return 1;
}
