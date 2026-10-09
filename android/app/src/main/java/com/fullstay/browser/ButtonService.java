package com.fullstay.browser;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.view.Display;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Volume up = screenshot, volume down = switch between FullStay and the last app you used.
 * It only watches which app is in front and the two volume buttons.
 */
public class ButtonService extends AccessibilityService {
    static final String ORIGINAL = "com.fullstay.browser";
    static volatile boolean running;
    private static WeakReference<ButtonService> instance = new WeakReference<>(null);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> ignored = new HashSet<>();
    private ExecutorService io;
    private AudioManager audio;
    private KeyguardManager keyguard;
    private long lastShotAt = -10_000;
    private String front;                                   // app screen currently in front

    /** Both FullStay builds (normal and ".exitfs") count as "the browser". */
    static boolean isFamily(String pkg) {
        return pkg != null && (pkg.equals(ORIGINAL) || pkg.startsWith(ORIGINAL + "."));
    }

    static ButtonService get() { return instance.get(); }

    static boolean isEnabled(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (s == null) return false;
        for (String part : s.split(":")) {            // either FullStay build's button service
            ComponentName cn = ComponentName.unflattenFromString(part);
            if (cn != null && isFamily(cn.getPackageName()) && ButtonService.class.getName().equals(cn.getClassName())) return true;
        }
        return false;
    }

    /** If both builds' button services are on, only one may act (the normal build's), never both. */
    private boolean deferToSibling() {
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (s == null) return false;
        String mine = getPackageName();
        for (String part : s.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(part);
            if (cn != null && isFamily(cn.getPackageName()) && !cn.getPackageName().equals(mine)
                    && ButtonService.class.getName().equals(cn.getClassName())
                    && cn.getPackageName().compareTo(mine) < 0) return true;
        }
        return false;
    }

    @Override protected void onServiceConnected() {
        running = true;
        instance = new WeakReference<>(this);
        if (io == null) io = Executors.newSingleThreadExecutor();
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        refreshIgnored();
        // Remembered so the app can notice when Android switches the service off (it does that on Force stop).
        if (!Prefs.b(this, Prefs.SERVICE_WAS_ON, false)) Prefs.p(this).edit().putBoolean(Prefs.SERVICE_WAS_ON, true).apply();
    }

