package com.mahmoud.flappybot;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bot.load(this);

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("FlappyBot (build 3)");
        title.setTextSize(26);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(0, pad / 2, 0, pad);
        root.addView(status);

        Button acc = new Button(this);
        acc.setText("1) تفعيل صلاحية الضغط (Accessibility)");
        acc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        root.addView(acc);

        Button start = new Button(this);
        start.setText("2) تشغيل (اختار: الشاشة كلها)");
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (TapService.instance == null) {
                    Toast.makeText(MainActivity.this,
                            "فعّل صلاحية Accessibility الأول", Toast.LENGTH_LONG).show();
                    return;
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(
                            new String[]{"android.permission.POST_NOTIFICATIONS"}, 2);
                }
                MediaProjectionManager mpm =
                        (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
            }
        });
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("إيقاف");
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopService(new Intent(MainActivity.this, CaptureService.class));
                status.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        refresh();
                    }
                }, 300);
            }
        });
        root.addView(stop);

        TextView how = new TextView(this);
        how.setPadding(0, pad, 0, pad);
        how.setText("بعد التشغيل افتح اللعبة في تيك توك. هتلاقي زرار ▶ صغير على شمال الشاشة: "
                + "دوس عليه عشان البوت يبدأ، ودوس تاني عشان يقف.");
        root.addView(how);

        addSlider(root, "مكان الطيران جوه الفتحة (قلله لو بيخبط تحت، زوده لو بيخبط فوق)",
                0.30f, 0.90f, Bot.aim, new Setter() {
                    @Override
                    public void set(float v) {
                        Bot.aim = v;
                    }
                });
        addSlider(root, "تعويض التأخير بالثانية (زوده لو بيضغط متأخر)",
                0.00f, 0.20f, Bot.lookahead, new Setter() {
                    @Override
                    public void set(float v) {
                        Bot.lookahead = v;
                    }
                });
        addSlider(root, "أقل وقت بين ضغطتين بالثانية (زوده لو بيطلع زيادة)",
                0.05f, 0.30f, Bot.minTapGap, new Setter() {
                    @Override
                    public void set(float v) {
                        Bot.minTapGap = v;
                    }
                });

        TextView credit = new TextView(this);
        credit.setPadding(0, pad * 2, 0, 0);
        credit.setText("Created by Eng. Mahmoud Abdullah v1.0");
        root.addView(credit);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private interface Setter {
        void set(float v);
    }

    private void addSlider(LinearLayout root, final String label, final float min,
                           final float max, float value, final Setter setter) {
        final TextView t = new TextView(this);
        t.setText(label + ": " + String.format(java.util.Locale.US, "%.2f", value));
        root.addView(t);
        SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        bar.setProgress(Math.round((value - min) / (max - min) * 100));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                float v = min + (max - min) * progress / 100f;
                setter.set(v);
                t.setText(label + ": " + String.format(java.util.Locale.US, "%.2f", v));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                Bot.save(MainActivity.this);
            }
        });
        root.addView(bar);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        String a = TapService.instance != null ? "مفعّلة ✔" : "مش مفعّلة ✘";
        String c = CaptureService.running ? "شغال ✔" : "واقف";
        status.setText("صلاحية الضغط: " + a + "\nمراقبة الشاشة: " + c);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "لازم توافق على تسجيل الشاشة", Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(this, CaptureService.class)
                .putExtra(CaptureService.EXTRA_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_DATA, data);
        startForegroundService(i);
        status.postDelayed(new Runnable() {
            @Override
            public void run() {
                refresh();
            }
        }, 500);
    }
}
