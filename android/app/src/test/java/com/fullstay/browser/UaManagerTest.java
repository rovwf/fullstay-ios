package com.fullstay.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.webkit.UserAgentMetadata;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class UaManagerTest {
    private Context c;
    private static final String CHROME_WIN = UaManager.BUILTIN[0].ua;
    private static final String IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Mobile/15E148 Safari/604.1";

    @Before public void setUp() {
        c = ApplicationProvider.getApplicationContext();
        Prefs.p(c).edit().clear().commit();
        UaManager.reset(c);
    }

    private void set(String mode, String ua) {
        Prefs.p(c).edit().putString(Prefs.UA_MODE, mode).putString(Prefs.UA_CURRENT, ua).commit();
    }

    @Test public void offByDefault() {
        assertNull(UaManager.uaForUrl(c, "https://example.com"));
        assertEquals("Off", UaManager.menuLabel(c));
    }

    @Test public void allSites() {
        set("all", CHROME_WIN);
        assertEquals(CHROME_WIN, UaManager.uaForUrl(c, "https://example.com/a"));
        assertEquals(CHROME_WIN, UaManager.uaForUrl(c, "about:blank"));
    }

    @Test public void onlyListedSites() {
        set("whitelist", CHROME_WIN);
        Prefs.p(c).edit().putString(Prefs.UA_SITES, "example.com\n*.game.io").commit();
        assertEquals(CHROME_WIN, UaManager.uaForUrl(c, "https://www.example.com/"));
        assertEquals(CHROME_WIN, UaManager.uaForUrl(c, "https://play.game.io/"));
        assertNull(UaManager.uaForUrl(c, "https://other.org/"));
        assertNull(UaManager.uaForUrl(c, "about:blank"));
    }

    @Test public void allExceptListed() {
        set("blacklist", CHROME_WIN);
        Prefs.p(c).edit().putString(Prefs.UA_SITES, "example.com, bank.test").commit();
        assertNull(UaManager.uaForUrl(c, "https://example.com/"));
        assertNull(UaManager.uaForUrl(c, "https://login.bank.test/"));
        assertEquals(CHROME_WIN, UaManager.uaForUrl(c, "https://other.org/"));
    }

    @Test public void rulesWinAndMostSpecificRuleWins() {
        set("all", CHROME_WIN);
        UaManager.putRule(c, "example.com", IPHONE);
        UaManager.putRule(c, "https://m.example.com/path", "");
        assertEquals(IPHONE, UaManager.uaForUrl(c, "https://www.example.com/"));
        assertNull(UaManager.uaForUrl(c, "https://m.example.com/"));      // "" = normal user-agent
        assertEquals(CHROME_WIN, UaManager.uaForUrl(c, "https://x.org/"));
        UaManager.removeRule(c, "example.com");
        assertEquals(CHROME_WIN, UaManager.uaForUrl(c, "https://www.example.com/"));
    }

    @Test public void rulesIgnoredWhenOff() {
        UaManager.putRule(c, "example.com", IPHONE);
        assertNull(UaManager.uaForUrl(c, "https://example.com/"));
    }

    @Test public void noUserAgentChosenMeansNoChange() {
        set("all", "");
        assertNull(UaManager.uaForUrl(c, "https://example.com/"));
    }

    @Test public void selectTurnsSwitcherOnAndRandomOff() {
        Prefs.p(c).edit().putBoolean(Prefs.UA_RANDOM, true).commit();
        UaManager.select(c, "Chrome \u2014 Windows", CHROME_WIN);
        assertEquals("all", Prefs.s(c, Prefs.UA_MODE, ""));
        assertFalse(Prefs.b(c, Prefs.UA_RANDOM, true));
        assertEquals("Chrome \u2014 Windows", UaManager.menuLabel(c));
        // Selecting again keeps a non-off mode as it is
        Prefs.p(c).edit().putString(Prefs.UA_MODE, "whitelist").commit();
        UaManager.select(c, "x", IPHONE);
        assertEquals("whitelist", Prefs.s(c, Prefs.UA_MODE, ""));
    }

    @Test public void randomRespectsPoolAndSkipsBotsAndLegacy() {
        Prefs.p(c).edit().putString(Prefs.UA_MODE, "all").putBoolean(Prefs.UA_RANDOM, true).commit();
        for (String group : new String[]{"desktop", "mobile", "any"}) {
            Prefs.p(c).edit().putString(Prefs.UA_RANDOM_GROUP, group).commit();
            for (int i = 0; i < 40; i++) {
                UaManager.reroll(c);
                String ua = UaManager.globalUa(c);
                assertNotNull(ua);
                assertFalse(ua.contains("bot"));
                assertFalse(ua.contains("Trident"));
                if (group.equals("desktop")) assertTrue(UaManager.isDesktop(ua));
                if (group.equals("mobile")) assertFalse(UaManager.isDesktop(ua));
            }
        }
        assertTrue(UaManager.menuLabel(c).startsWith("Random: "));
    }

    @Test public void customUserAgentsAppearInList() {
        int before = UaManager.allPresets(c).size();
        UaManager.addCustom(c, "My TV", "Mozilla/5.0 (SMART-TV; Linux) Test/1");
        assertEquals(before + 1, UaManager.allPresets(c).size());
        assertEquals("\u2605 My TV", UaManager.nameFor(c, "Mozilla/5.0 (SMART-TV; Linux) Test/1"));
        UaManager.removeCustom(c, 0);
        assertEquals(before, UaManager.allPresets(c).size());
    }

    @Test public void exportImportRoundTrip() throws Exception {
        set("blacklist", IPHONE);
        Prefs.p(c).edit().putString(Prefs.UA_SITES, "a.com\nb.com").putBoolean(Prefs.UA_SPOOF_JS, false).commit();
        UaManager.putRule(c, "x.com", CHROME_WIN);
        UaManager.addCustom(c, "Mine", "UA/1");
        String json = UaManager.exportJson(c);
        UaManager.reset(c);
        Prefs.p(c).edit().remove(Prefs.UA_SITES).commit();
        assertNull(UaManager.uaForUrl(c, "https://z.com"));
        UaManager.importJson(c, json);
        assertEquals("blacklist", Prefs.s(c, Prefs.UA_MODE, ""));
        assertEquals("a.com\nb.com", Prefs.s(c, Prefs.UA_SITES, ""));
        assertFalse(Prefs.b(c, Prefs.UA_SPOOF_JS, true));
        assertEquals(CHROME_WIN, UaManager.uaForUrl(c, "https://x.com"));
        assertEquals(1, UaManager.customs(c).length());
    }

    @Test(expected = org.json.JSONException.class)
    public void importRejectsGarbage() throws Exception {
        UaManager.importJson(c, "hello");
    }

    @Test public void importRejectsOtherJson() {
        for (String json : new String[]{"{}", "{\"name\":\"x\"}", "[1,2]"}) {
            try { UaManager.importJson(c, json); org.junit.Assert.fail(json); } catch (org.json.JSONException expected) { }
        }
        assertEquals("off", Prefs.s(c, Prefs.UA_MODE, "off"));
    }

    @Test public void importRejectsInvalidValuesAndChangesNothing() {
        for (String json : new String[]{"{\"ua_mode\":\"sometimes\",\"ua_sites\":\"x.com\"}",
                "{\"ua_random_group\":\"toasters\"}"}) {
            try { UaManager.importJson(c, json); org.junit.Assert.fail(json); } catch (org.json.JSONException expected) { }
        }
        assertEquals("off", Prefs.s(c, Prefs.UA_MODE, "off"));
        assertEquals("", Prefs.s(c, Prefs.UA_SITES, ""));
    }

    @Test public void operaAndSamsungReportTheirOwnVersion() {
        for (UaManager.Preset p : UaManager.BUILTIN) {
            if (!p.browser.equals("Opera") && !p.browser.equals("Samsung Internet")) continue;
            String own = p.ua.replaceAll(".*(?:OPR|SamsungBrowser)/(\\d+).*", "$1");
            boolean found = false;
            for (UserAgentMetadata.BrandVersion b : UaManager.metaFor(p.ua).getBrandVersionList()) {
                if (b.getBrand().equals(p.browser)) { assertEquals(own, b.getMajorVersion()); found = true; }
            }
            assertTrue(p.browser, found);
        }
    }

    @Test public void desktopDetection() {
        assertTrue(UaManager.isDesktop(CHROME_WIN));
        assertFalse(UaManager.isDesktop(IPHONE));
        for (UaManager.Preset p : UaManager.BUILTIN) {
            if (!p.special) assertEquals(p.label(), p.desktop, UaManager.isDesktop(p.ua));
        }
    }

    @Test public void clientHintsMatchUserAgent() {
        UserAgentMetadata m = UaManager.metaFor(CHROME_WIN);
        assertEquals("Windows", m.getPlatform());
        assertFalse(m.isMobile());
        boolean chrome = false;
        for (UserAgentMetadata.BrandVersion b : m.getBrandVersionList()) chrome |= b.getBrand().equals("Google Chrome");
        assertTrue(chrome);
        UserAgentMetadata a = UaManager.metaFor(UaManager.BUILTIN[4].ua);   // Chrome Android phone
        assertEquals("Android", a.getPlatform());
        assertTrue(a.isMobile());
        assertEquals("iOS", UaManager.metaFor(IPHONE).getPlatform());
    }

    @Test public void hostNormalizing() {
        assertEquals("example.com", UaManager.normalizeHost(" HTTPS://Example.com/path?x "));
        assertEquals("game.io", UaManager.normalizeHost("*.game.io"));
        assertEquals("", UaManager.normalizeHost("   "));
        assertFalse(UaManager.matches("notexample.com", "example.com"));
        assertTrue(UaManager.matches("a.b.example.com", "example.com"));
    }
}
