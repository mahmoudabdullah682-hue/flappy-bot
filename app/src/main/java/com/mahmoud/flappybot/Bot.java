package com.mahmoud.flappybot;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Reads a (down-scaled) screen frame and decides when to tap.
 *
 * Each frame it builds a column-by-column map of the free space between the
 * cactuses, then simulates the player's flight for the next fraction of a
 * second with and without a tap and picks whichever keeps more clearance.
 */
final class Bot {
    private Bot() {}

    static volatile boolean active = false;

    // Tunable from the app screen.
    static volatile float aim = 0.50f;        // 0.5 = balanced; lower = fly higher, higher = fly lower
    static volatile float lookahead = 0.10f;  // seconds between deciding to tap and the jump starting
    static volatile float minTapGap = 0.13f;  // minimum seconds between taps

    private static final float CAP = 0.050f;       // brown cap height (fraction of screen height)
    private static final float CAP_DX = 0.055f;    // cap overhang + unlit cactus edge (fraction of width)
    private static final float CEIL_DEFAULT = 0.03f;
    private static final float FLOOR_DEFAULT = 0.90f;
    private static final float CLEAR_CAP = 0.06f;  // clearance beyond this is "safe enough"
    private static final float SIM_DT = 0.02f;
    private static final int SIM_STEPS = 34;       // ~0.68 s look-ahead
    private static final int NODE_BUDGET = 5000;   // keeps the planner fast on a phone
    private static final int SAMPLES = 9;

    // learned while playing
    private static float jumpRise = 0.09f;    // how high one tap lifts the player (fraction of height)
    private static float gravity = 2.5f;      // fraction of height per second^2
    private static float scroll = 0.45f;      // obstacle speed, fraction of width per second
    private static float latency = 0.10f;     // measured delay between a tap and the jump showing up
    private static float latencySetting = -1f;
    private static boolean awaitJump;

    // tracking state
    private static float prevY = -1f;
    private static double prevT, prevMeasT, lastGood, lastTap;
    private static boolean hasVy, modelValid, tapPending, measuring;
    private static float rawVy, smoothVy, my, mv, lowY, minSinceTap;
    private static boolean risen, riseKnown;
    private static float vAtTap;
    private static int outliers;
    private static float lastRadius = 0.040f, lastPlayerX = 0.22f;
    private static float prevEdge = -1f;
    private static boolean fallSeg;
    private static double fallT0;
    private static float fallV0;

    // work buffers
    private static int[] rowGrey = new int[0];
    private static int[] colGrey = new int[0];
    private static boolean[] green = new boolean[0];
    private static float[] ceilRaw = new float[0], floorRaw = new float[0];
    private static float[] ceilP = new float[0], floorP = new float[0];
    private static float[] velT = new float[0], velB = new float[0];
    private static int[] colCnt = new int[0];
    private static final int MAXG = 8;
    private static final float[] cXa = new float[MAXG], cXb = new float[MAXG], cT = new float[MAXG],
            cB = new float[MAXG], cVT = new float[MAXG], cVB = new float[MAXG];
    private static final float[] pXa = new float[MAXG], pXb = new float[MAXG], pT = new float[MAXG],
            pB = new float[MAXG], pVT = new float[MAXG], pVB = new float[MAXG];
    private static int pG = 0;
    private static final float[] sampleU = new float[SAMPLES];
    private static final float[] sampleH = new float[SAMPLES];

    // per-search parameters
    private static int sW, sCool, nodes;
    private static float sPx, sRx, sR, sJv, sBiasTop, sBiasBot, sL;

    static void load(Context c) {
        SharedPreferences p = c.getSharedPreferences("bot", Context.MODE_PRIVATE);
        aim = p.getFloat("aim3", 0.50f);
        lookahead = p.getFloat("lookahead2", 0.10f);
        minTapGap = p.getFloat("minTapGap2", 0.13f);
        // what the bot learned about the game last time
        jumpRise = p.getFloat("learnRise", jumpRise);
        gravity = p.getFloat("learnGravity", gravity);
        scroll = p.getFloat("learnScroll", scroll);
        riseKnown = p.getBoolean("learnKnown", false);
    }

