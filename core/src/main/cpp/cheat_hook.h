#ifndef SEGA_CHEAT_HOOK_H
#define SEGA_CHEAT_HOOK_H

#ifdef __cplusplus
extern "C" {
#endif

/* Installs or removes the work-RAM write handlers for the locking switches.
 * Safe to call every frame. */
void sega_cheats_hook_sync(void);
int sega_cheats_hook_active(void);

#ifdef __cplusplus
}
#endif

#endif
