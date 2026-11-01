package com.beeshtv.browser;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;

/**
 * The on-screen pointer, moved with the D-pad. Holding a direction starts slow, for precise aim, and
 * speeds up over about half a second. Pushing against an edge reports how far it would have gone, so
 * the page can scroll instead. It fades out when left alone and comes back on the next press.
 *
 * It's a small view the size of the arrow that moves by translation, so moving it never redraws
 * anything: the page and any video under it keep their full frame rate.
 */
final class CursorView extends View {
    interface Listener {
        void onMove(float x, float y);
        /** The pointer is held against an edge; scroll the page by this much. */
        void onEdge(float dx, float dy);
    }

    static final int LEFT = 1, RIGHT = 2, UP = 4, DOWN = 8;
    private static final long FADE_AFTER_MS = 6000;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private final float minSpeed, maxSpeed, pad;
    private Listener listener;
    private float x = -1, y = -1;
    private int held;
    private long heldSince, wokeAt;

    CursorView(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        minSpeed = 4 * d;
        maxSpeed = 26 * d;
        pad = 6 * d;
        float s = 22 * d;
        fill.setColor(0xFFFFFFFF);
        edge.setColor(0xD9000000);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1.6f * d);
        edge.setStrokeJoin(Paint.Join.ROUND);
        // A classic arrow with its tip at (pad, pad), the pointer's hot spot.
        arrow.moveTo(0, 0);
        arrow.lineTo(0, s);
        arrow.lineTo(s * 0.27f, s * 0.75f);
        arrow.lineTo(s * 0.45f, s * 1.08f);
        arrow.lineTo(s * 0.6f, s * 1.0f);
        arrow.lineTo(s * 0.43f, s * 0.68f);
        arrow.lineTo(s * 0.75f, s * 0.68f);
        arrow.close();
        arrow.offset(pad, pad);
        // A soft drop shadow so it shows on white pages too. Path shadows need software drawing, which
        // is cheap at this size and only happens once: moving is a translation.
        fill.setShadowLayer(3 * d, 0, 1.2f * d, 0x66000000);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        // Hidden until the first press puts it in the middle of the screen.
        setAlpha(0f);
    }

    /** The arrow plus room for its shadow. */
    static int sizePx(Context c) {
        return Math.round(36 * c.getResources().getDisplayMetrics().density);
    }

    void setListener(Listener l) { listener = l; }
    float pointerX() { return x; }
    float pointerY() { return y; }

    private int areaW() { return ((View) getParent()).getWidth(); }
    private int areaH() { return ((View) getParent()).getHeight(); }

    /** A direction key went down (true) or up (false). */
    void press(int dir, boolean down) {
        if (!placed()) return;
        if (down) {
            if ((held & dir) == 0) {
                if (held == 0) heldSince = SystemClock.uptimeMillis();
                held |= dir;
                wake();
                // A tap moves one small step at once, for fine aim; holding then speeds up.
                if (heldSince == SystemClock.uptimeMillis() || held == dir) step(dir, minSpeed * 2);
                removeCallbacks(tick);
                postOnAnimation(tick);
            }
        } else {
            held &= ~dir;
        }
    }

    private void step(int dir, float by) {
        float dx = (dir & RIGHT) != 0 ? by : (dir & LEFT) != 0 ? -by : 0;
        float dy = (dir & DOWN) != 0 ? by : (dir & UP) != 0 ? -by : 0;
        float nx = Math.max(0, Math.min(areaW() - 1, x + dx)), ny = Math.max(0, Math.min(areaH() - 1, y + dy));
        if (nx == x && ny == y) {
            if (listener != null) listener.onEdge(dx * 4, dy * 4);
            return;
        }
        moveTo(nx, ny);
        if (listener != null) listener.onMove(x, y);
    }

    /** Puts the pointer in the middle the first time it's needed, once the screen has a size. */
    private boolean placed() {
        if (x >= 0) return true;
        if (areaW() == 0) return false;
        moveTo(areaW() / 2f, areaH() / 2f);
        return true;
    }

    void moveTo(float nx, float ny) {
        x = nx;
        y = ny;
        setTranslationX(x - pad);
        setTranslationY(y - pad);
    }

    /** Shows the pointer again and restarts the fade timer (at most a few times a second). */
    void wake() {
        if (!placed()) return;
        long now = SystemClock.uptimeMillis();
        if (getAlpha() == 1f && now - wokeAt < 300) return;
        wokeAt = now;
        animate().cancel();
        setAlpha(1f);
        removeCallbacks(fade);
        postDelayed(fade, FADE_AFTER_MS);
    }

    private final Runnable fade = () -> animate().alpha(0f).setDuration(400).start();

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (held == 0) return;
            float t = Math.min(1f, (SystemClock.uptimeMillis() - heldSince) / 550f);
            float speed = minSpeed + (maxSpeed - minSpeed) * t * t;
            float dx = ((held & RIGHT) != 0 ? speed : 0) - ((held & LEFT) != 0 ? speed : 0);
            float dy = ((held & DOWN) != 0 ? speed : 0) - ((held & UP) != 0 ? speed : 0);
            float nx = Math.max(0, Math.min(areaW() - 1, x + dx));
            float ny = Math.max(0, Math.min(areaH() - 1, y + dy));
            float overX = x + dx - nx, overY = y + dy - ny;
            if (nx != x || ny != y) {
                moveTo(nx, ny);
                if (listener != null) listener.onMove(x, y);
            }
            if ((overX != 0 || overY != 0) && listener != null) listener.onEdge(overX * 1.6f, overY * 1.6f);
            wake();
            postOnAnimation(this);
        }
    };

    @Override
    protected void onDraw(Canvas c) {
        c.drawPath(arrow, fill);
        c.drawPath(arrow, edge);
    }
}
