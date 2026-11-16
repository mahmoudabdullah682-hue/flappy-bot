package com.mahmoud.flappybot;

import android.content.Context;
import android.content.SharedPreferences;

/** Reads a (down-scaled) screen frame and decides when to tap. */
final class Bot {
    private Bot() {}

    static volatile boolean active = false;

    // Tunable from the app screen.
    static volatile float aim = 0.72f;        // where to fly inside the gap: 0 = top edge, 1 = bottom edge
    static volatile float lookahead = 0.07f;  // seconds of delay to compensate for
    static volatile float minTapGap = 0.11f;  // minimum seconds between taps

    static final float PLAYER_X = 0.25f;      // player's horizontal position (fraction of width)
    static final float PLAYER_BAND = 0.07f;   // half-width of the strip searched for the player
    static final float IDLE_TARGET = 0.48f;   // height to hold when no obstacle is visible

    private static float prevY = -1f;
    private static double prevT;
    private static double lastTap;
    private static float lastGapTop = -1f;
    private static float lastGapBottom;

    // Jump height is learned while playing (fraction of screen height).
    private static float jumpRise = 0.13f;
    private static boolean measuring = false;
    private static float tapY, minSinceTap;

    private static int[] rowGrey = new int[0];
    private static int[] colGreen = new int[0];
    private static boolean[] obst = new boolean[0];

    static void load(Context c) {
        SharedPreferences p = c.getSharedPreferences("bot", Context.MODE_PRIVATE);
        aim = p.getFloat("aim2", 0.72f);
        lookahead = p.getFloat("lookahead", 0.07f);
        minTapGap = p.getFloat("minTapGap", 0.11f);
    }

    static void save(Context c) {
        c.getSharedPreferences("bot", Context.MODE_PRIVATE).edit()
                .putFloat("aim2", aim)
                .putFloat("lookahead", lookahead)
                .putFloat("minTapGap", minTapGap)
                .apply();
    }

    static void reset() {
        prevY = -1f;
        lastGapTop = -1f;
        measuring = false;
    }