    /**
     * Turns the button service back on without a trip to Accessibility settings. Only possible after a one-time
     * "adb shell pm grant <package> android.permission.WRITE_SECURE_SETTINGS"; returns false otherwise.
     */
    static boolean tryReenable(Context c) {
        if (c.checkSelfPermission(PERMISSION_WRITE_SECURE) != PackageManager.PERMISSION_GRANTED) return false;
        ComponentName me = new ComponentName(c, ButtonService.class);
        try {
            String cur = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            StringBuilder others = new StringBuilder();
            boolean listed = false;
            for (String part : cur == null ? new String[0] : cur.split(":")) {
                if (part.isEmpty()) continue;
                if (me.equals(ComponentName.unflattenFromString(part))) { listed = true; continue; }
                if (others.length() > 0) others.append(':');
                others.append(part);
            }
            // Listed but not running (Android didn't bind it again): take it out first, so adding it makes Android bind it.
            if (listed) Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.toString());
            String all = others.length() > 0 ? others + ":" + me.flattenToString() : me.flattenToString();
            Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, all);
            Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, "1");
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    static final String PERMISSION_WRITE_SECURE = "android.permission.WRITE_SECURE_SETTINGS";

    /** The saved last app (Settings can change or forget it at any time). */
    private String lastOther() { return Prefs.s(this, Prefs.LAST_OTHER, null); }

    /** Apps that never count as "the last app": us, the system UI, home screens and keyboards. */
    private void refreshIgnored() {
        ignored.clear();
        ignored.add(getPackageName());
        ignored.add("com.android.systemui");
        ignored.add("android");
        PackageManager pm = getPackageManager();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : pm.queryIntentActivities(home, 0)) {
            // Real home screens only. Placeholders with a negative priority (like the Settings app's
            // "FallbackHome") are skipped, otherwise Settings could never count as your last app.
            if (ri.activityInfo != null && ri.priority >= 0) ignored.add(ri.activityInfo.packageName);
        }
        ResolveInfo def = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        if (def != null && def.activityInfo != null) ignored.add(def.activityInfo.packageName);
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            for (InputMethodInfo info : imm.getEnabledInputMethodList()) ignored.add(info.getPackageName());
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = e.getPackageName(), cls = e.getClassName();
        if (p == null || cls == null) return;
        String pkg = p.toString();
        PackageManager pm = getPackageManager();
        try {
            pm.getActivityInfo(new ComponentName(pkg, cls.toString()), 0);   // only real app screens count
        } catch (PackageManager.NameNotFoundException notAnActivity) {
            return;
        }
        front = pkg;
        if (isFamily(pkg)) {                                    // a FullStay build: remember which one
            if (!pkg.equals(Prefs.s(this, Prefs.LAST_FAMILY, null))) Prefs.p(this).edit().putString(Prefs.LAST_FAMILY, pkg).apply();
            return;
        }
        if (ignored.contains(pkg) || pkg.equals(lastOther())) return;
        if (pm.getLaunchIntentForPackage(pkg) == null) return;
        Prefs.p(this).edit().putString(Prefs.LAST_OTHER, pkg).apply();
    }

    @Override public void onInterrupt() { }

    @Override public boolean onUnbind(Intent intent) {
        stop();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        stop();
        super.onDestroy();
    }

    private void stop() {
        running = false;
        if (instance.get() == this) instance = new WeakReference<>(null);
        if (io != null) { io.shutdown(); io = null; }
    }

    @Override protected boolean onKeyEvent(KeyEvent e) {
        KeyLogic.Action a = KeyLogic.decide(e.getKeyCode(), e.getAction(), e.getRepeatCount(),
                Prefs.keysEnabled(this), Prefs.upScreenshot(this), Prefs.downSwitch(this), passThrough());
        if (a == KeyLogic.Action.PASS || deferToSibling()) return false;
        switch (a) {
            case SCREENSHOT: takeShot(); return true;
            case SWITCH: switchApps(); return true;
            default: return true;
        }
    }

    /** On the lock screen and during calls, leave the buttons alone. */
    private boolean passThrough() {
        if (keyguard != null && keyguard.isKeyguardLocked()) return true;
        if (audio == null) return false;
        int m = audio.getMode();
        return m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION
                || m == AudioManager.MODE_RINGTONE || m == AudioManager.MODE_CALL_SCREENING;
    }

    // ------------------------------------------------------------------ screenshot

    void takeShot() {
        long now = SystemClock.uptimeMillis();
        if (now - lastShotAt < 800) return;          // ignore accidental double presses
        lastShotAt = now;
        buzz();
        if ("silent".equals(Prefs.s(this, Prefs.SHOT_STYLE, "system")) && Build.VERSION.SDK_INT >= 30) {
            silentShot();
        } else if (!performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)) {
            toast("Couldn't take a screenshot");
        }
    }

    @RequiresApi(30)
    private void silentShot() {
        if (io == null) io = Executors.newSingleThreadExecutor();
        takeScreenshot(Display.DEFAULT_DISPLAY, io, new TakeScreenshotCallback() {
            @Override public void onSuccess(@NonNull ScreenshotResult result) {
                Bitmap soft = null;
                try (HardwareBuffer hb = result.getHardwareBuffer()) {
                    Bitmap hw = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                    if (hw != null) {
                        soft = hw.copy(Bitmap.Config.ARGB_8888, false);
                        hw.recycle();
                    }
                } catch (Exception ignored) { }
                Uri saved = ScreenshotSaver.save(ButtonService.this, soft);
                if (soft != null) soft.recycle();
                handler.post(() -> toast(saved != null ? "Screenshot saved to Pictures/Screenshots" : "Couldn't save the screenshot"));
            }

            @Override public void onFailure(int code) {
                String msg = code == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ? "This app doesn't allow screenshots"
                        : code == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ? "Too fast \u2014 wait a moment and try again"
                        : "Couldn't take a screenshot";
                handler.post(() -> toast(msg));
            }
        });
    }

    // ------------------------------------------------------------------ switching

    void switchApps() {
        buzz();
        if (browserInFront()) goToLastApp(); else openBrowser();
    }

    /** This build is in front, or the other FullStay build is. */
    private boolean browserInFront() {
        return App.inForeground() || (isFamily(front) && !front.equals(getPackageName()));
    }

    private void goToLastApp() {
        if ("recents".equals(Prefs.s(this, Prefs.SWITCH_METHOD, "launch"))) {
            performGlobalAction(GLOBAL_ACTION_RECENTS);
            handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_RECENTS), 250);
            return;
        }
        Switcher.Result r = Switcher.openLastApp(this);
        if (r == Switcher.Result.STARTED) return;
        String msg = Switcher.message(r);
        if (msg != null) toast(msg);
        MainActivity m = App.main.get();
        if (App.inForeground() && m != null && !m.isFinishing() && !m.isDestroyed()) m.moveTaskToBack(true);
        else performGlobalAction(GLOBAL_ACTION_HOME);
    }

    /** Opens the FullStay build you used last (this one unless you last used the other one). */
    private void openBrowser() {
        PackageManager pm = getPackageManager();
        String last = Prefs.s(this, Prefs.LAST_FAMILY, null);
        Intent i = last != null && !last.equals(getPackageName()) ? pm.getLaunchIntentForPackage(last) : null;
        if (i == null) i = pm.getLaunchIntentForPackage(getPackageName());
        if (i == null) i = new Intent(this, MainActivity.class);
        if (!Switcher.start(this, i)) toast("Couldn't open FullStay");
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("deprecation")
    private void buzz() {
        if (!Prefs.b(this, Prefs.HAPTIC, true)) return;
        Vibrator v;
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
            v = vm == null ? null : vm.getDefaultVibrator();
        } else {
            v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        }
        if (v == null || !v.hasVibrator()) return;
        try {
            v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
        } catch (Exception ignored) { }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    /** Tests only. */
    String lastOtherForTest() { return lastOther(); }
}
