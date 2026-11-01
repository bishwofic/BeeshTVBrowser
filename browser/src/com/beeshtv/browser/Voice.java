package com.beeshtv.browser;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * Voice typing in the browser's own look: a small frosted bar at the bottom says it's listening, shows
 * how loud you are and the words as they come, then hands the text over. The TV's own speech
 * recogniser does the listening (through the remote's microphone); no search screen opens.
 */
final class Voice extends FrameLayout {
    private final Frost bar;
    private final ImageView mic;
    private final View level;
    private final TextView words;
    private SpeechRecognizer recognizer;
    private Consumer<String> done;

    Voice(Context c) {
        super(c);
        bar = new Frost(c);
        float r = Ui.dp(c, 28);
        bar.corners(r, r, r, r);
        bar.setElevation(Ui.dp(c, 10));
        bar.setClickable(true);
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 22), 0);
        FrameLayout dot = new FrameLayout(c);
        // A disc behind the mic grows with your voice.
        level = new View(c);
        GradientDrawable lv = new GradientDrawable();
        lv.setShape(GradientDrawable.OVAL);
        lv.setColor(Ui.alpha(Keyboard.LIME, 0.35f));
        level.setBackground(lv);
        dot.addView(level, new LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36), Gravity.CENTER));
        mic = new ImageView(c);
        mic.setImageResource(R.drawable.ic_mic);
        mic.setImageTintList(ColorStateList.valueOf(Keyboard.ON_LIME));
        mic.setPadding(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8));
        GradientDrawable md = new GradientDrawable();
        md.setShape(GradientDrawable.OVAL);
        md.setColor(Keyboard.LIME);
        mic.setBackground(md);
        dot.addView(mic, new LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36), Gravity.CENTER));
        row.addView(dot, new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));
        words = Ui.text(c, 16, 400, Ui.TEXT);
        words.setEllipsize(android.text.TextUtils.TruncateAt.START);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1f);
        wp.leftMargin = Ui.dp(c, 14);
        row.addView(words, wp);
        bar.addView(row, new LayoutParams(-1, -1));
        LayoutParams bp = new LayoutParams(Math.min(Ui.dp(c, 560), getResources().getDisplayMetrics().widthPixels - Ui.dp(c, 64)),
                Ui.dp(c, 60), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bp.bottomMargin = Ui.dp(c, 32);
        addView(bar, bp);
        setClickable(true);
        setOnClickListener(v -> stop());
        setVisibility(GONE);
    }

    static boolean available(Context c) { return SpeechRecognizer.isRecognitionAvailable(c); }

    boolean listening() { return getVisibility() == VISIBLE; }

    /** Starts listening over the given (blurred) screen; the words go to done unless cancelled. */
    void listen(android.graphics.Bitmap backdrop, int bottom, Consumer<String> done) {
        this.done = done;
        // Above the keyboard when it's up, else near the bottom edge.
        ((LayoutParams) bar.getLayoutParams()).bottomMargin = bottom;
        bar.requestLayout();
        bar.setBackdrop(backdrop);
        bar.tint();
        words.setText("Listening");
        words.setTextColor(Ui.TEXT_2);
        setVisibility(VISIBLE);
        bar.setAlpha(0f);
        bar.setTranslationY(Ui.dp(getContext(), 16));
        bar.animate().alpha(1f).translationY(0).setDuration(180).start();
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(getContext());
            recognizer.setRecognitionListener(listener);
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getContext().getPackageName());
        recognizer.startListening(i);
    }

    /** Stops without using what was heard (Back, or clicking away). */
    void stop() {
        if (recognizer != null) recognizer.cancel();
        hide();
    }

    void release() {
        if (recognizer != null) recognizer.destroy();
        recognizer = null;
    }

    private void hide() {
        done = null;
        bar.animate().alpha(0f).setDuration(140).withEndAction(() -> setVisibility(GONE)).start();
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle b) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float db) {
            // Roughly -2 (quiet) to 10 (loud) dB: the disc grows up to half again its size.
            float s = 1f + Math.max(0f, Math.min(1f, (db + 2f) / 12f)) * 0.55f;
            level.setScaleX(s);
            level.setScaleY(s);
        }
        @Override public void onBufferReceived(byte[] b) { }
        @Override public void onEndOfSpeech() { words.setTextColor(Ui.TEXT_2); }
        @Override public void onError(int error) {
            words.setTextColor(Ui.TEXT_2);
            words.setText(error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ? "Microphone not allowed"
                    : error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    ? "Didn't catch that" : "Voice typing isn't working right now");
            done = null;
            postDelayed(Voice.this::hide, 1400);
        }
        @Override public void onResults(Bundle b) {
            ArrayList<String> said = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            Consumer<String> d = done;
            hide();
            if (d != null && said != null && !said.isEmpty()) d.accept(said.get(0));
        }
        @Override public void onPartialResults(Bundle b) {
            ArrayList<String> said = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (said != null && !said.isEmpty() && !said.get(0).isEmpty()) {
                words.setText(said.get(0));
                words.setTextColor(Ui.TEXT);
            }
        }
        @Override public void onEvent(int type, Bundle b) { }
    };
}
