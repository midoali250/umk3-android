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

#ifdef __cplusplus
}
#endif

#endif
