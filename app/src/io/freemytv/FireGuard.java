/*
 * After dumbtv home's Guard and HomeRetry (https://github.com/humblemuzzu/dumbtv, launcher/src/tv/dumbtv/home):
 *
 * MIT License
 *
 * Copyright (c) 2026 dumbtv contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS
 * OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Fire TV's Home button in Free My TV mode. Fire OS keeps HOME on Amazon's launcher (`set-home-activity` answers Success
 * and changes nothing) and handles the key before any accessibility filter, so this service waits for Amazon's home
 * window to appear and puts ours ({@link FireHomeActivity}) over it. It receives window changes only and reads no
 * window content. On only in Free My TV mode on Fire TV (HomeMode.fireGuard); Original and the watchdog take it out.
 *
 * Android holds back app starts for 5 s after HOME; an alarm's PendingIntent is sent by system_server, which is
 * exempt, so the launch goes through an alarm, which fires at once only with {@code min_futurity=0} (the switch sets
 * it, Original puts the TV's value back). A plain overlay covers Amazon's home until ours is up. Fire OS 8 (Android 11)
 * refuses a start from an app without a visible window, so the launch waits for the cover's first draw; Android 14
 * counts the cover a frame or more after that ({@link #SHOWN_DELAY_MS}).
 *
 * Turned off, a Fire OS TV restarts quietly into standby, and Amazon's home can be up before this service is: no
 * window event comes, and HOME on Amazon's home sends none either. So until it has seen a window, in the first minutes
 * after a start, it takes the screen for Amazon's home: when the screen comes on (or is on as it connects), and no
 * window shows up for {@link #SETTLE_MS} (a TV that starts on an input has its player's), ours comes up.
 */
public class FireGuard extends AccessibilityService {
    private static final String AMAZON = "com.amazon.tv.launcher";
    private static final String AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext";
    private static final long COVER_MAX_MS = 1500;
    private static final long UNCOVER_MS = 120;          // our window is up: a frame or two for it to draw
    private static final long LAUNCH_FALLBACK_MS = 150;
    private static final long SHOWN_DELAY_MS = Build.VERSION.SDK_INT >= 34 ? 300 : 0;
    private static final long REPEAT_MS = 1000;          // a burst of HOME presses opens ours once a second at most
    private static final long BOOT_MS = 5 * 60_000;      // connected this soon after a start: Amazon's home is in front
    private static final long SETTLE_MS = 1200;
    private static final long SLOW_COVER_MS = 6500;      // alarms not at once yet (5 s): the cover waits for the launch

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable uncover = this::uncover;
    private final Runnable launch = () -> home(0);
    private final Runnable retry = this::retryHome;
    private final Runnable bootHome = this::bootHome;
    private View cover;
    private boolean onAmazonHome, pending, seen, afterBoot;
    private long lastLaunch = -REPEAT_MS;