    /**
     * @param px RGBA_8888 pixels
     * @return true when the bot should tap now
     */
    static boolean step(byte[] px, int w, int h, int rowStride, int pixelStride, long nowNs) {
        if (rowGrey.length != h) rowGrey = new int[h];
        if (colGreen.length != w) colGreen = new int[w];
        if (obst.length != w * h) obst = new boolean[w * h];
        java.util.Arrays.fill(rowGrey, 0);
        java.util.Arrays.fill(colGreen, 0);

        int x0 = Math.max(0, (int) (w * (PLAYER_X - PLAYER_BAND)));
        int x1 = Math.min(w, (int) (w * (PLAYER_X + PLAYER_BAND)));
        int top = (int) (h * 0.06f);
        int bottom = (int) (h * 0.96f);

        for (int y = 0; y < h; y++) {
            int base = y * rowStride;
            int o = y * w;
            for (int x = 0; x < w; x++) {
                int i = base + x * pixelStride;
                if (i + 2 >= px.length) {
                    obst[o + x] = false;
                    continue;
                }
                int r = px[i] & 0xFF;
                int g = px[i + 1] & 0xFF;
                int b = px[i + 2] & 0xFF;
                boolean green = g > r + 12 && g > b + 12;
                boolean brown = r > b + 30 && r >= g && r > 90;
                if (green) colGreen[x]++;
                obst[o + x] = green || brown;
                if (x >= x0 && x < x1 && y >= top && y < bottom) {
                    int mx = Math.max(r, Math.max(g, b));
                    int mn = Math.min(r, Math.min(g, b));
                    if (mn > 175 && mx - mn < 22) rowGrey[y]++;
                }
            }
        }

        // ---- player: bright grey/white disc
        int max = 0;
        for (int y = top; y < bottom; y++) if (rowGrey[y] > max) max = rowGrey[y];
        if (max <= (x1 - x0) * 0.35f) {
            prevY = -1f;
            return false;
        }
        long sum = 0;
        int n = 0;
        for (int y = top; y < bottom; y++) {
            if (rowGrey[y] > max * 0.5f) {
                sum += y;
                n++;
            }
        }
        float playerY = (float) sum / n / h;
        float radius = Math.min(0.08f, Math.max(0.03f, n * 0.55f / h));

        // ---- nearest obstacle and its gap
        float gapTop = -1f, gapBottom = -1f;
        int leftLimit = Math.max(0, (int) (w * (PLAYER_X - PLAYER_BAND - 0.02f)));
        float colThr = h * 0.18f;
        int first = -1;
        for (int x = leftLimit; x < w; x++) {
            if (colGreen[x] > colThr) {
                first = x;
                break;
            }
        }
        if (first >= 0) {
            int tol = Math.max(2, (int) (w * 0.02f));
            int end = first;
            for (int x = first + 1; x < w; x++) {
                if (colGreen[x] > colThr) {
                    if (x - end > tol) break;
                    end = x;
                }
            }
            int width = end - first + 1;
            if (width >= 2) {
                int ya = (int) (h * 0.04f);
                int bestStart = -1, bestLen = 0, runStart = -1;
                for (int y = ya; y <= bottom; y++) {
                    boolean free = false;
                    if (y < bottom) {
                        int c = 0;
                        int o = y * w;
                        for (int x = first; x <= end; x++) if (obst[o + x]) c++;
                        free = c < width * 0.30f;
                    }
                    if (free) {
                        if (runStart < 0) runStart = y;
                    } else if (runStart >= 0) {
                        if (y - runStart > bestLen) {
                            bestLen = y - runStart;
                            bestStart = runStart;
                        }
                        runStart = -1;
                    }
                }
                if (bestLen > h * 0.08f) {
                    gapTop = (float) bestStart / h;
                    gapBottom = (float) (bestStart + bestLen) / h;
                }
            }
        }

        // ---- control
        double now = nowNs / 1e9;
        float vy = 0f;
        if (prevY >= 0f && now > prevT) vy = (float) ((playerY - prevY) / (now - prevT));
        prevY = playerY;
        prevT = now;

        // learn how high one tap lifts the player
        if (measuring) {
            if (playerY < minSinceTap) minSinceTap = playerY;
            if (playerY > minSinceTap + 0.01f) {
                float rise = tapY - minSinceTap;
                if (rise > 0.03f && rise < 0.40f) jumpRise = 0.7f * jumpRise + 0.3f * rise;
                measuring = false;
            }
        }

        float gTop, bottomEdge;
        if (gapTop >= 0f) {
            lastGapTop = gapTop;
            lastGapBottom = gapBottom;
            gTop = gapTop;
            bottomEdge = gapBottom;
        } else if (lastGapTop >= 0f) {
            gTop = lastGapTop;
            bottomEdge = lastGapBottom;
        } else {
            gTop = IDLE_TARGET - 0.15f;
            bottomEdge = IDLE_TARGET + 0.15f;
        }

        // lowest line the player may reach before it must jump
        float lowLine = bottomEdge - radius - 0.02f;
        // highest line from which a jump still stays under the top obstacle
        float safeTop = gTop + radius + jumpRise + 0.015f;
        float wanted = gTop + (bottomEdge - gTop) * aim;
        float threshold = Math.min(lowLine, Math.max(wanted, safeTop));

        float predicted = playerY + vy * lookahead;
        boolean rising = vy < -0.05f;
        boolean mustJump = predicted > lowLine;
        if (predicted > threshold && (!rising || mustJump) && now - lastTap > minTapGap) {
            lastTap = now;
            measuring = true;
            tapY = playerY;
            minSinceTap = playerY;
            return true;
        }
        return false;
    }
}
