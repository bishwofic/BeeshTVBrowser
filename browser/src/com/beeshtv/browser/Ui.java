package com.beeshtv.browser;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/**
 * The browser's look: a living room at night (or, in light mode, by day). Neutrals with a trace of red
 * in them, text in three strengths, frosted surfaces (a faint fill and a hairline edge, no glow), and
 * one accent, the red of a TV's standby light, kept for the current item and the loading line.
 *
 * The colours aren't constants: theme() sets them for dark or light before any view is made.
 */
final class Ui {
    private Ui() { }

    static boolean light;
    // Neutral ramp.
    static int BG, SURFACE, TEXT, TEXT_2, TEXT_3;
    /** Separator lines, and the matte frosted fills (no outlines), as fractions of the text colour. */
    static int HAIRLINE, FROST, HOVER, SELECTED;
    /** The standby light. */
    static final int RED = 0xFFE5484D;

    static { theme(false); }

    static void theme(boolean isLight) {
        light = isLight;
        if (light) {
            BG = 0xFFF5F2F1; SURFACE = 0xFFFFFFFF;
            TEXT = 0xFF1C1717; TEXT_2 = 0xB31C1717; TEXT_3 = 0x7A1C1717;
            HAIRLINE = 0x1A000000; FROST = 0x0F1C1717; HOVER = 0x1C1C1717; SELECTED = 0x141C1717;
        } else {
            BG = 0xFF0B0A0A; SURFACE = 0xFF151313;
            TEXT = 0xFFF4F1F0; TEXT_2 = 0xB3F4F1F0; TEXT_3 = 0x73F4F1F0;
            HAIRLINE = 0x17FFFFFF; FROST = 0x14F4F1F0; HOVER = 0x24F4F1F0; SELECTED = 0x1CF4F1F0;
        }
    }

    /** Dialogs follow the theme. */
    static int dialog() {
        return light ? android.R.style.Theme_DeviceDefault_Light_Dialog_Alert : android.R.style.Theme_DeviceDefault_Dialog_Alert;
    }

    /** A colour at a given opacity (0 to 1). */
    static int alpha(int c, float a) { return (Math.round(a * 255) << 24) | (c & 0xFFFFFF); }

    /** Perceived brightness, 0 (black) to 1 (white). */
    static float luminance(int c) {
        return (0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255)) / 255f;
    }

    /** a moved toward b by t. */
    static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    /** "#rrggbb" of a colour, for the pages the browser writes itself. */
    static String css(int c) { return String.format("#%06x", c & 0xFFFFFF); }

    private static final Map<Integer, Typeface> FACES = new HashMap<>();

    /** Schibsted Grotesk at an exact weight; the system sans if the file fails to load. */
    static Typeface face(Context c, int weight) {
        Typeface t = FACES.get(weight);
        if (t != null) return t;
        try {
            t = new Typeface.Builder(c.getAssets(), "fonts/SchibstedGrotesk.ttf")
                    .setFontVariationSettings("'wght' " + weight).build();
        } catch (RuntimeException e) {
            t = null;
        }
        if (t == null) t = Typeface.DEFAULT;
        FACES.put(weight, t);
        return t;
    }

    static TextView text(Context c, float sp, int weight, int color) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(face(c, weight));
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Figures that keep one width, for clocks and counts. */
    static TextView figures(Context c, float sp, int weight, int color) {
        TextView t = text(c, sp, weight, color);
        t.setFontFeatureSettings("tnum");
        return t;
    }

    /** White text over a photo gets a soft shadow so it holds up on bright skies (dark mode only). */
    static TextView onPhoto(TextView t) {
        if (!light) t.setShadowLayer(dp(t.getContext(), 8), 0, dp(t.getContext(), 1), 0x80000000);
        return t;
    }

    static Drawable icon(Context c, int res, int color, int sizeDp) {
        Drawable d = c.getDrawable(res).mutate();
        d.setTint(color);
        d.setBounds(0, 0, dp(c, sizeDp), dp(c, sizeDp));
        return d;
    }

    /** A frosted panel: a soft matte fill, no outline. */
    static GradientDrawable frost(Context c, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c, radiusDp));
        g.setColor(FROST);
        return g;
    }

    /**
     * Anything you can point at and click: nothing at rest, a frosted step when the pointer is over it
     * (hovered) or the remote is on it (focused), a fainter step when it's the current one (selected).
     * Pass a resting fill for things that need a shape even at rest, like a field.
     */
    static StateListDrawable surface(Context c, float radiusDp, int rest) {
        return surface(c, radiusDp, rest, HOVER, SELECTED);
    }

    /** The same, with its own hover and selected fills (for the bar, which takes the website's colour). */
    static StateListDrawable surface(Context c, float radiusDp, int rest, int hover, int selected) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, fill(c, radiusDp, hover));
        s.addState(new int[]{android.R.attr.state_hovered}, fill(c, radiusDp, hover));
        s.addState(new int[]{android.R.attr.state_focused}, fill(c, radiusDp, hover));
        s.addState(new int[]{android.R.attr.state_activated}, fill(c, radiusDp, hover));
        s.addState(new int[]{android.R.attr.state_selected}, fill(c, radiusDp, selected));
        s.addState(new int[]{}, fill(c, radiusDp, rest));
        s.setEnterFadeDuration(90);
        s.setExitFadeDuration(120);
        return s;
    }

    private static GradientDrawable fill(Context c, float radiusDp, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c, radiusDp));
        g.setColor(color);
        return g;
    }

    /** The plain background, with the faint red of a standby light low in one corner. */
    static final class Night extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        @Override protected void onBoundsChange(Rect b) {
            float r = Math.max(b.width(), b.height()) * 0.7f;
            paint.setShader(new RadialGradient(b.left + b.width() * 0.08f, b.bottom + b.height() * 0.1f, r,
                    light ? new int[]{0x1FE5484D, 0x0AE5484D, 0x00E5484D} : new int[]{0x33B8262E, 0x14B8262E, 0x00B8262E},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        }

        @Override public void draw(Canvas c) {
            c.drawColor(BG);
            c.drawRect(getBounds(), paint);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    /**
     * The clickable view under a point on screen, topmost first, or null. Views that are hidden,
     * see-through or not clickable are passed over; a field counts as clickable.
     */
    static View hit(ViewGroup g, float x, float y) {
        int[] at = new int[2];
        for (int i = g.getChildCount() - 1; i >= 0; i--) {
            View c = g.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE || c.getAlpha() == 0f) continue;
            c.getLocationInWindow(at);
            if (x < at[0] || y < at[1] || x >= at[0] + c.getWidth() || y >= at[1] + c.getHeight()) continue;
            if (c instanceof ViewGroup) {
                View d = hit((ViewGroup) c, x, y);
                if (d != null) return d;
            }
            if (c.isClickable() || c instanceof EditText) return c;
        }
        return null;
    }

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }
}