    static void saveLearned(Context c) {
        c.getSharedPreferences("bot", Context.MODE_PRIVATE).edit()
                .putFloat("learnRise", jumpRise)
                .putFloat("learnGravity", gravity)
                .putFloat("learnScroll", scroll)
                .putBoolean("learnKnown", riseKnown)
                .apply();
    }

    static void save(Context c) {
        c.getSharedPreferences("bot", Context.MODE_PRIVATE).edit()
                .putFloat("aim3", aim)
                .putFloat("lookahead2", lookahead)
                .putFloat("minTapGap2", minTapGap)
                .apply();
    }

    static void reset() {
        prevY = -1f;
        hasVy = false;
        modelValid = false;
        tapPending = false;
        measuring = false;
        awaitJump = false;
        outliers = 0;
        prevEdge = -1f;
        fallSeg = false;
        pG = 0;
    }

    /**
     * @param px RGBA_8888 pixels
     * @return true when the bot should tap now
     */
    static boolean step(byte[] px, int w, int h, int rowStride, int pixelStride, long nowNs) {
        if (rowGrey.length != h) rowGrey = new int[h];
        if (colGrey.length != w) {
            colGrey = new int[w];
            ceilRaw = new float[w];
            floorRaw = new float[w];
            ceilP = new float[w];
            floorP = new float[w];
            velT = new float[w];
            velB = new float[w];
            colCnt = new int[w];
        }
        if (green.length != w * h) green = new boolean[w * h];
        java.util.Arrays.fill(rowGrey, 0);
        java.util.Arrays.fill(colGrey, 0);

        int xMax = (int) (w * 0.70f);
        int top = (int) (h * 0.06f);
        int bottom = (int) (h * 0.96f);
        int total = 0;

        for (int y = 0; y < h; y++) {
            int base = y * rowStride;
            int o = y * w;
            for (int x = 0; x < w; x++) {
                int i = base + x * pixelStride;
                if (i + 2 >= px.length) {
                    green[o + x] = false;
                    continue;
                }
                int r = px[i] & 0xFF;
                int g = px[i + 1] & 0xFF;
                int b = px[i + 2] & 0xFF;
                // cactus body (night and day); b < 200 rejects the pale day-time haze
                green[o + x] = g > r + 12 && g > b + 12 && b < 200;
                if (x < xMax && y >= top && y < bottom) {
                    // neutral light grey = the player's disc
                    int mx = Math.max(r, Math.max(g, b));
                    int mn = Math.min(r, Math.min(g, b));
                    if (mx - mn <= 10 && mn >= 185 && mn <= 245) {
                        rowGrey[y]++;
                        colGrey[x]++;
                        total++;
                    }
                }
            }
        }

        // ---- player (only trusted when the grey blob has the size of the disc;
        //      while the background fades between night and day it can turn grey too)
        int max = 0;
        for (int y = top; y < bottom; y++) if (rowGrey[y] > max) max = rowGrey[y];
        boolean reliable = max > 0 && total > w * h * 0.004f && total < w * h * 0.025f;
        float measY = -1f;
        if (reliable) {
            double sum = 0, wsum = 0;
            int n = 0;
            for (int y = top; y < bottom; y++) {
                if (rowGrey[y] > max * 0.5f) n++;
                if (rowGrey[y] > max * 0.25f) {
                    sum += (y + 0.5) * rowGrey[y];
                    wsum += rowGrey[y];
                }
            }
            if (n < h * 0.035f || n > h * 0.115f) {
                reliable = false;
            } else {
                measY = (float) (sum / wsum / h);
                lastRadius = Math.min(0.06f, Math.max(0.03f, n * 0.58f / h));
                int maxC = 0;
                for (int x = 0; x < xMax; x++) if (colGrey[x] > maxC) maxC = colGrey[x];
                long sumX = 0;
                int nX = 0;
                for (int x = 0; x < xMax; x++) {
                    if (colGrey[x] > maxC * 0.5f) {
                        sumX += x;
                        nX++;
                    }
                }
                lastPlayerX = (float) sumX / nX / w;
            }
        }
        float radius = lastRadius;
        float playerX = lastPlayerX;

        // ---- cactus columns (solid vertical runs of green only)
        int minCount = (int) (h * 0.04f);
        for (int x = 0; x < w; x++) {
            int cnt = 0, run = 0;
            for (int y = 0; y < h; y++) {
                if (green[y * w + x]) {
                    run++;
                    if (run == 3) cnt += 3;
                    else if (run > 3) cnt++;
                } else {
                    run = 0;
                }
            }
            colCnt[x] = cnt;
        }
        java.util.Arrays.fill(ceilRaw, -1f);
        java.util.Arrays.fill(floorRaw, -1f);
        java.util.Arrays.fill(velT, 0f);
        java.util.Arrays.fill(velB, 0f);
        int dx = Math.max(1, (int) (w * CAP_DX));

        double now = nowNs / 1e9;
        float dt = (float) (now - prevT);
        prevT = now;
        boolean dtOk = dt > 0.004f && dt < 0.1f;

        // ---- group the columns into obstacles and find each one's gap
        int nG = 0;
        int tolX = Math.max(2, (int) (w * 0.03f));
        int xs = 0;
        while (xs < w) {
            if (colCnt[xs] < minCount) {
                xs++;
                continue;
            }
            int xa = xs, xb = xs, miss = 0;
            xs++;
            while (xs < w && miss <= tolX) {
                if (colCnt[xs] >= minCount) {
                    xb = xs;
                    miss = 0;
                } else {
                    miss++;
                }
                xs++;
            }
            int need = Math.min(Math.max(3, (int) (w * 0.06f)), Math.max(2, (xb - xa + 1) / 3));
            int bestS = -1, bestLen = 0, runStart = -1;
            for (int y = 0; y <= h; y++) {
                boolean free = false;
                if (y < h) {
                    int c = 0;
                    int o = y * w;
                    for (int x = xa; x <= xb && c < need; x++) if (green[o + x]) c++;
                    free = c < need;
                }
                if (free) {
                    if (runStart < 0) runStart = y;
                } else if (runStart >= 0) {
                    if (y - runStart > bestLen) {
                        bestLen = y - runStart;
                        bestS = runStart;
                    }
                    runStart = -1;
                }
            }
            if (bestLen < h * 0.08f) continue;      // no usable gap seen in this group
            int mid = bestS + bestLen / 2;
            for (int x = xa; x <= xb; x++) {
                if (colCnt[x] < minCount) continue;
                for (int y = mid - 1; y >= 2; y--) {
                    if (green[y * w + x] && green[(y - 1) * w + x] && green[(y - 2) * w + x]) {
                        ceilRaw[x] = (float) (y + 1) / h;
                        break;
                    }
                }
                for (int y = mid; y < h - 2; y++) {
                    if (green[y * w + x] && green[(y + 1) * w + x] && green[(y + 2) * w + x]) {
                        floorRaw[x] = (float) y / h;
                        break;
                    }
                }
            }
            if (nG < MAXG) {
                float fa = (float) xa / w, fb = (float) xb / w;
                float gT = (float) bestS / h, gB = (float) (bestS + bestLen) / h;
                float vT = 0f, vB = 0f;
                if (dtOk) {
                    // same obstacle as last frame? then measure how fast its gap edges move
                    for (int j = 0; j < pG; j++) {
                        float ea = pXa[j] - scroll * dt, eb = pXb[j] - scroll * dt;
                        if (Math.abs(fa - ea) < 0.04f || Math.abs(fb - eb) < 0.04f) {
                            float sT = clamp((gT - pT[j]) / dt, -0.5f, 0.5f);
                            float sB = clamp((gB - pB[j]) / dt, -0.5f, 0.5f);
                            vT = 0.75f * pVT[j] + 0.25f * sT;
                            vB = 0.75f * pVB[j] + 0.25f * sB;
                            break;
                        }
                    }
                }
                cXa[nG] = fa;
                cXb[nG] = fb;
                cT[nG] = gT;
                cB[nG] = gB;
                cVT[nG] = vT;
                cVB[nG] = vB;
                nG++;
                int a0 = Math.max(0, xa - dx), b0 = Math.min(w - 1, xb + dx);
                for (int x = a0; x <= b0; x++) {
                    velT[x] = vT;
                    velB[x] = vB;
                }
            }
        }
        pG = nG;
        for (int j = 0; j < nG; j++) {
            pXa[j] = cXa[j];
            pXb[j] = cXb[j];
            pT[j] = cT[j];
            pB[j] = cB[j];
            pVT[j] = cVT[j];
            pVB[j] = cVB[j];
        }

        // widen by the cap overhang and add the cap height
        for (int x = 0; x < w; x++) {
            float c = -1f, f = 2f;
            int a = Math.max(0, x - dx), b = Math.min(w - 1, x + dx);
            for (int k = a; k <= b; k++) {
                if (ceilRaw[k] > c) c = ceilRaw[k];
                if (floorRaw[k] >= 0f && floorRaw[k] < f) f = floorRaw[k];
            }
            ceilP[x] = c >= 0f ? Math.max(CEIL_DEFAULT, c + CAP) : CEIL_DEFAULT;
            floorP[x] = f <= 1f ? Math.min(FLOOR_DEFAULT, f - CAP) : FLOOR_DEFAULT;
        }

        if (latencySetting != lookahead) {     // slider moved (or first run): start from its value
            latencySetting = lookahead;
            latency = lookahead;
        }

        // ---- obstacle speed: follow the left edge of the first fully visible cactus
        float edge = -1f;
        int e0 = (int) (w * 0.04f);
        for (int x = Math.max(1, e0); x < w; x++) {
            boolean here = ceilRaw[x] >= 0f || floorRaw[x] >= 0f;
            boolean before = ceilRaw[x - 1] >= 0f || floorRaw[x - 1] >= 0f;
            if (here && !before) {
                edge = (float) x / w;
                break;
            }
        }
        if (edge >= 0f && prevEdge >= 0f && dtOk) {
            float moved = prevEdge - edge;
            if (moved > 0f && moved < 0.08f) {
                float sp = moved / dt;
                if (sp > 0.1f && sp < 2.5f) scroll = 0.92f * scroll + 0.08f * sp;
            }
        }
        prevEdge = edge;

        // ---- physics model of the player; it carries us through frames we cannot read
        if (modelValid) {
            if (dt > 0f && dt < 0.25f) {
                if (tapPending && now >= lastTap + latency) {
                    mv = -jumpVel();
                    tapPending = false;
                    fallSeg = false;
                }
                mv += gravity * dt;
                my += mv * dt;
            } else {
                modelValid = false;
            }
        }

        boolean accept = false;
        if (reliable) {
            if (!modelValid || Math.abs(measY - my) < 0.08f || outliers >= 4) accept = true;
            else outliers++;
        }

        if (accept) {
            outliers = 0;
            lastGood = now;
            float dm = (float) (now - prevMeasT);
            if (prevY >= 0f && dm > 0.001f && dm < 0.25f) {
                rawVy = (measY - prevY) / dm;
                if (awaitJump) {
                    float since = (float) (now - lastTap);
                    if (rawVy < Math.min(-0.35f * jumpVel(), vAtTap - 0.4f * jumpVel()) && since > 0.01f) {
                        // the jump started somewhere inside the last frame interval
                        float seen = since - 0.5f * dm;
                        if (seen > 0.02f && seen < 0.30f) latency = 0.7f * latency + 0.3f * seen;
                        awaitJump = false;
                        tapPending = false;
                        smoothVy = rawVy;       // velocity just flipped: restart smoothing
                    } else if (now >= lastTap + latency) {
                        // jump is overdue: it has not shown up yet, keep expecting it
                        tapPending = true;
                    }
                }
                smoothVy = hasVy ? 0.5f * smoothVy + 0.5f * rawVy : rawVy;
                hasVy = true;
                mv = smoothVy;
                // learn gravity from a stretch of free fall well after the last tap
                if (now - lastTap > latency + 0.12 && !tapPending) {
                    if (!fallSeg) {
                        fallSeg = true;
                        fallT0 = now;
                        fallV0 = smoothVy;
                    } else if (now - fallT0 > 0.10) {
                        float acc = (float) ((smoothVy - fallV0) / (now - fallT0));
                        if (acc > 0.8f && acc < 10f) gravity = 0.93f * gravity + 0.07f * acc;
                    }
                } else {
                    fallSeg = false;
                }
            } else {
                hasVy = false;
                fallSeg = false;
                if (!modelValid) mv = 0f;
            }
            prevY = measY;
            prevMeasT = now;
            my = measY;
            modelValid = true;

            // learn how high one tap lifts the player: from the lowest point after the
            // tap (the jump starts a little late) up to the top of the jump
            if (measuring) {
                if (!risen) {
                    if (measY > lowY) lowY = measY;
                    else if (measY < lowY - 0.004f) {
                        risen = true;
                        minSinceTap = measY;
                    }
                    if (now - lastTap > 0.4) measuring = false;
                } else {
                    if (measY < minSinceTap) minSinceTap = measY;
                    if (measY > minSinceTap + 0.006f) {
                        float rise = lowY - minSinceTap;
                        if (rise > 0.02f && rise < 0.40f) {
                            jumpRise = riseKnown ? 0.7f * jumpRise + 0.3f * rise : rise;
                            riseKnown = true;
                        }
                        measuring = false;
                    }
                }
            }
        } else {
            prevY = -1f;
            hasVy = false;
            fallSeg = false;
            if (!modelValid || now - lastGood > 0.6) {
                modelValid = false;
                return false;
            }
        }

        if (awaitJump && now - lastTap > 0.35) {
            awaitJump = false;
            tapPending = false;
        }
        // never stack a tap on one that has not had time to land
        if (now - lastTap < Math.max(minTapGap, latency + 0.035f) || awaitJump) return false;

        // ---- where the player will be when a tap decided now takes effect
        float y = my, v = mv;
        boolean pend = tapPending;
        float L = latency;
        double t = now;
        for (float done = 0f; done < L; done += 0.005f) {
            float d = Math.min(0.005f, L - done);
            if (pend && t >= lastTap + latency) {
                v = -jumpVel();
                pend = false;
            }
            v += gravity * d;
            y += v * d;
            t += d;
        }

        // ---- compare "tap now" with "do not tap now"
        sW = w;
        sPx = playerX + scroll * L;      // obstacles keep coming while the tap is on its way
        sR = radius;
        sL = L;
        sRx = radius * h / w;
        sJv = jumpVel();
        sCool = Math.max(1, Math.round(minTapGap / SIM_DT));
        sBiasTop = aim > 0.5f ? (aim - 0.5f) * 0.1f : 0f;
        sBiasBot = aim < 0.5f ? (0.5f - aim) * 0.1f : 0f;
        for (int j = 0; j < SAMPLES; j++) {
            float u = -1f + 2f * j / (SAMPLES - 1);
            sampleU[j] = u;
            sampleH[j] = (float) Math.sqrt(Math.max(0f, 1f - u * u));
        }

        float start = Math.min(CLEAR_CAP, clearance(y, 0));
        // option A: no tap now (taps allowed from the next step)
        float vA = v + gravity * SIM_DT;
        float yA = y + vA * SIM_DT;
        nodes = 0;
        float noTap = search(1, yA, vA, 0, Math.min(start, clearance(yA, 1)), -9f);
        // option B: tap now
        float vB = -sJv + gravity * SIM_DT;
        float yB = y + vB * SIM_DT;
        nodes = 0;
        float tap = search(1, yB, vB, sCool - 1, Math.min(start, clearance(yB, 1)), -9f);

        // default: hover on the centre line of the next gap (or mid-screen)
        float target = 0.45f;
        int from = Math.max(0, (int) ((playerX - sRx) * w));
        for (int x = from; x < w; x++) {
            if (ceilP[x] > CEIL_DEFAULT || floorP[x] < FLOOR_DEFAULT) {
                int xi = Math.min(w - 1, x + 2 * dx);
                float tau = Math.min(0.5f, (float) (xi - from) / w / Math.max(0.1f, scroll));
                target = 0.5f * (ceilP[xi] + velT[xi] * tau + floorP[xi] + velB[xi] * tau);
                break;
            }
        }
        // (in open space a fast fall is braked early so the player never dives into a gap)
        boolean open = tap >= CLEAR_CAP - 0.002f && noTap >= CLEAR_CAP - 0.002f;
        float brake = open ? Math.max(0f, v) * 0.10f : 0f;
        boolean doTap = y + brake > target + 0.5f * jumpRise;
        // the planner overrides the default when one choice clearly keeps more distance
        if (tap > noTap + 0.004f) doTap = true;
        else if (noTap > tap + 0.004f) doTap = false;
        if (doTap) {
            lastTap = now;
            tapPending = true;
            awaitJump = accept && hasVy && smoothVy > -0.5f * jumpVel();
            vAtTap = smoothVy;
            measuring = accept;
            risen = false;
            lowY = my;
            minSinceTap = my;
            return true;
        }
        return false;
    }