    /** Waking from standby resumes what was on screen with no window event: Amazon's home gets replaced then too. */
    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (onAmazonHome) launchWhenCovered();
            else if (afterBoot && !seen) settle();
        }
    };

    @Override protected void onServiceConnected() {
        if (!HomeMode.AMAZON) {
            disableSelf();
            return;
        }
        View view = new View(this);
        view.setVisibility(View.GONE);   // the window exists from the start; a GONE root keeps its surface hidden
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        lp.windowAnimations = 0;
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(view, lp);
            cover = view;
        } catch (RuntimeException e) {
            AppLog.w("fire guard: no cover (" + e.getMessage() + ")");   // the launch still works, Amazon's home flashes by
        }
        registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
        afterBoot = SystemClock.elapsedRealtime() < BOOT_MS;
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (afterBoot && power != null && power.isInteractive()) settle();
    }

    private void settle() {
        handler.removeCallbacks(bootHome);
        handler.postDelayed(bootHome, SETTLE_MS);
    }

    /** No window seen since the start: what's on screen is Amazon's home. */
    private void bootHome() {
        if (seen) return;
        AppLog.w("fire guard: Amazon's home at start, ours over it");
        onAmazonHome = true;
        launchWhenCovered();
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(screenOn);
        } catch (IllegalArgumentException ignored) {}
        if (cover != null) {
            try {
                ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(cover);
            } catch (RuntimeException ignored) {}
        }
        cancelLaunch();
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName(), cls = e.getClassName();
        if (pkg == null) return;
        if (!AMAZON.contentEquals(pkg)) {
            if ("com.android.systemui".contentEquals(pkg) || "android".contentEquals(pkg)) return;
            seen();
            cancelRetry();
            onAmazonHome = false;
            if (getPackageName().contentEquals(pkg)) {
                handler.removeCallbacks(uncover);
                handler.postDelayed(uncover, UNCOVER_MS);
            } else {
                cancelLaunch();   // they opened an app: a queued launch must not pull them back
            }
            return;
        }
        seen();
        // Amazon's other launcher screens (its settings grid, its app library) stay.
        onAmazonHome = cls != null && AMAZON_HOME.contentEquals(cls);
        if (!onAmazonHome) {
            cancelRetry();
            return;
        }
        pending = true;
        retryHome();
    }

    private void seen() {
        seen = true;
        handler.removeCallbacks(bootHome);
    }

    /** Keeps one HOME request until a second has passed since the last launch: Fire OS may leave its home on top after a burst. */
    private void retryHome() {
        handler.removeCallbacks(retry);
        if (!onAmazonHome || !pending) {
            pending = false;
            return;
        }
        long now = SystemClock.uptimeMillis();
        long wait = Math.max(0, lastLaunch + REPEAT_MS - now);
        if (wait == 0) {
            pending = false;
            lastLaunch = now;
            launchWhenCovered();
        } else {
            handler.postAtTime(retry, now + wait);
        }
    }

    private void cancelRetry() {
        handler.removeCallbacks(retry);
        pending = false;
    }

    /** Shows the cover, then launches once the window manager has it (an already visible cover gets no new draw). */
    private void launchWhenCovered() {
        handler.removeCallbacks(launch);
        if (cover == null) {
            home(0);
            return;
        }
        boolean shown = cover.getVisibility() == View.VISIBLE;
        cover();
        if (shown) {
            home(0);
            return;
        }
        cover.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                cover.getViewTreeObserver().removeOnPreDrawListener(this);
                handler.removeCallbacks(launch);
                home(SHOWN_DELAY_MS);
                return true;
            }
        });
        handler.postDelayed(launch, LAUNCH_FALLBACK_MS);   // a window that never draws
    }

    private void home(long delayMs) {
        try {
            AlarmManager alarms = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            alarms.setExact(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + delayMs, launchIntent());
        } catch (RuntimeException e) {
            AppLog.w("fire guard: " + e.getMessage());
            uncover();
        }
    }

    private void cancelLaunch() {
        try {
            ((AlarmManager) getSystemService(Context.ALARM_SERVICE)).cancel(launchIntent());
        } catch (RuntimeException ignored) {}
    }

    private PendingIntent launchIntent() {
        Intent i = new Intent(this, FireHomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle());
    }

    private void cover() {
        if (cover == null) return;
        cover.setBackgroundColor(getColor(R.color.home_background));
        cover.setVisibility(View.VISIBLE);
        handler.removeCallbacks(uncover);
        handler.postDelayed(uncover, alarmsAtOnce() ? COVER_MAX_MS : SLOW_COVER_MS);
    }

    /**
     * Whether the switch's min_futurity=0 is in place (Fire OS can put its alarm constants back at a start, until the
     * boot check writes it again). Otherwise the launch fires 5 s later, and Android 11 lets it through only while the
     * cover is still up. Android 12+ keeps it in DeviceConfig, which an app can't read: taken as set.
     */
    private boolean alarmsAtOnce() {
        if (Build.VERSION.SDK_INT >= 31) return true;
        String all = android.provider.Settings.Global.getString(getContentResolver(), "alarm_manager_constants");
        return all != null && ("," + all + ",").contains(",min_futurity=0,");
    }

    private void uncover() {
        handler.removeCallbacks(uncover);
        if (cover != null) cover.setVisibility(View.GONE);
    }

    @Override public void onInterrupt() {}
}
