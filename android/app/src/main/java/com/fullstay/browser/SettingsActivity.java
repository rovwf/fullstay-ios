package com.fullstay.browser;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

public class SettingsActivity extends AppCompatActivity implements SharedPreferences.OnSharedPreferenceChangeListener {
    static final String[][] ENGINES = {
            {"DuckDuckGo", "https://duckduckgo.com/?q=%s"},
            {"Google", "https://www.google.com/search?q=%s"},
            {"Bing", "https://www.bing.com/search?q=%s"},
            {"Brave Search", "https://search.brave.com/search?q=%s"},
            {"Startpage", "https://www.startpage.com/do/search?query=%s"},
            {"Ecosia", "https://www.ecosia.org/search?q=%s"},
    };
    static final int[] ZOOMS = {75, 90, 100, 115, 130, 150, 175, 200};

    private Ui.Row masterRow, homeRow, searchRow, zoomRow, styleRow, methodRow, lastAppRow, uaRow;
    private ImageView statusIcon;
    private TextView statusTitle, statusText;
    private LinearLayout setupBox;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout page = Ui.screen(this, "Settings");

        // ---- Volume buttons
        Ui.section(page, "Volume buttons");
        LinearLayout keys = Ui.card(page);
        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.HORIZONTAL);
        status.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 4));
        statusIcon = Ui.icon(this, R.drawable.ic_warning, Ui.ATTR_ERROR, 24);
        status.addView(statusIcon);
        LinearLayout st = new LinearLayout(this);
        st.setOrientation(LinearLayout.VERTICAL);
        st.setPadding(Ui.dp(this, 16), 0, 0, 0);
        statusTitle = new TextView(this);
        Ui.text(statusTitle, com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        statusTitle.setTextColor(Ui.color(this, Ui.ATTR_ON_SURFACE));
        statusText = new TextView(this);
        Ui.text(statusText, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        statusText.setTextColor(Ui.color(this, Ui.ATTR_ON_SURFACE_VARIANT));
        st.addView(statusTitle);
        st.addView(statusText);
        status.addView(st, new LinearLayout.LayoutParams(0, -2, 1f));
        keys.addView(status);
        setupBox = new LinearLayout(this);
        setupBox.setOrientation(LinearLayout.VERTICAL);
        keys.addView(setupBox);
        Ui.button(setupBox, "Open Accessibility settings", true, v -> open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        Ui.note(setupBox, "Find \u201CFullStay buttons\u201D (it may be under \u201CDownloaded apps\u201D or \u201CInstalled apps\u201D) and turn it on. "
                + "If Android says it's a restricted setting, tap the button below, then \u22EE at the top right \u2192 "
                + "\u201CAllow restricted settings\u201D, and try again.");
        Ui.button(setupBox, "Open app info", false, v -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null))));
        Ui.divider(keys);

        masterRow = Ui.switchRow(keys, "Use volume buttons as shortcuts",
                "Turn off to use them for volume again. The \u201CButton shortcuts\u201D Quick Settings tile does the same.",
                Prefs.KEYS_ENABLED, true, () -> ButtonTileService.refresh(this));
        Ui.switchRow(keys, "Volume up takes a screenshot", "One normal press", Prefs.UP_SCREENSHOT, true, null);
        styleRow = Ui.row(keys, "Screenshot style", styleName(), v -> chooseStyle());
        Ui.switchRow(keys, "Volume down switches apps", "One press: FullStay \u21C4 the last app you used",
                Prefs.DOWN_SWITCH, true, null);
        methodRow = Ui.row(keys, "How to switch", methodName(), v -> chooseMethod());
        Ui.switchRow(keys, "Instant switch", "No animation when switching", Prefs.INSTANT, true, null);
        Ui.switchRow(keys, "Vibrate on press", null, Prefs.HAPTIC, true, null);
        lastAppRow = Ui.row(keys, "Last app", "", v -> lastAppOptions());
        Ui.note(keys, "On the lock screen and during calls the buttons change the volume as usual. "
                + "To change the volume anywhere else: \u22EE \u2192 Volume in the browser, or turn the shortcuts off with the tile.");

        // ---- Fullscreen
        Ui.section(page, "Fullscreen");
        LinearLayout fs = Ui.card(page);
        Ui.switchRow(fs, "Stay fullscreen when switching apps",
                "Pages act as if you never left: no \u201Chidden\u201D or \u201Clost focus\u201D signals, and they can't leave "
                        + "fullscreen by themselves while you switch.", Prefs.KEEP_FS, true, null);
        Ui.switchRow(fs, "Keep pages running in the background",
                "The page isn't paused when you leave, so sound keeps playing. Android still slows down "
                        + "pages you can't see. Uses more battery.",
                Prefs.KEEP_RUNNING, true, null);
        Ui.switchRow(fs, "Start in fullscreen", null, Prefs.START_FS, false, null);
        Ui.switchRow(fs, "Keep the screen on in fullscreen", null, Prefs.SCREEN_ON, false, null);
        Ui.note(fs, "Enter fullscreen any time with \u22EE \u2192 Fullscreen. Press Back to leave it. "
                + "Swipe from the screen edge to peek at the system bars.");

        // ---- Browsing
        Ui.section(page, "Browsing");
        LinearLayout br = Ui.card(page);
        homeRow = Ui.row(br, "Home page", Prefs.s(this, Prefs.HOME, Prefs.DEF_HOME), v -> editHome());
        searchRow = Ui.row(br, "Search engine", engineName(), v -> chooseEngine());
        zoomRow = Ui.row(br, "Text size", Prefs.i(this, Prefs.TEXT_ZOOM, 100) + "%", v -> chooseZoom());
        Ui.switchRow(br, "Reopen the last page on start", null, Prefs.RESTORE_LAST, true, null);
        Ui.switchRow(br, "Allow third-party cookies", "Some logins and embedded games need this",
                Prefs.THIRD_PARTY_COOKIES, true, null);
        Ui.row(br, "Make FullStay your default browser", "Opens Android's default-apps screen",
                v -> open(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)));

        // ---- User-agent
        Ui.section(page, "User-agent");
        LinearLayout ua = Ui.card(page);
        uaRow = Ui.row(ua, "User-Agent Switcher", "", v -> startActivity(new Intent(this, UaActivity.class)));

        // ---- Privacy
        Ui.section(page, "Privacy");
        LinearLayout pr = Ui.card(page);
        Ui.row(pr, "Clear cookies and site data", "Signs you out of websites and resets saved game data",
                v -> confirm("Clear cookies and site data?", "You'll be signed out of websites, and website data "
                        + "(like browser-saved game progress) will be deleted.", () -> {
                    CookieManager.getInstance().removeAllCookies(null);
                    WebStorage.getInstance().deleteAllData();
                    toast("Cookies and site data cleared");
                }));
        Ui.row(pr, "Clear history and cache", null, v -> confirm("Clear history and cache?", null, () -> {
            Prefs.p(this).edit().putBoolean(Prefs.CLEAR_PENDING, true).remove(Prefs.LAST_URL).apply();
            toast("Cleared \u2014 takes effect when you go back to the browser");
        }));

        // ---- About
        Ui.section(page, "Screenshots & about");
        LinearLayout ab = Ui.card(page);
        Ui.note(ab, "FullStay never blocks screenshots or screen recording. Some protected (DRM) videos may still "
                + "appear black; that's decided by the video service, not by the app.");
        Ui.row(ab, "Version", versionName(), null);
    }

    // The Quick Settings tile can change the master switch while this screen stays open
    // (pulling down the shade doesn't pause it), so follow changes live.
    @Override protected void onStart() {
        super.onStart();
        Prefs.p(this).registerOnSharedPreferenceChangeListener(this);
        onSharedPreferenceChanged(null, Prefs.KEYS_ENABLED);
    }

    @Override protected void onStop() {
        Prefs.p(this).unregisterOnSharedPreferenceChangeListener(this);
        super.onStop();
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        boolean on = Prefs.keysEnabled(this);
        if (Prefs.KEYS_ENABLED.equals(key) && masterRow.toggle.isChecked() != on) masterRow.toggle.setChecked(on);
    }

    @Override protected void onResume() {
        super.onResume();
        boolean on = ButtonService.isEnabled(this);
        statusIcon.setImageResource(on ? R.drawable.ic_check_circle : R.drawable.ic_warning);
        androidx.core.widget.ImageViewCompat.setImageTintList(statusIcon, android.content.res.ColorStateList.valueOf(
                Ui.color(this, on ? Ui.ATTR_PRIMARY : Ui.ATTR_ERROR)));
        statusTitle.setText(on ? "Ready" : "Needs a one-time setup");
        statusText.setText(on ? "The volume buttons work in every app."
                : "Right now they only work inside FullStay (and can't bring you back from other apps).");
        setupBox.setVisibility(on ? android.view.View.GONE : android.view.View.VISIBLE);
        lastAppRow.setSummary(lastAppLabel());
        uaRow.setSummary(UaManager.menuLabel(this));
    }

    // ---- volume buttons

    private String styleName() {
        return "silent".equals(Prefs.s(this, Prefs.SHOT_STYLE, "system"))
                ? "Silent \u2014 saved straight to Pictures/Screenshots"
                : "Android screenshot \u2014 with preview, edit and share";
    }

    private void chooseStyle() {
        String[] opts = {"Android screenshot \u2014 with preview, edit and share", "Silent \u2014 saved straight to Pictures/Screenshots"};
        int checked = "silent".equals(Prefs.s(this, Prefs.SHOT_STYLE, "system")) ? 1 : 0;
        new MaterialAlertDialogBuilder(this).setTitle("Screenshot style")
                .setSingleChoiceItems(opts, checked, (d, w) -> {
                    if (w == 1 && Build.VERSION.SDK_INT < 30) { toast("Silent screenshots need Android 11 or newer"); d.dismiss(); return; }
                    Prefs.p(this).edit().putString(Prefs.SHOT_STYLE, w == 1 ? "silent" : "system").apply();
                    styleRow.setSummary(styleName());
                    d.dismiss();
                }).show();
    }

    private String methodName() {
        return "recents".equals(Prefs.s(this, Prefs.SWITCH_METHOD, "launch"))
                ? "Recents double-tap (experimental)"
                : "Open the last app directly (recommended)";
    }

    private void chooseMethod() {
        String[] opts = {"Open the last app directly (recommended)", "Recents double-tap (experimental)"};
        int checked = "recents".equals(Prefs.s(this, Prefs.SWITCH_METHOD, "launch")) ? 1 : 0;
        new MaterialAlertDialogBuilder(this).setTitle("How to switch")
                .setSingleChoiceItems(opts, checked, (d, w) -> {
                    Prefs.p(this).edit().putString(Prefs.SWITCH_METHOD, w == 1 ? "recents" : "launch").apply();
                    methodRow.setSummary(methodName());
                    d.dismiss();
                }).show();
    }

    private String lastAppLabel() {
        String pkg = Prefs.s(this, Prefs.LAST_OTHER, null);
        if (pkg == null) return "None yet \u2014 open any other app once";
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private void lastAppOptions() {
        if (Prefs.s(this, Prefs.LAST_OTHER, null) == null) { toast("Open another app, then come back"); return; }
        new MaterialAlertDialogBuilder(this).setTitle("Last app: " + lastAppLabel())
                .setMessage("Volume down switches to this app. It updates automatically whenever you use another app.")
                .setPositiveButton("OK", null)
                .setNegativeButton("Forget it", (d, w) -> {
                    Prefs.p(this).edit().remove(Prefs.LAST_OTHER).apply();
                    lastAppRow.setSummary(lastAppLabel());
                    toast("Forgotten");
                }).show();
    }

    // ---- browsing

    private void editHome() {
        TextInputLayout til = new TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        til.setHint("Web address");
        til.setHelperText("Leave empty for the default (DuckDuckGo)");
        TextInputEditText e = new TextInputEditText(til.getContext());
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setSingleLine(true);
        e.setText(Prefs.s(this, Prefs.HOME, Prefs.DEF_HOME));
        til.addView(e);
        new MaterialAlertDialogBuilder(this).setTitle("Home page").setView(wrap(til))
                .setPositiveButton("Save", (d, w) -> {
                    String input = e.getText() == null ? "" : e.getText().toString().trim();
                    String u = input.isEmpty() ? Prefs.DEF_HOME : UrlUtil.toAddress(input);
                    if (u == null || !(UrlUtil.isHttp(u) || u.startsWith("about:"))) {
                        toast("That isn't a web address \u2014 the home page wasn't changed");
                        return;
                    }
                    Prefs.p(this).edit().putString(Prefs.HOME, u).apply();
                    homeRow.setSummary(u);
                })
                .setNegativeButton("Cancel", null).show();
    }

    private String engineName() {
        String cur = Prefs.s(this, Prefs.SEARCH, Prefs.DEF_SEARCH);
        for (String[] e : ENGINES) if (e[1].equals(cur)) return e[0];
        return "Custom: " + cur;
    }

    private void chooseEngine() {
        String cur = Prefs.s(this, Prefs.SEARCH, Prefs.DEF_SEARCH);
        String[] names = new String[ENGINES.length + 1];
        int checked = ENGINES.length;
        for (int i = 0; i < ENGINES.length; i++) {
            names[i] = ENGINES[i][0];
            if (ENGINES[i][1].equals(cur)) checked = i;
        }
        names[ENGINES.length] = "Custom\u2026";
        new MaterialAlertDialogBuilder(this).setTitle("Search engine")
                .setSingleChoiceItems(names, checked, (d, w) -> {
                    d.dismiss();
                    if (w == ENGINES.length) { customEngine(); return; }
                    Prefs.p(this).edit().putString(Prefs.SEARCH, ENGINES[w][1]).apply();
                    searchRow.setSummary(engineName());
                }).show();
    }

    private void customEngine() {
        TextInputLayout til = new TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        til.setHint("Search URL with %s for the words");
        til.setHelperText("Example: https://example.com/search?q=%s");
        TextInputEditText e = new TextInputEditText(til.getContext());
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setSingleLine(true);
        til.addView(e);
        new MaterialAlertDialogBuilder(this).setTitle("Custom search engine").setView(wrap(til))
                .setPositiveButton("Save", (d, w) -> {
                    String s = e.getText() == null ? "" : e.getText().toString().trim();
                    if (!UrlUtil.isHttp(s) || !s.contains("%s")) { toast("It must start with http:// or https:// and contain %s"); return; }
                    Prefs.p(this).edit().putString(Prefs.SEARCH, s).apply();
                    searchRow.setSummary(engineName());
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void chooseZoom() {
        String[] labels = new String[ZOOMS.length];
        int cur = Prefs.i(this, Prefs.TEXT_ZOOM, 100), checked = 2;
        for (int i = 0; i < ZOOMS.length; i++) {
            labels[i] = ZOOMS[i] + "%" + (ZOOMS[i] == 100 ? " (default)" : "");
            if (ZOOMS[i] == cur) checked = i;
        }
        new MaterialAlertDialogBuilder(this).setTitle("Text size")
                .setSingleChoiceItems(labels, checked, (d, w) -> {
                    Prefs.p(this).edit().putInt(Prefs.TEXT_ZOOM, ZOOMS[w]).apply();
                    zoomRow.setSummary(ZOOMS[w] + "%");
                    d.dismiss();
                }).show();
    }

    // ---- helpers

    private android.view.View wrap(android.view.View v) {
        LinearLayout box = new LinearLayout(this);
        int p = Ui.dp(this, 24);
        box.setPadding(p, Ui.dp(this, 8), p, 0);
        box.addView(v, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    private void confirm(String title, String msg, Runnable action) {
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(this).setTitle(title)
                .setPositiveButton("Clear", (d, w) -> action.run())
                .setNegativeButton("Cancel", null);
        if (msg != null) b.setMessage(msg);
        b.show();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private void open(Intent i) {
        try { startActivity(i); } catch (Exception e) { toast("Couldn't open that screen on this phone"); }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
