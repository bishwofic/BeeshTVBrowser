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
    private final float minSpeed, maxSpeed, size;
    private Listener listener;
    private float x = -1, y = -1;
    private int held;
    private long heldSince;

    CursorView(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        minSpeed = 4 * d;
        maxSpeed = 26 * d;
        size = 22 * d;
        fill.setColor(0xFFFFFFFF);
        edge.setColor(0xD9000000);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1.6f * d);
        edge.setStrokeJoin(Paint.Join.ROUND);
        // A classic arrow with its tip at (0, 0).
        float s = size;
        arrow.moveTo(0, 0);
        arrow.lineTo(0, s);
        arrow.lineTo(s * 0.27f, s * 0.75f);
        arrow.lineTo(s * 0.45f, s * 1.08f);
        arrow.lineTo(s * 0.6f, s * 1.0f);
        arrow.lineTo(s * 0.43f, s * 0.68f);
        arrow.lineTo(s * 0.75f, s * 0.68f);
        arrow.close();
        // A soft drop shadow so it shows on white pages too (path shadows need software drawing).
        fill.setShadowLayer(4 * d, 0, 1.5f * d, 0x66000000);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    void setListener(Listener l) { listener = l; }
    float pointerX() { return x; }
    float pointerY() { return y; }

    /** A direction key went down (true) or up (false). */
    void press(int dir, boolean down) {
        if (x < 0) centre();
        if (down) {
            if ((held & dir) == 0) {
                if (held == 0) heldSince = SystemClock.uptimeMillis();
                held |= dir;
                wake();
                removeCallbacks(tick);
                post(tick);
            }
        } else {
            held &= ~dir;
        }
    }

    void centre() {
        x = getWidth() / 2f;
        y = getHeight() / 2f;
        invalidate();
    }

    /** Shows the pointer again and restarts the fade timer. */
    void wake() {
        if (x < 0) centre();
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
            float nx = Math.max(0, Math.min(getWidth() - 1, x + dx));
            float ny = Math.max(0, Math.min(getHeight() - 1, y + dy));
            float overX = x + dx - nx, overY = y + dy - ny;
            if (nx != x || ny != y) {
                x = nx;
                y = ny;
                invalidate();
                if (listener != null) listener.onMove(x, y);
            }
            if ((overX != 0 || overY != 0) && listener != null) listener.onEdge(overX * 1.6f, overY * 1.6f);
            wake();
            postOnAnimation(this);
        }
    };

    @Override
    protected void onDraw(Canvas c) {
        if (x < 0) return;
        c.save();
        c.translate(x, y);
        c.drawPath(arrow, fill);
        c.drawPath(arrow, edge);
        c.restore();
    }
}
