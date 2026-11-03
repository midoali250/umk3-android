package com.midoali.atari.core;

/**
 * The four cheat switches, in the order the panel shows them.
 *
 * <p>Ids are the native constants from {@code core/src/main/cpp/cheat_engine.h}.
 * They are applied by the core to Mega Drive work RAM on every frame, so a
 * switch takes effect in the fight that is already running.</p>
 */
public final class Cheats {
    public static final int INFINITE_ENERGY = 0;
    public static final int INFINITE_RUN = 1;
    public static final int FROZEN_CLOCK = 2;
    public static final int ONE_HIT_KO = 3;
    public static final int COUNT = 4;
    /** Default: everything off, the game plays as it did before. */
    public static final int NONE = 0;
    public static final int ALL = (1 << COUNT) - 1;

    private Cheats() {}

    public static int mask(boolean... switches) {
        int mask = NONE;
        for (int i = 0; i < switches.length && i < COUNT; i++) if (switches[i]) mask |= 1 << i;
        return mask;
    }

    public static boolean isOn(int mask, int cheat) { return (mask & (1 << cheat)) != 0; }

    public static int with(int mask, int cheat, boolean on) {
        int bit = 1 << cheat;
        return on ? (mask | bit) : (mask & ~bit);
    }

    public static int toggle(int mask, int cheat) { return with(mask, cheat, !isOn(mask, cheat)); }

    public static int count(int mask) {
        int n = 0;
        for (int i = 0; i < COUNT; i++) if (isOn(mask, i)) n++;
        return n;
    }

    /** Sanitises anything that arrives from preferences or tests. */
    public static int clamp(int mask) { return mask & ALL; }

    /**
     * Pushes a whole mask into the running core.
     *
     * @param core sink for {@code nativeSetCheat}, injectable so the mapping can
     *             be unit-tested on the JVM without the native library.
     */
    public static void apply(int mask, Core core) {
        int safe = clamp(mask);
        for (int i = 0; i < COUNT; i++) core.setCheat(i, isOn(safe, i));
    }

    /** Thin seam so {@link #apply(int, Core)} is testable off-device. */
    public interface Core {
        void setCheat(int id, boolean enabled);
    }
}
