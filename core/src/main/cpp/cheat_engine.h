#ifndef SEGA_CHEAT_ENGINE_H
#define SEGA_CHEAT_ENGINE_H

#include <stddef.h>
#include <stdint.h>
#include <stdatomic.h>

#ifdef __cplusplus
extern "C" {
#endif

enum {
    SEGA_CHEAT_HEALTH = 0,  /* infinite energy for player one       */
    SEGA_CHEAT_RUN = 1,     /* infinite run meter                   */
    SEGA_CHEAT_TIME = 2,    /* clock frozen at 99                   */
    SEGA_CHEAT_P2_KO = 3,   /* one hit ends the opposing fighter    */
    SEGA_CHEAT_COUNT = 4
};

/* Clears the switches and every learnt maximum. */
void sega_cheats_reset(void);
void sega_cheats_set(int id, int enabled);
int sega_cheats_mask(void);
/* Highest value learnt for a meter (0 until a match has been seen). */
int sega_cheats_stats(int id);
/* Applies the enabled cheats to a work-RAM image. Returns 1 when a match is
 * live, 0 in menus (nothing is written there). */
int sega_cheats_apply(uint8_t *ram, size_t size);
/* 1 while the game is in a match and the engine is allowed to write. */
int sega_cheats_in_match(const uint8_t *ram, size_t size);
/* 1 when a switch that locks a meter is on, i.e. when the 68K write handlers
 * from cheat_hook.c are needed. */
int sega_cheats_guard_needed(void);
/* Called from the 68K write handlers before the store lands: rewrites *value
 * so a locked meter can never be written down. `offset` is the array offset. */
void sega_cheats_guard_byte(const uint8_t *ram, size_t size, unsigned offset, unsigned *value);
void sega_cheats_guard_word(const uint8_t *ram, size_t size, unsigned offset, unsigned *value);

#ifdef __cplusplus
}
#endif

#endif
