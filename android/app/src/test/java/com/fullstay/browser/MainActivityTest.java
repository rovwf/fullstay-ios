package com.fullstay.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;

import androidx.test.core.app.ApplicationProvider;

import com.google.android.material.bottomsheet.BottomSheetDialog;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowSystemClock;

import java.time.Duration;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class MainActivityTest {
    private Application app;

    @Before public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        Prefs.p(app).edit().clear().putBoolean(Prefs.SETUP_SHOWN, true).commit();
        UaManager.reset(app);
        ButtonService.running = false;
    }

    private void setExit(boolean on) { Prefs.p(app).edit().putBoolean(Prefs.REMOTE_EXIT, on).commit(); }

    private ActivityController<MainActivity> start(Intent i) {
        return (i == null ? Robolectric.buildActivity(MainActivity.class) : Robolectric.buildActivity(MainActivity.class, i)).setup();
    }

    private static String lastUrl(MainActivity a) { return shadowOf(a.webForTest()).getLastLoadedUrl(); }

    private static View find(View v, String desc) {
        if (desc.contentEquals(v.getContentDescription() == null ? "" : v.getContentDescription())) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View f = find(g.getChildAt(i), desc);
                if (f != null) return f;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- start & navigation

    @Test public void opensHomePage() {
        MainActivity a = start(null).get();
        assertEquals(Prefs.DEF_HOME, lastUrl(a));
        assertEquals(View.VISIBLE, a.topBarForTest().getVisibility());
        assertFalse(a.isFullscreenUi());
    }

    @Test public void opensLinkFromAnotherApp() {
        MainActivity a = start(new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/page"))).get();
        assertEquals("https://example.com/page", lastUrl(a));
    }

    @Test public void otherAppsCannotRunScriptsOrOpenFiles() {
        for (String bad : new String[]{"javascript:alert(document.cookie)", "file:///sdcard/secret.html",
                "content://com.example.provider/x", "intent:#Intent;end"}) {
            MainActivity a = start(new Intent(Intent.ACTION_VIEW, Uri.parse(bad))).get();
            assertEquals(bad, Prefs.DEF_HOME, lastUrl(a));
            a.onNewIntent(new Intent(Intent.ACTION_VIEW, Uri.parse(bad)));
            assertEquals(bad, Prefs.DEF_HOME, lastUrl(a));
        }
    }

    private static android.webkit.WebResourceRequest request(String url, boolean gesture) {
        return new android.webkit.WebResourceRequest() {
            @Override public Uri getUrl() { return Uri.parse(url); }
            @Override public boolean isForMainFrame() { return true; }
            @Override public boolean isRedirect() { return false; }
            @Override public boolean hasGesture() { return gesture; }
            @Override public String getMethod() { return "GET"; }
            @Override public java.util.Map<String, String> getRequestHeaders() { return java.util.Collections.emptyMap(); }
        };
    }

    @Test public void pagesCannotOpenAppsWithoutATap() {
        MainActivity a = start(null).get();
        android.webkit.WebViewClient client = shadowOf(a.webForTest()).getWebViewClient();
        assertTrue(client.shouldOverrideUrlLoading(a.webForTest(), request("market://details?id=x.y", false)));
        assertEquals("nothing opened without a tap", null, shadowOf(a).getNextStartedActivity());
        assertTrue(client.shouldOverrideUrlLoading(a.webForTest(), request("market://details?id=x.y", true)));
        Intent opened = shadowOf(a).getNextStartedActivity();
        assertNotNull("opened after a tap", opened);
        assertEquals("market://details?id=x.y", opened.getDataString());
        assertFalse(client.shouldOverrideUrlLoading(a.webForTest(), request("https://example.com/", false)));
    }

    @Test public void reopensLastPage() {
        Prefs.p(app).edit().putString(Prefs.LAST_URL, "https://last.example/").commit();
        assertEquals("https://last.example/", lastUrl(start(null).get()));
        Prefs.p(app).edit().putBoolean(Prefs.RESTORE_LAST, false).commit();
        assertEquals(Prefs.DEF_HOME, lastUrl(start(null).get()));
    }

    @Test public void addressBarSearchesAndOpensSites() {
        MainActivity a = start(null).get();
        a.go("cute cats");
        assertEquals("https://duckduckgo.com/?q=cute+cats", lastUrl(a));
        a.go("example.org");
        assertEquals("https://example.org", lastUrl(a));
        Prefs.p(app).edit().putString(Prefs.SEARCH, "https://www.google.com/search?q=%s").commit();
        a.go("x");
        assertEquals("https://www.google.com/search?q=x", lastUrl(a));
    }

    @Test public void startsInFullscreenWhenAsked() {
        Prefs.p(app).edit().putBoolean(Prefs.START_FS, true).commit();
        MainActivity a = start(null).get();
        assertTrue(a.isFullscreenUi());
        assertEquals(View.GONE, a.topBarForTest().getVisibility());
    }

    // ---------------------------------------------------------------- fullscreen

    @Test public void menuFullscreenAndBackToLeave() {
        MainActivity a = start(null).get();
        a.enterAppFullscreen();
        assertTrue(a.isFullscreenUi());
        assertEquals(View.GONE, a.topBarForTest().getVisibility());
        a.getOnBackPressedDispatcher().onBackPressed();
        assertFalse(a.isFullscreenUi());
        assertEquals(View.VISIBLE, a.topBarForTest().getVisibility());
    }

    @Test public void pageFullscreenSurvivesSwitchingApps() {
        setExit(false);
        ActivityController<MainActivity> c = start(null);
        MainActivity a = c.get();
        WebChromeClient chrome = a.chromeForTest();
        chrome.onShowCustomView(new View(a), () -> { });
        assertTrue(a.isFullscreenUi());

        c.pause().stop();                    // you switch to another app...
        chrome.onHideCustomView();           // ...and Android kicks the page out of fullscreen
        ShadowSystemClock.advanceBy(Duration.ofMinutes(5));
        c.restart().resume();                // you come back much later

        assertTrue("must still be fullscreen", a.isFullscreenUi());
        assertTrue(a.autoFullscreenForTest());
        assertEquals(View.GONE, a.topBarForTest().getVisibility());

        a.getOnBackPressedDispatcher().onBackPressed();
        assertFalse(a.isFullscreenUi());
    }

    @Test public void forcedExitRightAfterReturningIsAlsoCaught() {
        setExit(false);
        ActivityController<MainActivity> c = start(null);
        MainActivity a = c.get();
        a.chromeForTest().onShowCustomView(new View(a), () -> { });
        c.pause().stop();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30));
        c.restart().resume();
        ShadowSystemClock.advanceBy(Duration.ofMillis(500));
        a.chromeForTest().onHideCustomView();   // exit arrives just after coming back
        assertTrue(a.isFullscreenUi());
    }

    @Test public void normalBuildKeepsMenuFullscreenWhenYouComeBack() {
        setExit(false);
        ActivityController<MainActivity> c = start(null);
        MainActivity a = c.get();
        a.enterAppFullscreen();
        c.pause().stop();
        c.restart().resume();
        assertTrue(a.isFullscreenUi());
    }

    @Test public void exitBuildAlwaysLeavesFullscreenWhenYouComeBack() {
        setExit(true);
        ActivityController<MainActivity> c = start(null);
        MainActivity a = c.get();

        // 1) page's own fullscreen (video/game)
        boolean[] told = {false};
        a.chromeForTest().onShowCustomView(new View(a), () -> { told[0] = true; a.chromeForTest().onHideCustomView(); });
        c.pause().stop();
        c.restart().resume();
        assertTrue("page is told to leave fullscreen", told[0]);
        assertFalse(a.isFullscreenUi());
        assertEquals(View.VISIBLE, a.topBarForTest().getVisibility());

        // 2) menu fullscreen
        a.enterAppFullscreen();
        c.pause().stop();
        c.restart().resume();
        assertFalse(a.isFullscreenUi());
        assertEquals(View.VISIBLE, a.topBarForTest().getVisibility());

        // 3) Android forced the page out while you were away
        a.chromeForTest().onShowCustomView(new View(a), () -> { });
        c.pause().stop();
        a.chromeForTest().onHideCustomView();
        c.restart().resume();
        assertFalse(a.isFullscreenUi());
        assertEquals(View.VISIBLE, a.topBarForTest().getVisibility());
    }

    @Test public void fullscreenStaysWhileYouAreStillInTheApp() {
        ActivityController<MainActivity> c = start(null);
        MainActivity a = c.get();
        a.enterAppFullscreen();
        c.pause().resume();                  // a dialog or the notification shade is not "leaving"
        assertTrue(a.isFullscreenUi());
    }

    @Test public void exitFlagStopsPagesRestoringTheirOwnFullscreen() {
        setExit(true);
        MainActivity a = start(null).get();
        assertTrue("exit on: page told not to restore", a.keepScriptAppliedToPageForTest().contains("__fsNoRestore=true"));
        setExit(false);
        MainActivity b = start(null).get();
        assertFalse("exit off: no such instruction", b.keepScriptAppliedToPageForTest().contains("__fsNoRestore=true"));
    }

    @Test public void pageLeavingFullscreenItselfIsRespected() {
        MainActivity a = start(null).get();
        a.chromeForTest().onShowCustomView(new View(a), () -> { });
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10));    // no app switch anywhere near
        a.chromeForTest().onHideCustomView();                    // e.g. the video's own exit button
        assertFalse(a.isFullscreenUi());
        assertFalse(a.autoFullscreenForTest());
    }

    @Test public void backLeavesPageFullscreenAndTellsThePage() {
        MainActivity a = start(null).get();
        boolean[] told = {false};
        WebChromeClient.CustomViewCallback cb = () -> {
            told[0] = true;
            a.chromeForTest().onHideCustomView();               // what WebView does when told
        };
        a.chromeForTest().onShowCustomView(new View(a), cb);
        a.getOnBackPressedDispatcher().onBackPressed();          // right after entering: still counts as you
        assertTrue(told[0]);
        assertFalse(a.isFullscreenUi());
        assertFalse(a.autoFullscreenForTest());
    }

    @Test public void secondFullscreenRequestIsRefusedSafely() {
        MainActivity a = start(null).get();
        boolean[] refused = {false};
        a.chromeForTest().onShowCustomView(new View(a), () -> { });
        a.chromeForTest().onShowCustomView(new View(a), () -> refused[0] = true);
        assertTrue(refused[0]);
        assertTrue(a.isFullscreenUi());
    }

    @Test public void systemChangesNeverReloadThePage() throws Exception {
        android.content.pm.ActivityInfo info = app.getPackageManager()
                .getActivityInfo(new android.content.ComponentName(app, MainActivity.class), 0);
        int needed = android.content.pm.ActivityInfo.CONFIG_ORIENTATION | android.content.pm.ActivityInfo.CONFIG_SCREEN_SIZE
                | android.content.pm.ActivityInfo.CONFIG_UI_MODE | android.content.pm.ActivityInfo.CONFIG_FONT_SCALE
                | android.content.pm.ActivityInfo.CONFIG_FONT_WEIGHT_ADJUSTMENT | android.content.pm.ActivityInfo.CONFIG_COLOR_MODE
                | android.content.pm.ActivityInfo.CONFIG_GRAMMATICAL_GENDER | android.content.pm.ActivityInfo.CONFIG_DENSITY
                | android.content.pm.ActivityInfo.CONFIG_LOCALE | android.content.pm.ActivityInfo.CONFIG_KEYBOARD_HIDDEN;
        assertEquals(needed, info.configChanges & needed);
    }

    @Test public void keyboardDoesNotCoverFullscreenPages() {
        MainActivity a = start(null).get();
        a.chromeForTest().onShowCustomView(new View(a), () -> { });
        androidx.core.view.WindowInsetsCompat withKeyboard = new androidx.core.view.WindowInsetsCompat.Builder()
                .setInsets(androidx.core.view.WindowInsetsCompat.Type.ime(), androidx.core.graphics.Insets.of(0, 0, 0, 700))
                .build();
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(a.rootForTest(), withKeyboard);
        assertEquals(700, a.fullscreenBoxForTest().getPaddingBottom());
    }

    @Test public void backWithNothingToCloseKeepsThePageAlive() {
        ActivityController<MainActivity> c = start(null);
        MainActivity a = c.get();
        a.getOnBackPressedDispatcher().onBackPressed();
        assertFalse(a.isFinishing());
    }

    // ---------------------------------------------------------------- error screen

    @Test public void errorScreenSurvivesAnyCallbackOrder() {
        MainActivity a = start(null).get();
        android.webkit.WebViewClient client = shadowOf(a.webForTest()).getWebViewClient();
        a.showError("https://down.test/", "net::ERR_NAME_NOT_RESOLVED", false);
        client.onPageStarted(a.webForTest(), "https://down.test/", null);   // start reported after the error
        assertNotNull("error must stay", a.errorForTest());
        client.onPageStarted(a.webForTest(), "https://other.test/", null);  // a different page clears it
        assertEquals(null, a.errorForTest());
    }

    @Test public void certificateMessageIsNotOverwritten() {
        MainActivity a = start(null).get();
        a.showError("https://bad.test/", "This site's security certificate isn't trusted", true);
        a.showError("https://bad.test/", "net::ERR_FAILED", false);
        assertTrue(a.errorForTest().getText().toString().contains("certificate"));
        a.go("example.org");                                                 // navigating away clears it
        assertEquals(null, a.errorForTest());
    }

    // ---------------------------------------------------------------- user-agent

    @Test public void userAgentFollowsSettingsAndRules() {
        MainActivity a = start(null).get();
        String normal = a.webForTest().getSettings().getUserAgentString();
        String win = UaManager.BUILTIN[0].ua;
        UaManager.select(app, "Chrome \u2014 Windows", win);
        UaManager.putRule(app, "bank.test", "");
        a.load("https://example.com/");
        assertEquals(win, a.webForTest().getSettings().getUserAgentString());
        a.load("https://bank.test/");
        assertEquals(normal, a.webForTest().getSettings().getUserAgentString());
        assertNotEquals(win, normal);
    }

    @Test public void randomUserAgentChangesOnEachFreshStart() {
        Prefs.p(app).edit().putString(Prefs.UA_MODE, "all").putBoolean(Prefs.UA_RANDOM, true)
                .putString(Prefs.UA_RANDOM_GROUP, "desktop").commit();
        UaManager.reroll(app);
        assertTrue(UaManager.isDesktop(UaManager.globalUa(app)));
        Prefs.p(app).edit().putString(Prefs.UA_RANDOM_GROUP, "mobile").commit();   // no re-roll yet
        start(null);                                                               // open FullStay fresh
        assertFalse("a new one must be picked on a fresh start", UaManager.isDesktop(UaManager.globalUa(app)));
    }

    @Test public void normalUserAgentHidesWebViewMarker() {
        MainActivity a = start(null).get();
        assertFalse(a.webForTest().getSettings().getUserAgentString().contains("; wv)"));
    }

    // ---------------------------------------------------------------- volume keys without the service

    @Test public void volumeKeysWorkInsideAppWithoutService() {
        MainActivity a = start(null).get();
        KeyEvent up = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP);
        KeyEvent down = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN);
        org.robolectric.shadows.ShadowToast.reset();
        assertTrue(a.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, up));
        assertTrue(a.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP)));
        String toast = null;                                    // the in-app screenshot saves on a background thread
        for (int i = 0; i < 100 && toast == null; i++) {
            shadowOf(Looper.getMainLooper()).idle();
            toast = org.robolectric.shadows.ShadowToast.getTextOfLatestToast();
            if (toast == null) { try { Thread.sleep(20); } catch (InterruptedException ignored) { } }
        }
        assertNotNull("volume up must produce a screenshot result", toast);
        assertTrue(toast, toast.startsWith("Screenshot saved") || toast.startsWith("Couldn't"));
        assertTrue(a.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, down));
        shadowOf(Looper.getMainLooper()).idle();

        Prefs.p(app).edit().putBoolean(Prefs.KEYS_ENABLED, false).commit();
        assertFalse(a.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, down));   // normal volume again
    }

    /** The bug: with the service off (e.g. after Force stop), volume down only hid FullStay. Now it opens your last app. */
    @Test public void volumeDownInsideAppOpensTheLastAppWithoutService() throws Exception {
        String game = "com.example.game";
        org.robolectric.shadows.ShadowPackageManager spm = shadowOf(app.getPackageManager());
        android.content.pm.PackageInfo pi = new android.content.pm.PackageInfo();
        pi.packageName = game;
        pi.applicationInfo = new android.content.pm.ApplicationInfo();
        pi.applicationInfo.packageName = game;
        spm.installPackage(pi);
        android.content.ComponentName cn = new android.content.ComponentName(game, game + ".Main");
        spm.addActivityIfNotPresent(cn);
        android.content.IntentFilter f = new android.content.IntentFilter(Intent.ACTION_MAIN);
        f.addCategory(Intent.CATEGORY_LAUNCHER);
        spm.addIntentFilterForActivity(cn, f);
        Prefs.p(app).edit().putString(Prefs.LAST_OTHER, game).commit();
        MainActivity a = start(null).get();
        assertTrue(a.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)));
        Intent started = shadowOf(a).getNextStartedActivity();
        assertNotNull("volume down must open the last app", started);
        assertEquals(game, started.getComponent() != null ? started.getComponent().getPackageName() : started.getPackage());
        assertFalse(a.isFinishing());
    }

    @Test public void volumeDownInsideAppWithNoLastAppStaysAndSaysWhy() {
        MainActivity a = start(null).get();
        org.robolectric.shadows.ShadowToast.reset();
        assertTrue(a.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)));
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(shadowOf(a).getNextStartedActivity());
        assertTrue(org.robolectric.shadows.ShadowToast.getTextOfLatestToast().startsWith("Open another app once"));
        assertFalse(a.isFinishing());
    }

    @Test public void saysWhenAndroidSwitchedTheButtonServiceOff() {
        MainActivity.resetServiceNoticeForTest();
        Prefs.p(app).edit().putBoolean(Prefs.SERVICE_WAS_ON, true).commit();   // it ran before, now it's gone
        MainActivity a = start(null).get();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2));
        shadowOf(Looper.getMainLooper()).idle();
        Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull("a dialog must explain the buttons are off", d);
        assertTrue(d.isShowing());
        assertEquals("Open Accessibility", ((android.widget.TextView) d.findViewById(android.R.id.button1)).getText().toString());
        d.dismiss();
        assertFalse(a.isFinishing());
    }

    @Test public void staysQuietWhenTheServiceWasNeverOn() {
        MainActivity.resetServiceNoticeForTest();
        ShadowDialog.reset();
        start(null).get();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2));
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(ShadowDialog.getLatestDialog());
    }

    @Test public void turnsTheServiceBackOnByItselfWhenAllowed() {
        MainActivity.resetServiceNoticeForTest();
        ShadowDialog.reset();
        org.robolectric.shadows.ShadowToast.reset();
        shadowOf(app).grantPermissions(ButtonService.PERMISSION_WRITE_SECURE);
        android.content.ComponentName me = new android.content.ComponentName(app, ButtonService.class);
        android.provider.Settings.Secure.putString(app.getContentResolver(), android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                "com.other/.Svc");
        Prefs.p(app).edit().putBoolean(Prefs.SERVICE_WAS_ON, true).commit();
        start(null).get();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2));
        shadowOf(Looper.getMainLooper()).idle();
        String now = android.provider.Settings.Secure.getString(app.getContentResolver(), android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        assertEquals("com.other/.Svc:" + me.flattenToString(), now);
        assertNull("no dialog when it could fix it by itself", ShadowDialog.getLatestDialog());
        assertEquals("Volume buttons turned back on", org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
    }

    @Test public void serviceTakesPriorityOverInAppKeys() {
        MainActivity a = start(null).get();
        ButtonService.running = true;
        try {
            // The service either handled it already or deliberately passed it on (calls, lock screen).
            assertFalse(a.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)));
        } finally {
            ButtonService.running = false;
        }
    }

    // ---------------------------------------------------------------- menu & setup

    @Test public void menuOpensAndItsItemsWork() {
        MainActivity a = start(null).get();
        View menu = find(a.getWindow().getDecorView(), "Menu");
        assertNotNull(menu);
        menu.performClick();
        Dialog d = ShadowDialog.getLatestDialog();
        assertTrue(d instanceof BottomSheetDialog);
        assertTrue(d.isShowing());
        View fs = find(d.getWindow().getDecorView(), "Fullscreen");
        assertNotNull(fs);
        fs.performClick();
        assertFalse(d.isShowing());
        assertTrue(a.isFullscreenUi());
    }

    @Test public void menuSettingsOpensSettings() {
        MainActivity a = start(null).get();
        find(a.getWindow().getDecorView(), "Menu").performClick();
        find(ShadowDialog.getLatestDialog().getWindow().getDecorView(), "Settings").performClick();
        Intent next = shadowOf(a).getNextStartedActivity();
        assertEquals(SettingsActivity.class.getName(), next.getComponent().getClassName());
    }

    @Test public void setupHelpShownOnlyOnce() {
        Prefs.p(app).edit().putBoolean(Prefs.SETUP_SHOWN, false).commit();
        start(null);
        Dialog first = ShadowDialog.getLatestDialog();
        assertNotNull(first);
        assertTrue(first.isShowing());
        assertTrue(Prefs.b(app, Prefs.SETUP_SHOWN, false));
        first.dismiss();
        start(null);
        assertFalse(ShadowDialog.getLatestDialog().isShowing());   // no new dialog
    }

    @Test public void pageMadeDownloadsExplainThemselves() {
        MainActivity a = start(null).get();
        shadowOf(a.webForTest()).getDownloadListener()
                .onDownloadStart("blob:https://x.test/123", "UA", null, "image/png", 10);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(org.robolectric.shadows.ShadowToast.getTextOfLatestToast().contains("can't save that kind"));
    }

    @Test public void locationRequestsAreAnsweredNo() {
        MainActivity a = start(null).get();
        boolean[] answered = {false};
        a.chromeForTest().onGeolocationPermissionsShowPrompt("https://x.test", (origin, allow, retain) -> {
            answered[0] = true;
            assertFalse(allow);
        });
        assertTrue(answered[0]);
    }

    @Test public void updatingFromVersion1StillShowsSetupHelp() {
        Prefs.p(app).edit().remove(Prefs.SETUP_SHOWN).putBoolean("setup_shown", true).commit();  // v1 left this
        start(null);
        Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull(d);
        assertTrue(d.isShowing());
    }

    @Test public void survivesBeingClosedAndReopened() {
        ActivityController<MainActivity> c = start(null);
        MainActivity old = c.get();
        c.pause().stop().destroy();
        assertEquals("WebView detached before destroy", null, old.webForTest().getParent());
        MainActivity b = start(null).get();
        assertNotNull(b.webForTest());
        assertEquals(Prefs.DEF_HOME, lastUrl(b));
    }
}