    private static float jumpVel() {
        return (float) Math.sqrt(2f * gravity * jumpRise);
    }

    private static float ceilAt(float xFrac) {
        int xi = (int) (xFrac * sW);
        if (xi < 0) xi = 0;
        if (xi >= sW) return CEIL_DEFAULT;
        return ceilP[xi];
    }

    private static float floorAt(float xFrac) {
        int xi = (int) (xFrac * sW);
        if (xi < 0) xi = 0;
        if (xi >= sW) return FLOOR_DEFAULT;
        return floorP[xi];
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Smallest distance between the player's disc and any obstacle, k steps ahead. */
    private static float clearance(float y, int k) {
        float shift = scroll * k * SIM_DT;
        float tau = Math.min(0.5f, sL + k * SIM_DT);    // obstacles may be moving up or down
        float best = 1f;
        for (int j = 0; j < SAMPLES; j++) {
            float x = sPx + sampleU[j] * sRx * 1.1f + shift;
            int xi = (int) (x * sW);
            if (xi < 0) xi = 0;
            float ce = CEIL_DEFAULT, fl = FLOOR_DEFAULT;
            if (xi < sW) {
                ce = ceilP[xi];
                fl = floorP[xi];
                // edges closing in on the gap are given extra room (they may speed up)
                if (ce > CEIL_DEFAULT) ce += velT[xi] * tau * (velT[xi] > 0f ? 1.5f : 0.7f);
                if (fl < FLOOR_DEFAULT) fl += velB[xi] * tau * (velB[xi] < 0f ? 1.5f : 0.7f);
            }
            float hh = sR * sampleH[j];
            float c1 = (y - hh) - ce - sBiasTop;
            float c2 = fl - (y + hh) - sBiasBot;
            if (c1 < best) best = c1;
            if (c2 < best) best = c2;
        }
        return best;
    }

    /**
     * Best achievable minimum clearance from step k to the horizon.
     * cool = steps until the next tap is allowed.
     */
    private static float search(int k, float y, float v, int cool, float curMin, float best) {
        if (curMin <= best) return curMin;
        if (k >= SIM_STEPS || ++nodes > NODE_BUDGET) return curMin;
        // no tap
        float v1 = v + gravity * SIM_DT;
        float y1 = y + v1 * SIM_DT;
        float r = search(k + 1, y1, v1, cool - 1, Math.min(curMin, clearance(y1, k + 1)), best);
        if (r > best) best = r;
        if (best >= curMin) return best;
        // tap
        // (after the first few steps, try taps only every other step)
        if (cool <= 0 && (k < 5 || (k & 1) == 0)) {
            float v2 = -sJv + gravity * SIM_DT;
            float y2 = y + v2 * SIM_DT;
            r = search(k + 1, y2, v2, sCool - 1, Math.min(curMin, clearance(y2, k + 1)), best);
            if (r > best) best = r;
        }
        return best;
    }
}
