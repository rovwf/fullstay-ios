package com.fullstay.browser;

import static android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME;
import static android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS;
import static android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.media.AudioManager;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowToast;

import java.time.Duration;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class ButtonServiceTest {
    private static final String GAME = "com.example.game";
    private static final String CHAT = "com.example.chat";
    private static final String LAUNCHER = "com.example.launcher";
    private Application app;
    private ButtonService svc;

    @Before public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        Prefs.p(app).edit().clear().commit();
        App.setResumedCountForTest(0);
        install(GAME, GAME + ".MainActivity", Intent.CATEGORY_LAUNCHER);
        install(CHAT, CHAT + ".ChatActivity", Intent.CATEGORY_LAUNCHER);
        install(LAUNCHER, LAUNCHER + ".Home", Intent.CATEGORY_HOME);
        svc = Robolectric.setupService(ButtonService.class);
        svc.onServiceConnected();
    }

    private void install(String pkg, String cls, String category) throws Exception {
        ShadowPackageManager spm = shadowOf(app.getPackageManager());
        PackageInfo pi = new PackageInfo();
        pi.packageName = pkg;
        pi.applicationInfo = new ApplicationInfo();
        pi.applicationInfo.packageName = pkg;
        spm.installPackage(pi);
        ComponentName cn = new ComponentName(pkg, cls);
        spm.addActivityIfNotPresent(cn);
        IntentFilter f = new IntentFilter(Intent.ACTION_MAIN);
        f.addCategory(category);
        if (category.equals(Intent.CATEGORY_HOME)) f.addCategory(Intent.CATEGORY_DEFAULT);
        spm.addIntentFilterForActivity(cn, f);
    }

    private static AccessibilityEvent window(String pkg, String cls) {
        AccessibilityEvent e = new AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        e.setPackageName(pkg);
        e.setClassName(cls);
        return e;
    }

    private boolean press(int key) {
        long t = SystemClock.uptimeMillis();
        boolean down = svc.onKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, key, 0));
        boolean up = svc.onKeyEvent(new KeyEvent(t, t + 60, KeyEvent.ACTION_UP, key, 0));
        assertEquals("down and up must be treated the same way", down, up);
        return down;
    }

    private List<Integer> actions() { return shadowOf(svc).getGlobalActionsPerformed(); }

    // ---------------------------------------------------------------- last-app tracking

    @Test public void remembersLastRealApp() {
        svc.onAccessibilityEvent(window(GAME, GAME + ".MainActivity"));
        assertEquals(GAME, svc.lastOtherForTest());
        assertEquals(GAME, Prefs.s(app, Prefs.LAST_OTHER, null));
        svc.onAccessibilityEvent(window(CHAT, CHAT + ".ChatActivity"));
        assertEquals(CHAT, svc.lastOtherForTest());
    }

    @Test public void ignoresHomeScreenPopupsAndItself() {
        svc.onAccessibilityEvent(window(GAME, GAME + ".MainActivity"));
        svc.onAccessibilityEvent(window(LAUNCHER, LAUNCHER + ".Home"));
        svc.onAccessibilityEvent(window(CHAT, "android.app.Dialog"));          // not an app screen
        svc.onAccessibilityEvent(window(app.getPackageName(), MainActivity.class.getName()));
        svc.onAccessibilityEvent(window("com.android.systemui", "com.android.systemui.Shade"));
        svc.onAccessibilityEvent(window("com.unknown", "com.unknown.X"));        // not installed
        AccessibilityEvent other = new AccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
        other.setPackageName(CHAT);
        other.setClassName(CHAT + ".ChatActivity");
        svc.onAccessibilityEvent(other);
        assertEquals(GAME, svc.lastOtherForTest());
    }

    @Test public void settingsAppCountsDespiteItsBackupHomeScreen() throws Exception {
        String settings = "com.example.settings";
        install(settings, settings + ".Settings", Intent.CATEGORY_LAUNCHER);
        ShadowPackageManager spm = shadowOf(app.getPackageManager());
        ComponentName fallback = new ComponentName(settings, settings + ".FallbackHome");
        spm.addActivityIfNotPresent(fallback);
        IntentFilter f = new IntentFilter(Intent.ACTION_MAIN);
        f.addCategory(Intent.CATEGORY_HOME);
        f.addCategory(Intent.CATEGORY_DEFAULT);
        f.setPriority(-1000);
        spm.addIntentFilterForActivity(fallback, f);
        ButtonService fresh = Robolectric.setupService(ButtonService.class);
        fresh.onServiceConnected();
        fresh.onAccessibilityEvent(window(settings, settings + ".Settings"));
        assertEquals(settings, fresh.lastOtherForTest());
        fresh.onAccessibilityEvent(window(LAUNCHER, LAUNCHER + ".Home"));   // real home screen still ignored
        assertEquals(settings, fresh.lastOtherForTest());
    }

    @Test public void forgettingTheLastAppWorksImmediately() {
        svc.onAccessibilityEvent(window(GAME, GAME + ".MainActivity"));
        Prefs.p(app).edit().remove(Prefs.LAST_OTHER).commit();        // Settings -> Last app -> Forget it
        App.setResumedCountForTest(1);
        assertTrue(press(KeyEvent.KEYCODE_VOLUME_DOWN));
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(shadowOf(app).getNextStartedActivity());
        assertTrue(ShadowToast.getTextOfLatestToast().startsWith("Open another app once"));
        svc.onAccessibilityEvent(window(GAME, GAME + ".MainActivity")); // same app again is remembered again
        assertEquals(GAME, svc.lastOtherForTest());
    }

    @Test public void uninstalledLastAppIsForgottenWithAMessage() {
        // (Robolectric's removePackage leaves stale entries that crash its own lookup, so simulate
        //  "was used, then uninstalled" with a saved package that is not installed.)
        Prefs.p(app).edit().putString(Prefs.LAST_OTHER, "com.example.uninstalled").commit();
        App.setResumedCountForTest(1);
        assertTrue(press(KeyEvent.KEYCODE_VOLUME_DOWN));
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(ShadowToast.getTextOfLatestToast().contains("isn't installed anymore"));
        assertNull(svc.lastOtherForTest());
    }

    /** The other FullStay build's package, whichever build these tests run in. */
    private String sibling() {
        return app.getPackageName().equals(ButtonService.ORIGINAL) ? ButtonService.ORIGINAL + ".exitfs" : ButtonService.ORIGINAL;
    }

    @Test public void otherFullStayBuildCountsAsTheBrowser() throws Exception {
        String sib = sibling();
        install(sib, MainActivity.class.getName(), Intent.CATEGORY_LAUNCHER);
        svc.onAccessibilityEvent(window(GAME, GAME + ".MainActivity"));
        svc.onAccessibilityEvent(window(sib, MainActivity.class.getName()));   // the other FullStay is in front
        assertEquals("never counts as 'the last app'", GAME, svc.lastOtherForTest());
        App.setResumedCountForTest(0);
        assertTrue(press(KeyEvent.KEYCODE_VOLUME_DOWN));
        Intent i = shadowOf(app).getNextStartedActivity();
        assertEquals(GAME, i.getComponent() != null ? i.getComponent().getPackageName() : i.getPackage());
    }

    @Test public void fromAnotherAppGoesToTheFullStayYouUsedLast() throws Exception {
        String sib = sibling();
        install(sib, MainActivity.class.getName(), Intent.CATEGORY_LAUNCHER);
        App.setResumedCountForTest(0);
        svc.onAccessibilityEvent(window(sib, MainActivity.class.getName()));
        svc.onAccessibilityEvent(window(GAME, GAME + ".MainActivity"));
        press(KeyEvent.KEYCODE_VOLUME_DOWN);
        assertEquals(sib, shadowOf(app).getNextStartedActivity().getComponent().getPackageName());

        svc.onAccessibilityEvent(window(app.getPackageName(), MainActivity.class.getName()));
        svc.onAccessibilityEvent(window(GAME, GAME + ".MainActivity"));
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        press(KeyEvent.KEYCODE_VOLUME_DOWN);
        assertEquals(app.getPackageName(), shadowOf(app).getNextStartedActivity().getComponent().getPackageName());
    }

    @Test public void withBothButtonServicesOnOnlyOneActs() {
        String sib = sibling();
        Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                sib + "/" + ButtonService.class.getName() + ":" + app.getPackageName() + "/" + ButtonService.class.getName());
        boolean iAct = app.getPackageName().compareTo(sib) < 0;
        assertEquals(iAct, press(KeyEvent.KEYCODE_VOLUME_UP));
        assertEquals(iAct ? 1 : 0, actions().size());
    }

    @Test public void eitherBuildsServiceCountsAsSetUp() {
        Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                sibling() + "/" + ButtonService.class.getName());
        assertTrue(ButtonService.isEnabled(app));
        Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                "com.other.app/" + ButtonService.class.getName());
        assertFalse(ButtonService.isEnabled(app));
    }

    @Test public void lastAppSurvivesServiceRestart() {
        svc.onAccessibilityEvent(window(GAME, GAME + ".MainActivity"));
        ButtonService again = Robolectric.setupService(ButtonService.class);
        again.onServiceConnected();
        assertEquals(GAME, again.lastOtherForTest());
    }

    // ---------------------------------------------------------------- volume up

    @Test public void volumeUpTakesScreenshot() {
        assertTrue(press(KeyEvent.KEYCODE_VOLUME_UP));
        assertEquals(List.of(GLOBAL_ACTION_TAKE_SCREENSHOT), actions());
    }

    @Test public void holdingVolumeUpTakesOnlyOne() {
        long t = SystemClock.uptimeMillis();
        assertTrue(svc.onKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP, 0)));
        for (int r = 1; r <= 10; r++) {
            assertTrue(svc.onKeyEvent(new KeyEvent(t, t + r * 50, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP, r)));
        }
        assertTrue(svc.onKeyEvent(new KeyEvent(t, t + 600, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP, 0)));
        assertEquals(1, actions().size());
    }

    @Test public void accidentalDoublePressIgnoredButNextPressWorks() {
        press(KeyEvent.KEYCODE_VOLUME_UP);
        press(KeyEvent.KEYCODE_VOLUME_UP);
        assertEquals(1, actions().size());
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        press(KeyEvent.KEYCODE_VOLUME_UP);
        assertEquals(2, actions().size());
    }

    @Test public void silentStyleDoesNotUseAndroidScreenshot() {
        Prefs.p(app).edit().putString(Prefs.SHOT_STYLE, "silent").commit();
        assertTrue(press(KeyEvent.KEYCODE_VOLUME_UP));
        assertFalse(actions().contains(GLOBAL_ACTION_TAKE_SCREENSHOT));
    }

    // ---------------------------------------------------------------- volume down

    @Test public void volumeDownInBrowserOpensLastApp() {
        svc.onAccessibilityEvent(window(GAME, GAME + ".MainActivity"));
        App.setResumedCountForTest(1);                                  // FullStay is in front
        assertTrue(press(KeyEvent.KEYCODE_VOLUME_DOWN));
        Intent i = shadowOf(app).getNextStartedActivity();
        assertNotNull(i);
        assertEquals(GAME, i.getComponent() != null ? i.getComponent().getPackageName() : i.getPackage());
        assertTrue((i.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertTrue((i.getFlags() & Intent.FLAG_ACTIVITY_NO_ANIMATION) != 0);
    }

    @Test public void volumeDownInAnyOtherAppOpensBrowser() {
        svc.onAccessibilityEvent(window(GAME, GAME + ".MainActivity"));
        App.setResumedCountForTest(0);                                  // some other app is in front
        assertTrue(press(KeyEvent.KEYCODE_VOLUME_DOWN));
        Intent i = shadowOf(app).getNextStartedActivity();
        assertNotNull(i);
        assertEquals(app.getPackageName(), i.getComponent().getPackageName());
        assertEquals(MainActivity.class.getName(), i.getComponent().getClassName());
        assertNull(shadowOf(app).getNextStartedActivity());             // exactly one launch per press
    }

    @Test public void animationKeptWhenInstantIsOff() {
        Prefs.p(app).edit().putBoolean(Prefs.INSTANT, false).commit();
        press(KeyEvent.KEYCODE_VOLUME_DOWN);
        Intent i = shadowOf(app).getNextStartedActivity();
        assertEquals(0, i.getFlags() & Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    @Test public void noLastAppYetGoesHomeAndExplains() {
        App.setResumedCountForTest(1);
        assertTrue(press(KeyEvent.KEYCODE_VOLUME_DOWN));
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(actions().contains(GLOBAL_ACTION_HOME));
        assertTrue(ShadowToast.getTextOfLatestToast().startsWith("Open another app once"));
    }

    @Test public void recentsMethodDoubleTapsRecents() {
        Prefs.p(app).edit().putString(Prefs.SWITCH_METHOD, "recents").commit();
        App.setResumedCountForTest(1);
        press(KeyEvent.KEYCODE_VOLUME_DOWN);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        assertEquals(List.of(GLOBAL_ACTION_RECENTS, GLOBAL_ACTION_RECENTS), actions());
    }

    // ---------------------------------------------------------------- normal volume where it matters

    @Test public void masterSwitchOffGivesNormalVolume() {
        Prefs.p(app).edit().putBoolean(Prefs.KEYS_ENABLED, false).commit();
        assertFalse(press(KeyEvent.KEYCODE_VOLUME_UP));
        assertFalse(press(KeyEvent.KEYCODE_VOLUME_DOWN));
        assertTrue(actions().isEmpty());
        assertNull(shadowOf(app).getNextStartedActivity());
    }

    @Test public void eachButtonCanBeTurnedOffSeparately() {
        Prefs.p(app).edit().putBoolean(Prefs.UP_SCREENSHOT, false).commit();
        assertFalse(press(KeyEvent.KEYCODE_VOLUME_UP));
        assertTrue(press(KeyEvent.KEYCODE_VOLUME_DOWN));
        Prefs.p(app).edit().putBoolean(Prefs.UP_SCREENSHOT, true).putBoolean(Prefs.DOWN_SWITCH, false).commit();
        assertTrue(press(KeyEvent.KEYCODE_VOLUME_UP));
        assertFalse(press(KeyEvent.KEYCODE_VOLUME_DOWN));
    }

    @Test public void lockScreenGivesNormalVolume() {
        KeyguardManager km = (KeyguardManager) app.getSystemService(Context.KEYGUARD_SERVICE);
        shadowOf(km).setKeyguardLocked(true);
        assertFalse(press(KeyEvent.KEYCODE_VOLUME_UP));
        assertFalse(press(KeyEvent.KEYCODE_VOLUME_DOWN));
        assertTrue(actions().isEmpty());
    }

    @Test public void callsGiveNormalVolume() {
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        for (int mode : new int[]{AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE}) {
            am.setMode(mode);
            assertFalse(press(KeyEvent.KEYCODE_VOLUME_DOWN));
        }
        am.setMode(AudioManager.MODE_NORMAL);
        assertTrue(press(KeyEvent.KEYCODE_VOLUME_DOWN));
    }

    @Test public void otherButtonsUntouched() {
        assertFalse(press(KeyEvent.KEYCODE_POWER));
        assertFalse(press(KeyEvent.KEYCODE_VOLUME_MUTE));
        assertFalse(press(KeyEvent.KEYCODE_BACK));
    }

    @Test public void stopsCleanly() {
        assertTrue(ButtonService.running);
        svc.onUnbind(new Intent());
        assertFalse(ButtonService.running);
        assertNull(ButtonService.get());
    }
}
