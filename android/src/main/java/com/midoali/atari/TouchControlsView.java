package com.midoali.atari;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import com.midoali.atari.core.ControlLayout;
import com.midoali.atari.core.PadMath;
import com.midoali.atari.core.SegaButtons;
import java.util.Arrays;

/**
 * Movable six-button Sega pad with a free analog stick and a one-tap FINISH button.
 *
 * <p>The stick replaces the old discrete cross: the whole circular pad is one control, the
 * direction is measured from the pad centre in eight sectors, so diagonals (jump back,
 * jump forward, sweep at an angle) work exactly like a real analog thumbstick. A knob
 * follows the finger so the engaged direction is always visible.
 */
public final class TouchControlsView extends View {
    public static final int[] FACE_IDS = {SegaButtons.A, SegaButtons.B, SegaButtons.C,
            SegaButtons.X, SegaButtons.Y, SegaButtons.Z};
    private static final String[] FACE_LABELS = {"A", "B", "C", "X", "Y", "Z"};
    private static final int[] FACE_COLORS = {0xffd94b4b, 0xff4bbd68, 0xffe2b83d,
            0xff4b78d9, 0xff9b59d0, 0xff3fb8c4};
    private static final long LONG_PRESS_MILLIS = 550;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF startRect = new RectF();
    private final float[] pointerX = new float[32], pointerY = new float[32];
    private final int[] pointerIds = new int[32];
    private final Runnable longPress = new Runnable() {
        @Override public void run() {
            if (!finishDown) return;
            finishDown = false;
            longPressFired = true;
            if (finishRepeatListener != null) finishRepeatListener.run();
        }
    };
    private ControlLayout layout = new ControlLayout();
    private boolean editing = false, interactive = true;
    private int pointerCount, dragGroup = -1, lastMask;
    private float density, base, padRadius, faceRadius, startRadius, finishRadius, rowGap;
    private int stickId = -1, finishId = -1;
    private float stickDx, stickDy, finishDownX, finishDownY;
    private boolean finishDown, longPressFired;
    private InputSink sink;
    private Runnable emptyTapListener, finishListener, finishRepeatListener;
    public interface InputSink { void onInput(int mask); }

