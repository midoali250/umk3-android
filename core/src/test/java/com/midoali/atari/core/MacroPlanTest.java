package com.midoali.atari.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** Verifies the generated UMK3 move table and the one-tap macro compiler. */
public final class MacroPlanTest {

    @Test public void everyMoveCompilesInBothDirections() {
        List<MoveData.Move> moves = MoveData.moves();
        assertTrue("move table is empty", moves.size() > 150);
        for (MoveData.Move move : moves) {
            MacroPlan forward = MacroPlan.of(move, false);
            MacroPlan mirrored = MacroPlan.of(move, true);
            assertEquals(forward.stepCount(), mirrored.stepCount());
            assertEquals(forward.holdMask, mirrored.holdMask);
            for (int i = 0; i < forward.stepCount(); ++i) {
                int step = forward.masks[i];
                assertTrue(move.character + "/" + move.name + " step " + i + " is empty", step != 0);
                assertEquals(step, SegaButtons.sanitize(step));
                assertEquals(mirrorDirections(forward.masks[i]), mirrored.masks[i]);
            }
            assertTrue(move.character + " has no sequence", move.sequence.trim().length() > 0);
        }
    }

    @Test public void directionsMirrorAndButtonsDoNot() {
        MacroPlan plan = MacroPlan.parse("", "R D L U", false);
        assertEquals(1 << SegaButtons.RIGHT, plan.masks[0]);
        assertEquals(1 << SegaButtons.DOWN, plan.masks[1]);
        assertEquals(1 << SegaButtons.LEFT, plan.masks[2]);
        assertEquals(1 << SegaButtons.UP, plan.masks[3]);
        MacroPlan mirrored = MacroPlan.parse("", "R D L U", true);
        assertEquals(1 << SegaButtons.LEFT, mirrored.masks[0]);
        assertEquals(1 << SegaButtons.RIGHT, mirrored.masks[2]);
        MacroPlan buttons = MacroPlan.parse("", "b+y", true);
        assertEquals((1 << SegaButtons.B) | (1 << SegaButtons.Y), buttons.masks[0]);
    }

    @Test public void heldButtonsStayDownForEveryStep() {
        MoveData.Move purpleHaze = find("Sonya", "Fatality 1");
        assertNotNull(purpleHaze);
        assertEquals("by", purpleHaze.held);
        MacroPlan plan = MacroPlan.of(purpleHaze, false);
        assertEquals((1 << SegaButtons.B) | (1 << SegaButtons.Y), plan.holdMask);
        assertEquals(4, plan.stepCount());
        assertEquals((1 << SegaButtons.UP) | plan.holdMask, plan.maskAt(0));
        assertEquals((1 << SegaButtons.LEFT) | plan.holdMask, plan.maskAt(2));
        assertTrue(plan.totalMillis() < 1200);
    }

    @Test public void knownFinishersMatchTheMoveList() {
        assertEquals("D D U z", find("Scorpion", "Fatality 1").sequence);
        assertEquals("D D U D x", find("Cyrax", "Fatality 1").sequence);
        MoveData.Move spear = find("Scorpion", "Spear");
        assertNotNull("Scorpion spear is missing", spear);
        assertEquals("L L a", spear.sequence);
        assertEquals("L R D D y", find("Sonya", "Fatality 2").sequence);
        assertEquals("y b y", find("Cyrax", "Stage Fatality").sequence);
    }

    @Test public void characterTableCoversTheRoster() {
        assertEquals(23, MoveData.CHARACTER_NAMES.length);
        Set<String> seen = new HashSet<>();
        for (String name : MoveData.CHARACTER_NAMES) assertTrue(name, seen.add(name));
        assertEquals(14, MoveData.characterIndex("Scorpion"));
        for (String name : MoveData.CHARACTER_NAMES) {
            List<MoveData.Move> moves = MoveData.movesFor(name);
            assertTrue(name + " has no fatality", moves.stream()
                    .anyMatch(m -> m.category == MoveData.CATEGORY_FATALITY));
            assertTrue(name + " has no special move", moves.stream()
                    .anyMatch(m -> m.category == MoveData.CATEGORY_SPECIAL));
            for (MoveData.Move move : moves) {
                assertFalse(move.character + "/" + move.name + " has an empty title",
                        move.displayName().trim().isEmpty());
            }
        }
    }

    private static int mirrorDirections(int mask) {
        int result = mask & ~((1 << SegaButtons.LEFT) | (1 << SegaButtons.RIGHT));
        if ((mask & (1 << SegaButtons.LEFT)) != 0) result |= 1 << SegaButtons.RIGHT;
        if ((mask & (1 << SegaButtons.RIGHT)) != 0) result |= 1 << SegaButtons.LEFT;
        return result;
    }

    private static MoveData.Move find(String character, String name) {
        for (MoveData.Move move : MoveData.movesFor(character)) {
            if (move.name.equalsIgnoreCase(name)) return move;
        }
        return null;
    }
}
