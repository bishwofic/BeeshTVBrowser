package com.beeshtv.browser;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.widget.FrameLayout;

/**
 * Frosted glass: a blurred picture of whatever is on screen behind it, kept lined up with the screen,
 * under a tint for the theme (dark or light). The picture is taken once, as the surface opens (see
 * BrowserActivity.frostedScreen), so drawing it costs nothing while it's up.
 */
final class Frost extends FrameLayout {
    private Bitmap backdrop;
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint tint = new Paint();
    private final Path shape = new Path();
    private final RectF box = new RectF(), dst = new RectF();
    private final int[] at = new int[2], now = new int[2];
    private float[] radii = new float[8];
    /** A slide moves the view without drawing it again, so follow it here and redraw. */
    private final android.view.ViewTreeObserver.OnPreDrawListener follow = () -> {
        if (backdrop == null) return true;
        getLocationInWindow(now);
        if (now[0] != at[0] || now[1] != at[1]) invalidate();
        return true;
    };

    Frost(Context c) {
        super(c);
        setWillNotDraw(false);
        tint();
    }

    /** Corner radius in px: all four, or top-left, top-right, bottom-right, bottom-left. */
    void corners(float tl, float tr, float br, float bl) {
        radii = new float[]{tl, tl, tr, tr, br, br, bl, bl};
        invalidate();
    }

    /** The theme's frost: dark glass over dark mode, milky glass over light. */
    void tint() {
        tint.setColor(Ui.light ? 0xB8F5F2F1 : 0xA6141212);
        invalidate();
    }

    /** A blurred, scaled-down picture of the whole screen. */
    void setBackdrop(Bitmap b) {
        backdrop = b;
        invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(follow);
    }

    @Override protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(follow);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        box.set(0, 0, getWidth(), getHeight());
        shape.reset();
        shape.addRoundRect(box, radii, Path.Direction.CW);
        int save = c.save();
        c.clipPath(shape);
        if (backdrop != null && !backdrop.isRecycled()) {
            getLocationInWindow(at);
            android.view.View root = getRootView();
            // The position on screen includes any slide in progress, so the picture stays still behind.
            dst.set(-at[0], -at[1], root.getWidth() - at[0], root.getHeight() - at[1]);
            c.drawBitmap(backdrop, null, dst, image);
        } else {
            c.drawColor(Ui.SURFACE);
        }
        c.drawRect(box, tint);
        c.drawRect(box, Ui.grain());
        c.restoreToCount(save);
    }
}
