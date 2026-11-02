package com.midoali.atari.core;

/**
 * One-button macro for a game move: a fixed sequence of short pad presses.
 *
 * <p>The DSL uses {@code U}/{@code D}/{@code L}/{@code R} for directions (forward is
 * {@code R} while the fighter faces right) and {@code a b c x y z} for the six Genesis
 * buttons. {@code held} lists buttons that stay pressed for the whole sequence.
 */
public final class MacroPlan {
    /** How long every synthetic press is held. Four frames at 60 Hz registers reliably. */
    public static final int PRESS_MILLIS = 72;
    /** Pause between two presses so the game sees distinct key changes. */
    public static final int GAP_MILLIS = 58;
    /** Extra time the last press (and any held buttons) stays down before release. */
    public static final int SETTLE_MILLIS = 140;

    public final int[] masks;
    public final int holdMask;

    private MacroPlan(int[] masks, int holdMask) {
        this.masks = masks;
        this.holdMask = holdMask;
    }

    public static MacroPlan of(MoveData.Move move, boolean mirror) {
        return parse(move.held, move.sequence, mirror);
    }

    public static MacroPlan parse(String held, String sequence, boolean mirror) {
        int hold = maskOf(held, mirror);
        String trimmed = sequence == null ? "" : sequence.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("empty macro sequence");
        String[] steps = trimmed.split("\\s+");
        int[] masks = new int[steps.length];
        for (int i = 0; i < steps.length; ++i) {
            int mask = maskOf(steps[i], mirror);
            if (mask == 0) throw new IllegalArgumentException("empty macro step " + i);
            masks[i] = mask;
        }
        return new MacroPlan(masks, hold);
    }

    /** Turns "b+y" or "D" into a SegaButtons mask. */
    public static int maskOf(String tokens, boolean mirror) {
        int mask = 0;
        if (tokens == null) return 0;
        for (int i = 0; i < tokens.length(); ++i) {
            char token = tokens.charAt(i);
            if (token == '+' || Character.isWhitespace(token)) continue;
            mask |= bit(token, mirror);
        }
        return mask;
    }

    private static int bit(char token, boolean mirror) {
        switch (token) {
            case 'U': return 1 << SegaButtons.UP;
            case 'D': return 1 << SegaButtons.DOWN;
            case 'L': return 1 << (mirror ? SegaButtons.RIGHT : SegaButtons.LEFT);
            case 'R': return 1 << (mirror ? SegaButtons.LEFT : SegaButtons.RIGHT);
            case 'a': return 1 << SegaButtons.A;
            case 'b': return 1 << SegaButtons.B;
            case 'c': return 1 << SegaButtons.C;
            case 'x': return 1 << SegaButtons.X;
            case 'y': return 1 << SegaButtons.Y;
            case 'z': return 1 << SegaButtons.Z;
            default: throw new IllegalArgumentException("unknown macro token: " + token);
        }
    }

    public int stepCount() { return masks.length; }

    /** Mask to send for one step, including buttons that stay held for the whole move. */
    public int maskAt(int step) { return masks[step] | holdMask; }

    public int totalMillis() {
        return masks.length * (PRESS_MILLIS + GAP_MILLIS) + SETTLE_MILLIS;
    }
}
