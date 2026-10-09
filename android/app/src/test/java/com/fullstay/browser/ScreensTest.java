package com.fullstay.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class ScreensTest {
    private Application app;

    @Before public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        Prefs.p(app).edit().clear().commit();
        UaManager.reset(app);
    }

    // ---- view helpers

    private static TextView findText(View v, String text) {
        if (v instanceof TextView && text.contentEquals(((TextView) v).getText())) return (TextView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView f = findText(g.getChildAt(i), text);
                if (f != null) return f;
            }
        }
        return null;
    }

    private static boolean hasText(View v, String prefix) {
        if (v instanceof TextView && ((TextView) v).getText().toString().startsWith(prefix) && v.isShown()) return true;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) if (hasText(g.getChildAt(i), prefix)) return true;
        }
        return false;
    }

    private static EditText findEdit(View v) {
        if (v instanceof EditText) return (EditText) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                EditText f = findEdit(g.getChildAt(i));
                if (f != null) return f;
            }
        }
        return null;
    }

    /** Clicks the settings row whose title is {@code title}. */
    private static void clickRow(View root, String title) {
        TextView t = findText(root, title);
        assertNotNull("row not found: " + title, t);
        View row = (View) t.getParent().getParent();
        assertTrue(row.performClick());
    }

    private static View buttonWithText(View root, String text) {
        TextView t = findText(root, text);
        assertNotNull("button not found: " + text, t);
        return t;
    }

    /** Dialog buttons deliver clicks through the main looper, like on a phone. */
    private static void press(AlertDialog d, int which) {
        assertTrue(d.getButton(which).performClick());
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static AlertDialog latest() { return (AlertDialog) ShadowDialog.getLatestDialog(); }

    private static void chooseItem(AlertDialog d, int index) {
        ListView lv = d.getListView();
        assertNotNull(lv);
        lv.performItemClick(null, index, index);
    }

    // ---- Settings

    @Test public void settingsShowsSetupThenReady() {
        ActivityController<SettingsActivity> c = Robolectric.buildActivity(SettingsActivity.class).setup();
        View root = c.get().getWindow().getDecorView();
        assertTrue(hasText(root, "Needs a one-time setup"));
        assertTrue(hasText(root, "Open Accessibility settings"));

        Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                "com.other/.Svc:" + new ComponentName(app, ButtonService.class).flattenToString());
        c.pause().resume();
        assertTrue(hasText(root, "Ready"));
        assertFalse(hasText(root, "Open Accessibility settings"));
        assertTrue(ButtonService.isEnabled(app));
    }

    @Test public void masterSwitchFollowsTheQuickSettingsTile() {
        View root = Robolectric.buildActivity(SettingsActivity.class).setup().get().getWindow().getDecorView();
        View row = (View) findText(root, "Use volume buttons as shortcuts").getParent().getParent();
        android.widget.CompoundButton sw = (android.widget.CompoundButton) ((ViewGroup) row).getChildAt(1);
        assertTrue(sw.isChecked());
        Robolectric.setupService(ButtonTileService.class).onClick();   // tile tapped with Settings open
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(sw.isChecked());
        assertFalse(Prefs.keysEnabled(app));
    }

    @Test public void switchesHaveSpokenLabels() {
        View root = Robolectric.buildActivity(SettingsActivity.class).setup().get().getWindow().getDecorView();
        View row = (View) findText(root, "Volume up takes a screenshot").getParent().getParent();
        assertEquals("Volume up takes a screenshot", ((ViewGroup) row).getChildAt(1).getContentDescription());
    }

    @Test public void settingsSwitchesAreSaved() {
        View root = Robolectric.buildActivity(SettingsActivity.class).setup().get().getWindow().getDecorView();
        clickRow(root, "Volume up takes a screenshot");
        assertFalse(Prefs.upScreenshot(app));
        clickRow(root, "Use volume buttons as shortcuts");
        assertFalse(Prefs.keysEnabled(app));
        clickRow(root, "Keep pages running in the background");
        assertFalse(Prefs.b(app, Prefs.KEEP_RUNNING, true));
        clickRow(root, "Start in fullscreen");
        assertTrue(Prefs.b(app, Prefs.START_FS, false));
        clickRow(root, "Allow third-party cookies");
        assertFalse(Prefs.b(app, Prefs.THIRD_PARTY_COOKIES, true));
    }

    @Test public void settingsChoicesAreSaved() {
        View root = Robolectric.buildActivity(SettingsActivity.class).setup().get().getWindow().getDecorView();
        clickRow(root, "Screenshot style");
        chooseItem(latest(), 1);
        assertEquals("silent", Prefs.s(app, Prefs.SHOT_STYLE, ""));
        clickRow(root, "How to switch");
        chooseItem(latest(), 1);
        assertEquals("recents", Prefs.s(app, Prefs.SWITCH_METHOD, ""));
        clickRow(root, "Search engine");
        chooseItem(latest(), 1);
        assertEquals("https://www.google.com/search?q=%s", Prefs.s(app, Prefs.SEARCH, ""));
        clickRow(root, "Text size");
        chooseItem(latest(), 4);
        assertEquals(130, Prefs.i(app, Prefs.TEXT_ZOOM, 0));
        assertTrue(hasText(root, "130%"));
    }

    @Test public void homePageAndCustomSearchValidated() {
        View root = Robolectric.buildActivity(SettingsActivity.class).setup().get().getWindow().getDecorView();
        clickRow(root, "Home page");
        AlertDialog d = latest();
        EditText e = findEdit(d.getWindow().getDecorView());
        e.setText("example.org/start");
        press(d, AlertDialog.BUTTON_POSITIVE);
        assertEquals("https://example.org/start", Prefs.s(app, Prefs.HOME, ""));

        clickRow(root, "Search engine");
        chooseItem(latest(), SettingsActivity.ENGINES.length);          // Custom...
        AlertDialog cd = latest();
        findEdit(cd.getWindow().getDecorView()).setText("not a url");
        press(cd, AlertDialog.BUTTON_POSITIVE);
        assertEquals(Prefs.DEF_SEARCH, Prefs.s(app, Prefs.SEARCH, Prefs.DEF_SEARCH));   // rejected
        clickRow(root, "Search engine");
        chooseItem(latest(), SettingsActivity.ENGINES.length);
        cd = latest();
        findEdit(cd.getWindow().getDecorView()).setText("https://s.test/?q=%s");
        press(cd, AlertDialog.BUTTON_POSITIVE);
        assertEquals("https://s.test/?q=%s", Prefs.s(app, Prefs.SEARCH, ""));
        assertTrue(hasText(root, "Custom: https://s.test/?q=%s"));
    }

    @Test public void homePageRejectsPlainWordsAndEmptyMeansDefault() {
        Prefs.p(app).edit().putString(Prefs.HOME, "https://mine.test/").commit();
        View root = Robolectric.buildActivity(SettingsActivity.class).setup().get().getWindow().getDecorView();
        clickRow(root, "Home page");
        AlertDialog d = latest();
        findEdit(d.getWindow().getDecorView()).setText("cute cats");
        press(d, AlertDialog.BUTTON_POSITIVE);
        assertEquals("https://mine.test/", Prefs.s(app, Prefs.HOME, ""));
        clickRow(root, "Home page");
        d = latest();
        findEdit(d.getWindow().getDecorView()).setText("  ");
        press(d, AlertDialog.BUTTON_POSITIVE);
        assertEquals(Prefs.DEF_HOME, Prefs.s(app, Prefs.HOME, ""));
    }

    @Test public void lastAppShownAndCanBeForgotten() {
        Prefs.p(app).edit().putString(Prefs.LAST_OTHER, "com.missing.app").commit();
        View root = Robolectric.buildActivity(SettingsActivity.class).setup().get().getWindow().getDecorView();
        assertTrue(hasText(root, "com.missing.app"));
        clickRow(root, "Last app");
        press(latest(), AlertDialog.BUTTON_NEGATIVE);
        assertEquals(null, Prefs.s(app, Prefs.LAST_OTHER, null));
        assertTrue(hasText(root, "None yet"));
    }

    @Test public void clearHistoryAsksFirst() {
        Prefs.p(app).edit().putString(Prefs.LAST_URL, "https://x.test/").commit();
        View root = Robolectric.buildActivity(SettingsActivity.class).setup().get().getWindow().getDecorView();
        clickRow(root, "Clear history and cache");
        assertFalse(Prefs.b(app, Prefs.CLEAR_PENDING, false));
        press(latest(), AlertDialog.BUTTON_POSITIVE);
        assertTrue(Prefs.b(app, Prefs.CLEAR_PENDING, false));
        assertEquals(null, Prefs.s(app, Prefs.LAST_URL, null));
    }

    // ---- User-Agent Switcher

    @Test public void uaChooseFromList() {
        UaActivity a = Robolectric.buildActivity(UaActivity.class).setup().get();
        View root = a.getWindow().getDecorView();
        buttonWithText(root, "Choose from list").performClick();
        chooseItem(latest(), 0);
        assertEquals(UaManager.BUILTIN[0].ua, Prefs.s(app, Prefs.UA_CURRENT, ""));
        assertEquals("all", Prefs.s(app, Prefs.UA_MODE, ""));
        assertTrue(hasText(root, UaManager.BUILTIN[0].ua));
    }

    @Test public void uaModeRadio() {
        View root = Robolectric.buildActivity(UaActivity.class).setup().get().getWindow().getDecorView();
        TextView rb = findText(root, "Only websites in my list\nEverything else stays normal");
        assertNotNull(rb);
        rb.performClick();
        assertEquals("whitelist", Prefs.s(app, Prefs.UA_MODE, ""));
    }

    @Test public void uaAddRuleFlow() throws Exception {
        View root = Robolectric.buildActivity(UaActivity.class).setup().get().getWindow().getDecorView();
        buttonWithText(root, "Add a rule").performClick();
        AlertDialog d = latest();
        findEdit(d.getWindow().getDecorView()).setText("https://Games.Example.com/play");
        press(d, AlertDialog.BUTTON_POSITIVE);
        chooseItem(latest(), 1);                                     // first real preset
        assertEquals(UaManager.BUILTIN[0].ua, UaManager.rules(app).getString("games.example.com"));
        assertTrue(hasText(root, "games.example.com"));
    }

    @Test public void uaRandomSwitchPicksOne() {
        View root = Robolectric.buildActivity(UaActivity.class).setup().get().getWindow().getDecorView();
        clickRow(root, "New random user-agent each time you open FullStay");
        assertTrue(Prefs.b(app, Prefs.UA_RANDOM, false));
        assertEquals("all", Prefs.s(app, Prefs.UA_MODE, ""));
        assertNotNull(UaManager.globalUa(app));
    }

    @Test public void uaExportImportKeepsWebsiteList() throws Exception {
        ActivityController<UaActivity> c = Robolectric.buildActivity(UaActivity.class).setup();
        View root = c.get().getWindow().getDecorView();
        EditText sites = findEdit(root);
        assertNotNull(sites);
        sites.setText("keep.me\nand.me");
        UaManager.putRule(app, "r.test", "UA/9");
        clickRow(root, "Copy settings to clipboard");
        ClipboardManager cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
        String json = cm.getPrimaryClip().getItemAt(0).getText().toString();
        assertTrue(json.contains("keep.me"));

        // wipe, then import through the screen
        UaManager.reset(app);
        Prefs.p(app).edit().putString(Prefs.UA_SITES, "").commit();
        ActivityController<UaActivity> c2 = Robolectric.buildActivity(UaActivity.class).setup();
        clickRow(c2.get().getWindow().getDecorView(), "Import settings from clipboard");
        c2.pause();                                                  // the screen rebuilds; must not save old text
        assertEquals("keep.me\nand.me", Prefs.s(app, Prefs.UA_SITES, ""));
        assertEquals("UA/9", UaManager.rules(app).getString("r.test"));
    }

    @Test public void uaResetKeepsResetList() {
        Prefs.p(app).edit().putString(Prefs.UA_SITES, "old.site").commit();
        ActivityController<UaActivity> c = Robolectric.buildActivity(UaActivity.class).setup();
        clickRow(c.get().getWindow().getDecorView(), "Reset all user-agent settings");
        press(latest(), AlertDialog.BUTTON_POSITIVE);
        c.pause();
        assertEquals("", Prefs.s(app, Prefs.UA_SITES, ""));
    }

    // ---- Tile & screenshots

    @Test public void quickSettingsTileToggles() {
        ButtonTileService t = Robolectric.setupService(ButtonTileService.class);
        t.onStartListening();
        t.onClick();
        assertFalse(Prefs.keysEnabled(app));
        t.onClick();
        assertTrue(Prefs.keysEnabled(app));
    }

    @Test public void screenshotFileName() {
        assertTrue(ScreenshotSaver.fileName(0).matches("Screenshot_\\d{8}_\\d{6}_\\d{3}_FullStay\\.png"));
    }

    @Test public void screenshotSavedToGallery() {
        Bitmap b = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        Uri u = ScreenshotSaver.save(app, b);
        assertNotNull(u);
        assertTrue(u.toString().startsWith("content://media/"));
        assertEquals(null, ScreenshotSaver.save(app, null));
    }
}
