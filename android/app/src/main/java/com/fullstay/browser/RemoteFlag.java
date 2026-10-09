package com.fullstay.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Silently asks rovwf.com whether this phone should stay in fullscreen when you come back to FullStay.
 *
 * The first time, the phone signs up (POST /_fs/register with the sign-up key built into the app) and gets its own
 * id and secret token; from then on it only asks for its own switch (POST /_fs/state). Every phone shows up in
 * "FullStay Android Control" on the site, where its switch can be flipped:
 *     on  = stays in fullscreen when you come back (the original behaviour);
 *     off = leaves fullscreen when you come back.
 *
 * Nothing about this is shown anywhere in the app. It fails quietly: without a connection the last known value is
 * kept. With no sign-up key built in (gradle property "fullstayKey" empty), the whole thing is off.
 */
final class RemoteFlag {
    private RemoteFlag() {}

    // Package-visible and not final only so the tests can point them at a local server.
    static String base = BuildConfig.FS_BASE;          // e.g. "https://rovwf.com"
    static String key = BuildConfig.FS_KEY;            // the sign-up key (same as the Worker's FS_KEY secret)

    private static final long MIN_GAP_MS = 30_000;     // don't ask more than twice a minute
    private static final int TIMEOUT_MS = 8000;
    private static final int MAX_BODY = 65536;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fs-remote");
        t.setDaemon(true);
        return t;
    });
    private static volatile long lastCheck = -MIN_GAP_MS * 2;

    static boolean enabled() { return !key.isEmpty() && !base.isEmpty(); }

    /** The value last fetched (defaults to the compile-time behaviour until the first successful check). */
    static boolean exitOnReturn(Context c) {
        return Prefs.b(c, Prefs.REMOTE_EXIT, BuildConfig.EXIT_FULLSCREEN_ON_RETURN);
    }

    /** Kick off a background check if remote control is on and we didn't just check. Returns immediately. */
    static void refresh(Context c) { refresh(c, false, null); }

    /**
     * Same, optionally ignoring the once-every-30-seconds limit. {@code onExitTurnedOn} runs on the main thread
     * if this check finds the switch was just turned off on the site (leave fullscreen on return).
     */
    static void refresh(Context c, boolean force, Runnable onExitTurnedOn) {
        if (!enabled()) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastCheck < MIN_GAP_MS) return;
        lastCheck = now;
        final Context app = c.getApplicationContext();
        IO.execute(() -> {
            boolean before = exitOnReturn(app);
            sync(app);
            if (onExitTurnedOn != null && !before && exitOnReturn(app)) MAIN.post(onExitTurnedOn);
        });
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Signs up if needed, then reads this phone's switch. Package-visible for tests; runs on the caller's thread. */
    static void sync(Context app) {
        try {
            SharedPreferences p = Prefs.p(app);
            JSONObject info = info(app);
            for (int attempt = 0; attempt < 2; attempt++) {
                String id = p.getString(Prefs.FS_ID, null), token = p.getString(Prefs.FS_TOKEN, null);
                if (id == null || token == null) {
                    JSONObject body = new JSONObject(info.toString()).put("key", key).put("platform", "android");
                    Reply r = post("/_fs/register", body);
                    if (r == null || r.code != 200) return;                  // no connection / not set up / wrong key
                    JSONObject o = new JSONObject(r.body);
                    if (!o.optBoolean("ok") || o.optString("id").isEmpty() || o.optString("token").isEmpty()) return;
                    p.edit().putString(Prefs.FS_ID, o.getString("id")).putString(Prefs.FS_TOKEN, o.getString("token")).apply();
                    store(app, o.getBoolean("stay"));
                    return;
                }
                Reply r = post("/_fs/state", new JSONObject().put("id", id).put("token", token).put("info", info));
                if (r == null) return;
                if (r.code == 404 && r.body.contains("\"gone\"")) {           // removed on the site: sign up again
                    p.edit().remove(Prefs.FS_ID).remove(Prefs.FS_TOKEN).apply();
                    continue;
                }
                if (r.code != 200) return;
                store(app, stayFrom(r.body));
                return;
            }
        } catch (Exception quiet) {
            // No network, bad reply, timeout: keep the last value, show nothing.
        }
    }

    private static void store(Context app, boolean stay) {
        boolean exit = !stay;
        if (exit != Prefs.b(app, Prefs.REMOTE_EXIT, !exit)) Prefs.p(app).edit().putBoolean(Prefs.REMOTE_EXIT, exit).apply();
    }

    /** {"ok":true,"stay":false} -> false. Throws on anything else. Package-visible for tests. */
    static boolean stayFrom(String body) throws JSONException {
        JSONObject o = new JSONObject(body == null ? "" : body.trim());
        if (!o.optBoolean("ok")) throw new JSONException("not ok");
        return o.getBoolean("stay");
    }

    /** What the site shows so you can tell phones apart. Package-visible for tests. */
    static JSONObject info(Context c) throws JSONException {
        JSONObject o = new JSONObject();
        String name = null;
        try { name = Settings.Global.getString(c.getContentResolver(), Settings.Global.DEVICE_NAME); } catch (Exception ignored) { }
        o.put("name", name == null || name.trim().isEmpty() ? Build.MODEL : name.trim());
        o.put("model", Build.MODEL);
        o.put("maker", Build.MANUFACTURER == null || Build.MANUFACTURER.isEmpty() ? ""
                : Build.MANUFACTURER.substring(0, 1).toUpperCase(Locale.ROOT) + Build.MANUFACTURER.substring(1));
        o.put("os", "Android " + Build.VERSION.RELEASE);
        o.put("app", BuildConfig.VERSION_NAME + (BuildConfig.EXIT_FULLSCREEN_ON_RETURN ? " (exit build)" : ""));
        o.put("screen", screen(c));
        o.put("lang", Locale.getDefault().toLanguageTag());
        o.put("tz", TimeZone.getDefault().getID());
        try {
            long gb = new StatFs(Environment.getDataDirectory().getPath()).getTotalBytes() / 1_000_000_000L;
            if (gb > 0) o.put("storage", gb + " GB");
        } catch (Exception ignored) { }
        o.put("build", Build.DISPLAY);
        return o;
    }

    private static String screen(Context c) {
        int w, h;
        WindowManager wm = c.getSystemService(WindowManager.class);
        if (wm != null && Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getMaximumWindowMetrics().getBounds();
            w = b.width(); h = b.height();
        } else {
            DisplayMetrics m = c.getResources().getDisplayMetrics();
            w = m.widthPixels; h = m.heightPixels;
        }
        return Math.min(w, h) + "\u00d7" + Math.max(w, h);
    }

    static final class Reply {
        final int code;
        final String body;
        Reply(int code, String body) { this.code = code; this.body = body; }
    }

    /** POSTs JSON to the site. Returns null if it couldn't be reached at all. */
    private static Reply post(String path, JSONObject json) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(base + path).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            byte[] out = json.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(out.length);
            try (OutputStream os = conn.getOutputStream()) { os.write(out); }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            if (in != null) {
                try (InputStream s = in) {
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = s.read(b)) > 0 && buf.size() < MAX_BODY) buf.write(b, 0, n);
                }
            }
            return new Reply(code, new String(buf.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
