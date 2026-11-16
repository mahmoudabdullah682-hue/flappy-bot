package com.mahmoud.flappybot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;

/** Performs the taps and shows the small floating on/off button. */
public class TapService extends AccessibilityService {
    static volatile TapService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView button;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        if (CaptureService.running) showButton(true);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        Bot.active = false;
        removeButton();
        return super.onUnbind(intent);
    }

    void tap(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 8))
                .build();
        dispatchGesture(g, null, null);
    }

    void showButton(final boolean show) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (show) addButton();
                else removeButton();
            }
        });
    }

    private void addButton() {
        if (button != null) return;
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        TextView b = new TextView(this);
        b.setTextSize(22);
        b.setTextColor(0xFF00E5FF);
        b.setBackgroundColor(0xCC101030);
        int pad = (int) (10 * dm.density);
        b.setPadding(pad, pad, pad, pad);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Bot.reset();
                Bot.active = !Bot.active;
                updateButton();
            }
        });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.START | Gravity.BOTTOM;
        lp.x = 0;
        lp.y = (int) (dm.heightPixels * 0.12f);
        try {
            wm.addView(b, lp);
            button = b;
            updateButton();
        } catch (RuntimeException e) {
            button = null;
        }
    }

    private void updateButton() {
        if (button != null) button.setText(Bot.active ? "⏸" : "▶");
    }

    private void removeButton() {
        if (button == null) return;
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(button);
        } catch (RuntimeException ignored) {
        }
        button = null;
    }
}