    public TouchControlsView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        Arrays.fill(pointerIds, -1);
        setClickable(true); setFocusable(false);
        setContentDescription(context.getString(R.string.touch_controls_description));
    }
    public void setInputSink(InputSink value) { sink = value; }
    public void setOnEmptyTapListener(Runnable value) { emptyTapListener = value; }
    /** Runs when the FINISH button is tapped: opens the one-tap move panel. */
    public void setFinishListener(Runnable value) { finishListener = value; }
    /** Runs when the FINISH button is held: repeats the last executed move instantly. */
    public void setFinishRepeatListener(Runnable value) { finishRepeatListener = value; }
    public void setInteractive(boolean value) { interactive = value; if (!value) clearInput(); }
    public void clearInput() { removeCallbacks(longPress); clearPointers(); stickId = -1; finishId = -1; finishDown = false; longPressFired = false; dragGroup = -1; emit(0); invalidate(); }
    public void refreshSizes() { refreshSizesInternal(); constrainLayout(); invalidate(); }
    public void setLayout(ControlLayout value) {
        layout = value.copy(); refreshSizesInternal(); constrainLayout(); invalidate();
    }
    public ControlLayout layout() { return layout.copy(); }
    public void setEditing(boolean value) {
        editing = value; clearPointers(); stickId = -1; finishId = -1; emit(0); invalidate();
    }
    public boolean isEditing() { return editing; }
    int dragGroupForTest() { return dragGroup; }
    public int currentMask() { return lastMask; }
    public int faceButtonCount() { return layout.sixButton ? 6 : 3; }
    public float faceRadius() { return faceRadius; }
    public float padRadius() { return padRadius; }
    /** True while a finger is steering the analog stick. */
    public boolean stickEngaged() { return stickId >= 0; }
    public float stickOffsetX() { return stickDx; }
    public float stickOffsetY() { return stickDy; }
    public float centerX(int group) {
        return (group == ControlLayout.GROUP_PAD ? layout.padX
                : group == ControlLayout.GROUP_FACE ? layout.faceX
                : group == ControlLayout.GROUP_FINISH ? layout.finishX : layout.startX) * getWidth();
    }
    public float centerY(int group) {
        return (group == ControlLayout.GROUP_PAD ? layout.padY
                : group == ControlLayout.GROUP_FACE ? layout.faceY
                : group == ControlLayout.GROUP_FINISH ? layout.finishY : layout.startY) * getHeight();
    }
    public float finishRadius() { return finishRadius; }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        refreshSizesInternal(); constrainLayout();
    }
    private void refreshSizesInternal() {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        base = Math.min(h * 0.22f, w * 0.105f) * layout.scale;
        padRadius = base * 0.92f;
        faceRadius = base * 0.34f;
        startRadius = base * 0.30f;
        finishRadius = base * 0.32f;
        rowGap = faceRadius * 2.05f;
    }
    private void constrainLayout() {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || base <= 0) return;
        float margin = 6 * density;
        clampGroup(ControlLayout.GROUP_PAD, layout.padX, layout.padY, padRadius + margin, padRadius + margin, w, h);
        float faceHalf = faceRadius * 3.35f + margin;
        float faceHalfY = rowGap * 0.5f + faceRadius * 1.15f + margin;
        clampGroup(ControlLayout.GROUP_FACE, layout.faceX, layout.faceY, faceHalf, faceHalfY, w, h);
        clampGroup(ControlLayout.GROUP_START, layout.startX, layout.startY, startRadius * 1.7f + margin, startRadius + margin, w, h);
        clampGroup(ControlLayout.GROUP_FINISH, layout.finishX, layout.finishY, finishRadius * 1.2f + margin, finishRadius * 1.6f + margin, w, h);
    }
    private void clampGroup(int group, float x, float y, float halfW, float halfH, int w, int h) {
        float minX = Math.min(0.5f, halfW / w), maxX = 1f - minX;
        float minY = Math.min(0.5f, halfH / h), maxY = 1f - minY;
        layout.move(group, PadMath.clamp(x, minX, maxX), PadMath.clamp(y, minY, maxY));
    }
    public float faceX(int index) { return layout.faceX * getWidth() + (index % 3 - 1) * faceRadius * 2.35f; }
    public float faceY(int index) {
        boolean top = index >= 3;
        float y = layout.faceY * getHeight() + (top ? -rowGap * 0.5f : rowGap * 0.5f);
        int col = index % 3;
        if (col != 1) y += top ? -faceRadius * 0.12f : faceRadius * 0.12f;
        else if (top) y -= faceRadius * 0.18f;
        return y;
    }
    private void circle(Canvas canvas, float x, float y, float radius, int color, boolean down, String label) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(down ? Color.argb(235, 255, 255, 255) : color);
        paint.setAlpha((int)(layout.opacity * (down ? 255 : 220)));
        canvas.drawCircle(x, y, radius, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Math.max(1.5f, density));
        paint.setColor(Color.argb((int)(layout.opacity * 230), 255, 255, 255));
        canvas.drawCircle(x, y, radius, paint);
        paint.setStyle(Paint.Style.FILL); paint.setColor(down ? Color.BLACK : Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(radius * 0.82f);
        paint.setAlpha((int)(layout.opacity * 255));
        canvas.drawText(label, x, y - (paint.ascent() + paint.descent()) / 2, paint);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (base <= 0) return;
        drawStick(canvas);
        int count = faceButtonCount();
        for (int i = 0; i < count; ++i) {
            int id = FACE_IDS[i];
            circle(canvas, faceX(i), faceY(i), faceRadius, FACE_COLORS[i], (lastMask & (1 << id)) != 0, FACE_LABELS[i]);
        }
        float sx = layout.startX * getWidth(), sy = layout.startY * getHeight();
        paint.setAlpha(255);
        startRect.set(sx - startRadius * 1.6f, sy - startRadius, sx + startRadius * 1.6f, sy + startRadius);
        paint.setStyle(Paint.Style.FILL); paint.setColor(Color.argb((int)(layout.opacity * 220), 55, 60, 72));
        canvas.drawRoundRect(startRect, startRadius, startRadius, paint);
        paint.setColor(Color.WHITE); paint.setTextSize(startRadius * 0.72f); paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("START", sx, sy - (paint.ascent() + paint.descent()) / 2, paint);
        drawFinish(canvas);
        if (editing) {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2 * density);
            paint.setColor(Color.argb(220, 80, 200, 255));
            canvas.drawCircle(layout.padX * getWidth(), layout.padY * getHeight(), padRadius + 4 * density, paint);
            canvas.drawCircle(layout.faceX * getWidth(), layout.faceY * getHeight(), faceRadius * 3.5f, paint);
            canvas.drawRoundRect(startRect, startRadius, startRadius, paint);
            canvas.drawCircle(finishX(), finishY(), finishRadius + 4 * density, paint);
        }
    }

    /** Analog thumbstick: ring, eight direction ticks and a knob that follows the finger. */
    private void drawStick(Canvas canvas) {
        float px = layout.padX * getWidth(), py = layout.padY * getHeight();
        int alpha = (int)(layout.opacity * 255);
        paint.setAlpha(255);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb((int)(layout.opacity * 105), 20, 24, 30));
        canvas.drawCircle(px, py, padRadius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, 2.2f * density));
        paint.setColor(Color.argb((int)(layout.opacity * 210), 150, 165, 190));
        canvas.drawCircle(px, py, padRadius, paint);
        // Eight small ticks so the diagonal sectors are obvious.
        paint.setStrokeWidth(Math.max(2f, 2f * density));
        for (int i = 0; i < 8; ++i) {
            double angle = -Math.PI / 2 + i * Math.PI / 4;
            float inner = padRadius * (i % 2 == 0 ? 0.80f : 0.86f);
            float outer = padRadius * (i % 2 == 0 ? 0.95f : 0.95f);
            paint.setColor(Color.argb((int)(layout.opacity * (i % 2 == 0 ? 235 : 130)), 235, 240, 250));
            canvas.drawLine(px + (float)Math.cos(angle) * inner, py + (float)Math.sin(angle) * inner,
                    px + (float)Math.cos(angle) * outer, py + (float)Math.sin(angle) * outer, paint);
        }
        float knobX = px + stickDx, knobY = py + stickDy;
        int mask = stickMask();
        if (mask != 0) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2.5f, 3f * density));
            paint.setColor(Color.argb((int)(layout.opacity * 235), 120, 220, 255));
            canvas.drawLine(px, py, knobX, knobY, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb((int)(layout.opacity * (stickId >= 0 ? 245 : 190)), 235, 240, 250));
        canvas.drawCircle(knobX, knobY, padRadius * 0.36f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5f, 1.6f * density));
        paint.setColor(Color.argb(alpha, 70, 80, 100));
        canvas.drawCircle(knobX, knobY, padRadius * 0.36f, paint);
    }

    private void drawFinish(Canvas canvas) {
        paint.setAlpha(255);
        float fx = finishX(), fy = finishY();
        boolean down = finishDown || longPressFired;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(down ? Color.argb(240, 255, 236, 160) : Color.argb((int)(layout.opacity * 235), 150, 30, 40));
        canvas.drawCircle(fx, fy, finishRadius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.8f, 2f * density));
        paint.setColor(Color.argb((int)(layout.opacity * 235), 255, 214, 90));
        canvas.drawCircle(fx, fy, finishRadius, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(down ? Color.BLACK : Color.argb((int)(layout.opacity * 255), 255, 232, 150));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(finishRadius * 0.92f);
        canvas.drawText("★", fx, fy - (paint.ascent() + paint.descent()) / 2 - finishRadius * 0.06f, paint);
        paint.setTextSize(finishRadius * 0.36f);
        canvas.drawText("FINISH", fx, fy + finishRadius * 0.66f, paint);
    }

    private float finishX() { return layout.finishX * getWidth(); }
    private float finishY() { return layout.finishY * getHeight(); }
    private boolean inFinish(float x, float y) {
        return Math.hypot(x - finishX(), y - finishY()) <= finishRadius * 1.25f;
    }
    private boolean inStickZone(float x, float y) {
        return Math.hypot(x - layout.padX * getWidth(), y - layout.padY * getHeight()) <= padRadius * 1.35f;
    }
    private void steerStick(float x, float y) {
        float dx = x - layout.padX * getWidth(), dy = y - layout.padY * getHeight();
        float length = (float)Math.hypot(dx, dy), limit = padRadius * 0.94f;
        if (length > limit && length > 0) { dx = dx / length * limit; dy = dy / length * limit; }
        stickDx = dx; stickDy = dy;
    }
    private int stickMask() {
        if (stickId < 0) return 0;
        return PadMath.direction(stickDx, stickDy, padRadius);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!interactive && !editing) return false;
        int action = event.getActionMasked();
        boolean emptyTap = false;
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            int index = event.getActionIndex();
            if (pointerCount < pointerIds.length) {
                int id = event.getPointerId(index);
                pointerIds[pointerCount] = id;
                pointerX[pointerCount] = event.getX(index); pointerY[pointerCount] = event.getY(index);
                // pointerCount is the count BEFORE inserting this pointer.
                // The first finger must select a drag group; checking == 1 here
                // made layout editing silently ignore every drag.
                if (editing && pointerCount == 0) dragGroup = groupAt(pointerX[0], pointerY[0]);
                pointerCount++;
                if (!editing) {
                    if (inFinish(pointerX[pointerCount - 1], pointerY[pointerCount - 1]) && finishId < 0) {
                        finishId = id; finishDown = true; longPressFired = false;
                        finishDownX = pointerX[pointerCount - 1]; finishDownY = pointerY[pointerCount - 1];
                        postDelayed(longPress, LONG_PRESS_MILLIS);
                    } else if (stickId < 0 && inStickZone(pointerX[pointerCount - 1], pointerY[pointerCount - 1])) {
                        stickId = id;
                        steerStick(pointerX[pointerCount - 1], pointerY[pointerCount - 1]);
                    }
                }
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < event.getPointerCount(); ++i) {
                int slot = slot(event.getPointerId(i));
                if (slot >= 0) { pointerX[slot] = event.getX(i); pointerY[slot] = event.getY(i); }
            }
            if (editing && dragGroup >= 0 && pointerCount > 0) {
                layout.move(dragGroup, pointerX[0] / getWidth(), pointerY[0] / getHeight());
                constrainLayout();
            } else if (!editing) {
                int stickSlot = slot(stickId);
                if (stickSlot >= 0) steerStick(pointerX[stickSlot], pointerY[stickSlot]);
                int finishSlot = slot(finishId);
                if (finishSlot >= 0 && finishDown &&
                        Math.hypot(pointerX[finishSlot] - finishDownX, pointerY[finishSlot] - finishDownY) > finishRadius) {
                    finishDown = false; removeCallbacks(longPress); finishId = -1;
                }
            }
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            int id = event.getPointerId(event.getActionIndex());
            if (id == stickId) { stickId = -1; stickDx = 0; stickDy = 0; }
            else if (id == finishId) { finishId = -1; finishDown = false; removeCallbacks(longPress); }
            removeSlot(slot(id));
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            emptyTap = action == MotionEvent.ACTION_UP && !editing && pointerCount == 1 &&
                    hitMask(pointerX[0], pointerY[0]) == 0 && !finishDown && !longPressFired &&
                    !inFinish(pointerX[0], pointerY[0]) && pointerY[0] < getHeight() * 0.20f;
            boolean tappedFinish = action == MotionEvent.ACTION_UP && finishDown && !longPressFired;
            removeCallbacks(longPress);
            finishDown = false; finishId = -1;
            if (stickId >= 0) { stickId = -1; stickDx = 0; stickDy = 0; }
            clearPointers(); dragGroup = -1;
            if (tappedFinish && finishListener != null) finishListener.run();
            longPressFired = false;
        }
        if (!editing) {
            int mask = stickMask();
            for (int i = 0; i < pointerCount; ++i) mask |= buttonMask(pointerX[i], pointerY[i]);
            emit(SegaButtons.cancelOpposites(mask));
        }
        invalidate();
        if (emptyTap && emptyTapListener != null) emptyTapListener.run();
        return true;
    }
    private int slot(int id) { if (id < 0) return -1; for (int i = 0; i < pointerCount; ++i) if (pointerIds[i] == id) return i; return -1; }
    private void removeSlot(int slot) {
        if (slot < 0) return;
        for (int i = slot; i < pointerCount - 1; ++i) {
            pointerIds[i] = pointerIds[i + 1]; pointerX[i] = pointerX[i + 1]; pointerY[i] = pointerY[i + 1];
        }
        pointerCount--; pointerIds[pointerCount] = -1;
    }
    private void clearPointers() { pointerCount = 0; Arrays.fill(pointerIds, -1); }
    private int groupAt(float x, float y) {
        if (Math.hypot(x - layout.padX * getWidth(), y - layout.padY * getHeight()) <= padRadius * 1.15f) {
            return ControlLayout.GROUP_PAD;
        }
        if (Math.abs(x - layout.faceX * getWidth()) <= faceRadius * 3.6f &&
                Math.abs(y - layout.faceY * getHeight()) <= rowGap * 0.5f + faceRadius * 1.3f) return ControlLayout.GROUP_FACE;
        if (inFinish(x, y)) return ControlLayout.GROUP_FINISH;
        if (startRect.contains(x, y)) return ControlLayout.GROUP_START;
        return -1;
    }
    /** Full mask for a single point: free-stick direction plus face buttons, START and FINISH. */
    public int hitMask(float x, float y) {
        int mask = 0;
        float dx = x - layout.padX * getWidth(), dy = y - layout.padY * getHeight();
        if (Math.hypot(dx, dy) <= padRadius * 1.35f) {
            float length = (float)Math.hypot(dx, dy), limit = padRadius * 0.94f;
            if (length > limit) { dx = dx / length * limit; dy = dy / length * limit; }
            mask |= PadMath.direction(dx, dy, padRadius);
        }
        return mask | buttonMask(x, y);
    }
    /** Face buttons and START only; the stick is steered by its own pointer. */
    private int buttonMask(float x, float y) {
        int mask = 0;
        int count = faceButtonCount();
        for (int i = 0; i < count; ++i)
            if (Math.hypot(x - faceX(i), y - faceY(i)) <= faceRadius * 1.08f) mask |= 1 << FACE_IDS[i];
        if (startRect.contains(x, y)) mask |= 1 << SegaButtons.START;
        return mask;
    }
    private void emit(int mask) {
        if (mask == lastMask) return;
        lastMask = mask;
        if (sink != null) sink.onInput(mask);
        if (layout.haptics && mask != 0) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }
}
