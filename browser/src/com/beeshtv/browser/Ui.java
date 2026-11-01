package com.beeshtv.browser;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/** Colours, type and small view helpers, matching BeeshTV Launcher. */
final class Ui {
    private Ui() { }

    // White on glass, as on BeeshTV's Home.
    static final int TEXT = 0xFFFFFFFF, TEXT_2 = 0xCCFFFFFF, TEXT_3 = 0x99FFFFFF;
    static final int FOCUS = 0x3DFFFFFF, CURRENT = 0x1FFFFFFF;
    static final int GLASS = 0xEB151518, AMBER = 0xFFFFB01F;
    /** Frosted panels over the home page photo. */
    static final int HOME_GLASS = 0x29FFFFFF, HOME_EDGE = 0x33FFFFFF;

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

    /** White text on a photo gets a soft shadow so it holds up on bright skies. */
    static TextView onPhoto(TextView t) {
        t.setShadowLayer(dp(t.getContext(), 8), 0, dp(t.getContext(), 1), 0x80000000);
        return t;
    }

    static Drawable icon(Context c, int res, int color, int sizeDp) {
        Drawable d = c.getDrawable(res).mutate();
        d.setTint(color);
        d.setBounds(0, 0, dp(c, sizeDp), dp(c, sizeDp));
        return d;
    }

    /** A rounded glass fill with a faint edge, for panels over the photo. */
    static GradientDrawable glass(Context c, float radiusDp, int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c, radiusDp));
        g.setColor(fill);
        g.setStroke(Math.max(1, dp(c, 0.75f)), HOME_EDGE);
        return g;
    }

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }
}
