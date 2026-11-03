/* Write-level lock for the life and run meters.
 *
 * The per-frame engine in cheat_engine.c can only repair a meter between frames:
 * the game has already drawn the shortened bar by then, so the bar can dip for a
 * single frame.  To make "the bar never goes down" literally true, the 68K core
 * is asked to route work-RAM writes through us, and a write that would lower a
 * locked meter is neutralised before it lands.
 *
 * The handlers reproduce exactly what m68ki_write_8/16 do with a NULL handler:
 *   word at offset O  ->  ram[O] = value & 0xff, ram[O+1] = value >> 8
 *   byte at offset O  ->  ram[O ^ 1] = value & 0xff
 * (Mega Drive RAM is stored as little-endian words in a byte array; a 68K byte
 * access at an odd address is the low byte of that word.)
 *
 * Only the 0xE0-0xFF work-RAM mirrors are touched, and only while a locking
 * switch is on: with the switches off the map is restored to NULL handlers and
 * the emulator runs untouched.
 */
#include "cheat_hook.h"

#include <string.h>

#include "cheat_engine.h"
#include "shared.h"

#define RAM_LOW  0xE0
#define RAM_HIGH 0xFF

static int installed;

static void ram_write8(unsigned int address, unsigned int data) {
    unsigned int offset = address & 0xFFFFu;
    unsigned int value = data & 0xFFu;
    sega_cheats_guard_byte(work_ram, sizeof(work_ram), offset, &value);
    work_ram[offset ^ 1] = (uint8)(value & 0xFFu);
}

static void ram_write16(unsigned int address, unsigned int data) {
    unsigned int offset = address & 0xFFFFu;
    unsigned int value = data & 0xFFFFu;
    sega_cheats_guard_word(work_ram, sizeof(work_ram), offset, &value);
    work_ram[offset] = (uint8)(value & 0xFFu);
    work_ram[offset + 1] = (uint8)((value >> 8) & 0xFFu);
}

/* Idempotent on purpose: m68k_init() rebuilds the map on every reset, so the
 * pointers are re-asserted each frame instead of being cached. */
void sega_cheats_hook_sync(void) {
    unsigned int i;
    if (sega_cheats_guard_needed()) {
        for (i = RAM_LOW; i <= RAM_HIGH; i++) {
            if (m68k.memory_map[i].write8 != ram_write8) m68k.memory_map[i].write8 = ram_write8;
            if (m68k.memory_map[i].write16 != ram_write16) m68k.memory_map[i].write16 = ram_write16;
        }
        installed = 1;
    } else if (installed) {
        for (i = RAM_LOW; i <= RAM_HIGH; i++) {
            if (m68k.memory_map[i].write8 == ram_write8) m68k.memory_map[i].write8 = NULL;
            if (m68k.memory_map[i].write16 == ram_write16) m68k.memory_map[i].write16 = NULL;
        }
        installed = 0;
    }
}

int sega_cheats_hook_active(void) { return installed; }
