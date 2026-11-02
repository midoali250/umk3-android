package com.midoali.atari;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class BuildConfigTest {
    @Test
    public void variantsHaveDistinctIdentities() {
        if (BuildConfig.PERSONAL_GAME) {
            assertTrue(BuildConfig.APPLICATION_ID.startsWith("com.midoali.umk3"));
            assertEquals("0.8.0-umk3", BuildConfig.VERSION_NAME);
        } else if (BuildConfig.DEBUG) {
            assertEquals("com.midoali.atari.debug", BuildConfig.APPLICATION_ID);
            assertEquals("0.8.0-debug", BuildConfig.VERSION_NAME);
        } else {
            assertEquals("com.midoali.atari", BuildConfig.APPLICATION_ID);
            assertEquals("0.8.0", BuildConfig.VERSION_NAME);
        }
        assertEquals(8, BuildConfig.VERSION_CODE);
    }

    @Test
    public void buildTypeFlagMatchesPackageName() {
        assertTrue(BuildConfig.APPLICATION_ID.startsWith("com.midoali."));
        if (BuildConfig.PERSONAL_GAME) {
            assertFalse(BuildConfig.APPLICATION_ID.endsWith(".debug"));
        } else {
            assertEquals(BuildConfig.DEBUG, BuildConfig.APPLICATION_ID.endsWith(".debug"));
        }
        assertFalse(BuildConfig.VERSION_NAME.isEmpty());
    }
}
