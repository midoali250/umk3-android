package com.midoali.atari.core;
import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class CheatsTest {
    /** Records what {@link Cheats#apply} pushes into the core. */
    private static final class Recorder implements Cheats.Core {
        final List<String> calls = new ArrayList<>();
        @Override public void setCheat(int id, boolean enabled) { calls.add(id + "=" + enabled); }
    }

    @Test public void everySwitchMapsToOneNativeId() {
        assertEquals(4, Cheats.COUNT);
        assertEquals(0, Cheats.INFINITE_ENERGY);
        assertEquals(1, Cheats.INFINITE_RUN);
        assertEquals(2, Cheats.FROZEN_CLOCK);
        assertEquals(3, Cheats.ONE_HIT_KO);
        assertEquals(0b1111, Cheats.ALL);
        assertEquals(0, Cheats.NONE);
    }

    @Test public void maskRoundTrip() {
        int mask = Cheats.mask(true, false, true, false);
        assertEquals(0b0101, mask);
        assertTrue(Cheats.isOn(mask, Cheats.INFINITE_ENERGY));
        assertFalse(Cheats.isOn(mask, Cheats.INFINITE_RUN));
        assertEquals(2, Cheats.count(mask));
        assertEquals(Cheats.ALL, Cheats.toggle(mask, Cheats.INFINITE_RUN) | 0b1010);
        assertEquals(mask, Cheats.with(Cheats.toggle(mask, Cheats.INFINITE_RUN), Cheats.INFINITE_RUN, false));
        assertEquals(0b0111, Cheats.toggle(mask, Cheats.INFINITE_RUN));
    }

    @Test public void applyPushesAllFourSwitches() {
        Recorder recorder = new Recorder();
        Cheats.apply(0b1010, recorder);
        assertEquals(4, recorder.calls.size());
        assertEquals("[0=false, 1=true, 2=false, 3=true]", recorder.calls.toString());
    }

    @Test public void junkFromDiskCannotEnableUnknownBits() {
        assertEquals(0b1111, Cheats.clamp(0xFFFFFFFF));
        Recorder recorder = new Recorder();
        Cheats.apply(0xFFFF, recorder);
        assertEquals("[0=true, 1=true, 2=true, 3=true]", recorder.calls.toString());
    }
}
