package com.mahmoud.flappybot;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/** Watches the screen (MediaProjection) and asks TapService to tap. */
public class CaptureService extends Service {
    static final String EXTRA_CODE = "code";
    static final String EXTRA_DATA = "data";
    private static final String CHANNEL = "flappybot";
    private static final int SCALE = 4;

    static volatile boolean running = false;

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private byte[] buf;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || projection != null) {
            if (intent == null) stopSelf();
            return START_NOT_STICKY;
        }
        goForeground();
        Bot.load(this);
        Bot.reset();

        int code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            projection = mpm.getMediaProjection(code, data);
        } catch (RuntimeException e) {
            projection = null;
        }
        if (projection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        final int fullW = dm.widthPixels;
        final int fullH = dm.heightPixels;
        final int w = fullW / SCALE;
        final int h = fullH / SCALE;

        thread = new HandlerThread("flappybot-capture");
        thread.start();
        Handler handler = new Handler(thread.getLooper());

        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader r) {
                Image img = null;
                try {
                    img = r.acquireLatestImage();
                    if (img == null || !Bot.active) return;
                    Image.Plane plane = img.getPlanes()[0];
                    ByteBuffer bb = plane.getBuffer();
                    int n = bb.remaining();
                    if (buf == null || buf.length < n) buf = new byte[n];
                    bb.get(buf, 0, n);
                    boolean tap = Bot.step(buf, w, h, plane.getRowStride(),
                            plane.getPixelStride(), SystemClock.elapsedRealtimeNanos());
                    if (tap) {
                        TapService s = TapService.instance;
                        if (s != null) s.tap(fullW * 0.70f, fullH * 0.60f);
                    }
                } catch (RuntimeException ignored) {
                } finally {
                    if (img != null) img.close();
                }
            }
        }, handler);

        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopSelf();
            }
        }, handler);

        display = projection.createVirtualDisplay("FlappyBot", w, h, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);

        running = true;
        TapService s = TapService.instance;
        if (s != null) s.showButton(true);
        return START_NOT_STICKY;
    }

    private void goForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(
                new NotificationChannel(CHANNEL, "FlappyBot", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("FlappyBot")
                .setContentText("Watching the screen")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, n);
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        Bot.active = false;
        TapService s = TapService.instance;
        if (s != null) s.showButton(false);
        if (display != null) display.release();
        if (reader != null) reader.close();
        if (projection != null) projection.stop();
        if (thread != null) thread.quitSafely();
        display = null;
        reader = null;
        projection = null;
        super.onDestroy();
    }
}
