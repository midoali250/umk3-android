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

/** Movable six-button Sega pad. Top row X/Y/Z, bottom row A/B/C, plus D-pad and START. */
public final class TouchControlsView extends View {
    public static final int[] FACE_IDS = {SegaButtons.A, SegaButtons.B, SegaButtons.C,
            SegaButtons.X, SegaButtons.Y, SegaButtons.Z};
    private static final String[] FACE_LABELS = {"A", "B", "C", "X", "Y", "Z"};
    private static final int[] FACE_COLORS = {0xffd94b4b, 0xff4bbd68, 0xffe2b83d,
            0xff4b78d9, 0xff9b59d0, 0xff3fb8c4};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF startRect = new RectF();
    private final float[] pointerX = new float[32], pointerY = new float[32];
    private final int[] pointerIds = new int[32];
    private ControlLayout layout = new ControlLayout();
    private boolean editing = false, interactive = true;
    private int pointerCount, dragGroup = -1, lastMask;
    private float density, base, padRadius, faceRadius, startRadius, rowGap;
    private InputSink sink;
    private Runnable emptyTapListener;
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
    public void setInteractive(boolean value) { interactive = value; if (!value) clearInput(); }
    public void clearInput() { clearPointers(); dragGroup = -1; emit(0); invalidate(); }
    public void refreshSizes() { refreshSizesInternal(); constrainLayout(); invalidate(); }
    public void setLayout(ControlLayout value) {
        layout = value.copy(); refreshSizesInternal(); constrainLayout(); invalidate();
    }
    public ControlLayout layout() { return layout.copy(); }
    public void setEditing(boolean value) {
        editing = value; clearPointers(); emit(0); invalidate();
    }
    public boolean isEditing() { return editing; }
    int dragGroupForTest() { return dragGroup; }
    public int currentMask() { return lastMask; }
    public int faceButtonCount() { return layout.sixButton ? 6 : 3; }
    public float faceRadius() { return faceRadius; }
    public float padRadius() { return padRadius; }
    public float centerX(int group) { return (group == 0 ? layout.padX : group == 1 ? layout.faceX : layout.startX) * getWidth(); }
    public float centerY(int group) { return (group == 0 ? layout.padY : group == 1 ? layout.faceY : layout.startY) * getHeight(); }

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
        rowGap = faceRadius * 2.05f;
    }
    private void constrainLayout() {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || base <= 0) return;
        float margin = 6 * density;
        clampGroup(0, layout.padX, layout.padY, padRadius + margin, padRadius + margin, w, h);
        float faceHalf = faceRadius * 3.35f + margin;
        float faceHalfY = rowGap * 0.5f + faceRadius * 1.15f + margin;
        clampGroup(1, layout.faceX, layout.faceY, faceHalf, faceHalfY, w, h);
        clampGroup(2, layout.startX, layout.startY, startRadius * 1.7f + margin, startRadius + margin, w, h);
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
        paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(radius * 0.82f);
        paint.setAlpha((int)(layout.opacity * 255));
        canvas.drawText(label, x, y - (paint.ascent() + paint.descent()) / 2, paint);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (base <= 0) return;
        float px = layout.padX * getWidth(), py = layout.padY * getHeight();
        paint.setStyle(Paint.Style.FILL); paint.setColor(Color.argb((int)(layout.opacity * 120), 25, 28, 34));
        canvas.drawCircle(px, py, padRadius, paint);
        float arm = padRadius * 0.34f, len = padRadius * 0.92f;
        paint.setColor(Color.argb((int)(layout.opacity * 225), 65, 70, 82));
        canvas.drawRoundRect(new RectF(px - arm, py - len, px + arm, py + len), arm, arm, paint);
        canvas.drawRoundRect(new RectF(px - len, py - arm, px + len, py + arm), arm, arm, paint);
        paint.setColor(Color.WHITE); paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(arm * 1.15f);
        canvas.drawText("▲", px, py - len * 0.58f, paint); canvas.drawText("▼", px, py + len * 0.78f, paint);
        canvas.drawText("◀", px - len * 0.66f, py + arm * 0.38f, paint); canvas.drawText("▶", px + len * 0.66f, py + arm * 0.38f, paint);
        int count = faceButtonCount();
        for (int i = 0; i < count; ++i) {
            int id = FACE_IDS[i];
            circle(canvas, faceX(i), faceY(i), faceRadius, FACE_COLORS[i], (lastMask & (1 << id)) != 0, FACE_LABELS[i]);
        }
        float sx = layout.startX * getWidth(), sy = layout.startY * getHeight();
        startRect.set(sx - startRadius * 1.6f, sy - startRadius, sx + startRadius * 1.6f, sy + startRadius);
        paint.setStyle(Paint.Style.FILL); paint.setColor(Color.argb((int)(layout.opacity * 220), 55, 60, 72));
        canvas.drawRoundRect(startRect, startRadius, startRadius, paint);
        paint.setColor(Color.WHITE); paint.setTextSize(startRadius * 0.72f);
        canvas.drawText("START", sx, sy - (paint.ascent() + paint.descent()) / 2, paint);
        if (editing) {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2 * density);
            paint.setColor(Color.argb(220, 80, 200, 255));
            canvas.drawCircle(px, py, padRadius + 4 * density, paint);
            canvas.drawCircle(layout.faceX * getWidth(), layout.faceY * getHeight(), faceRadius * 3.5f, paint);
            canvas.drawRoundRect(startRect, startRadius, startRadius, paint);
        }
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!interactive && !editing) return false;
        int action = event.getActionMasked();
        boolean emptyTap = false;
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            int index = event.getActionIndex();
            if (pointerCount < pointerIds.length) {
                pointerIds[pointerCount] = event.getPointerId(index);
                pointerX[pointerCount] = event.getX(index); pointerY[pointerCount] = event.getY(index);
                // pointerCount is the count BEFORE inserting this pointer.
                // The first finger must select a drag group; checking == 1 here
                // made layout editing silently ignore every drag.
                if (editing && pointerCount == 0) dragGroup = groupAt(pointerX[0], pointerY[0]);
                pointerCount++;
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < event.getPointerCount(); ++i) {
                int slot = slot(event.getPointerId(i));
                if (slot >= 0) { pointerX[slot] = event.getX(i); pointerY[slot] = event.getY(i); }
            }
            if (editing && dragGroup >= 0 && pointerCount > 0) {
                layout.move(dragGroup, pointerX[0] / getWidth(), pointerY[0] / getHeight());
                constrainLayout();
            }
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            removeSlot(slot(event.getPointerId(event.getActionIndex())));
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            emptyTap = action == MotionEvent.ACTION_UP && !editing && pointerCount == 1 &&
                    hitMask(pointerX[0], pointerY[0]) == 0 && pointerY[0] < getHeight() * 0.20f;
            clearPointers(); dragGroup = -1;
        }
        if (!editing) {
            int mask = 0;
            for (int i = 0; i < pointerCount; ++i) mask |= hitMask(pointerX[i], pointerY[i]);
            emit(SegaButtons.cancelOpposites(mask));
        }
        invalidate();
        if (emptyTap && emptyTapListener != null) emptyTapListener.run();
        return true;
    }
    private int slot(int id) { for (int i = 0; i < pointerCount; ++i) if (pointerIds[i] == id) return i; return -1; }
    private void removeSlot(int slot) {
        if (slot < 0) return;
        for (int i = slot; i < pointerCount - 1; ++i) {
            pointerIds[i] = pointerIds[i + 1]; pointerX[i] = pointerX[i + 1]; pointerY[i] = pointerY[i + 1];
        }
        pointerCount--; pointerIds[pointerCount] = -1;
    }
    private void clearPointers() { pointerCount = 0; Arrays.fill(pointerIds, -1); }
    private int groupAt(float x, float y) {
        if (Math.hypot(x - layout.padX * getWidth(), y - layout.padY * getHeight()) <= padRadius * 1.15f) return 0;
        if (Math.abs(x - layout.faceX * getWidth()) <= faceRadius * 3.6f &&
                Math.abs(y - layout.faceY * getHeight()) <= rowGap * 0.5f + faceRadius * 1.3f) return 1;
        if (startRect.contains(x, y)) return 2;
        return -1;
    }
    public int hitMask(float x, float y) {
        int mask = 0;
        float dx = x - layout.padX * getWidth(), dy = y - layout.padY * getHeight();
        if (Math.hypot(dx, dy) <= padRadius * 1.08f) {
            float dead = padRadius * 0.18f;
            if (Math.abs(dx) > Math.abs(dy)) { if (Math.abs(dx) > dead) mask |= 1 << (dx < 0 ? SegaButtons.LEFT : SegaButtons.RIGHT); }
            else if (Math.abs(dy) > dead) mask |= 1 << (dy < 0 ? SegaButtons.UP : SegaButtons.DOWN);
        }
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
