package com.fullstay.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UrlUtilTest {
    private static final String DDG = "https://duckduckgo.com/?q=%s";

    @Test public void addressesBecomeHttps() {
        assertEquals("https://example.com", UrlUtil.toUrl("example.com", DDG));
        assertEquals("https://www.example.co.uk/a?b=1", UrlUtil.toUrl("  www.example.co.uk/a?b=1 ", DDG));
        assertEquals("https://example.com:8443/x", UrlUtil.toUrl("example.com:8443/x", DDG));
    }

    @Test public void fullUrlsAndSpecialSchemesKept() {
        assertEquals("http://example.com", UrlUtil.toUrl("http://example.com", DDG));
        assertEquals("HTTPS://Example.com", UrlUtil.toUrl("HTTPS://Example.com", DDG));
        assertEquals("about:blank", UrlUtil.toUrl("about:blank", DDG));
    }

    @Test public void internationalDomainsOpenDirectly() {
        assertEquals("https://\u043f\u0440\u0438\u043c\u0435\u0440.\u0431\u0433", UrlUtil.toUrl("\u043f\u0440\u0438\u043c\u0435\u0440.\u0431\u0433", DDG));   // пример.бг
        assertEquals("https://m\u00fcnchen.de/x", UrlUtil.toUrl("m\u00fcnchen.de/x", DDG));
        assertEquals("https://duckduckgo.com/?q=%D0%B7%D0%B4%D1%80%D0%B0%D0%B2%D0%B5%D0%B9", UrlUtil.toUrl("\u0437\u0434\u0440\u0430\u0432\u0435\u0439", DDG));  // здравей = search
    }

    @Test public void localAddressesUseHttp() {
        assertEquals("http://localhost:3000", UrlUtil.toUrl("localhost:3000", DDG));
        assertEquals("http://192.168.1.1/admin", UrlUtil.toUrl("192.168.1.1/admin", DDG));
    }

    @Test public void wordsBecomeSearches() {
        assertEquals("https://duckduckgo.com/?q=cute+cats", UrlUtil.toUrl("cute cats", DDG));
        assertEquals("https://duckduckgo.com/?q=hello", UrlUtil.toUrl("hello", DDG));
        assertEquals("https://duckduckgo.com/?q=a%26b%3Dc", UrlUtil.toUrl("a&b=c", DDG));
        assertEquals("https://duckduckgo.com/?q=example.com+rules", UrlUtil.toUrl("example.com rules", DDG));
        assertEquals("https://s.test/find?x=hi", UrlUtil.toUrl("hi", "https://s.test/find?x="));
        assertEquals("https://duckduckgo.com/?q=hi", UrlUtil.toUrl("hi", null));
    }

    @Test public void addressOnlyForRealAddresses() {
        assertEquals("https://example.com", UrlUtil.toAddress("example.com"));
        assertEquals("about:blank", UrlUtil.toAddress("about:blank"));
        assertNull(UrlUtil.toAddress("cute cats"));
        assertNull(UrlUtil.toAddress("hello"));
        assertNull(UrlUtil.toAddress(""));
    }

    @Test public void emptyInputGivesNull() {
        assertNull(UrlUtil.toUrl("   ", DDG));
        assertNull(UrlUtil.toUrl(null, DDG));
    }

    @Test public void displayIsShort() {
        assertEquals("example.com", UrlUtil.display("https://example.com/"));
        assertEquals("example.com/a/", UrlUtil.display("https://example.com/a/"));
        assertEquals("http://example.com", UrlUtil.display("http://example.com/"));
        assertEquals("example.com/?q=1", UrlUtil.display("https://example.com/?q=1"));
        assertEquals("", UrlUtil.display(null));
    }

    @Test public void httpDetection() {
        assertTrue(UrlUtil.isHttp("https://a.b"));
        assertTrue(UrlUtil.isHttp("HTTP://a.b"));
        assertFalse(UrlUtil.isHttp("about:blank"));
        assertFalse(UrlUtil.isHttp(null));
    }

    @Test public void friendlyErrors() {
        assertTrue(UrlUtil.friendlyError("net::ERR_INTERNET_DISCONNECTED").startsWith("You're offline"));
        assertTrue(UrlUtil.friendlyError("net::ERR_NAME_NOT_RESOLVED").startsWith("Couldn't find"));
        assertEquals("net::ERR_SOMETHING", UrlUtil.friendlyError("net::ERR_SOMETHING"));
        assertFalse(UrlUtil.friendlyError(null).isEmpty());
    }
}
