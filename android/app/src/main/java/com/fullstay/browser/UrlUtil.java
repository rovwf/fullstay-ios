package com.fullstay.browser;

import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;
import java.util.Locale;
import java.util.regex.Pattern;

/** Turns what you type in the address bar into a URL, and URLs into short display text. */
final class UrlUtil {
    private UrlUtil() {}

    private static final Pattern HAS_SCHEME = Pattern.compile("(?i)^[a-z][a-z0-9+.-]*://.*");
    private static final Pattern SPECIAL = Pattern.compile("(?i)^(about|data|javascript|view-source):.*");
    private static final Pattern LOCAL = Pattern.compile(
            "(?i)^(localhost|\\d{1,3}(\\.\\d{1,3}){3}|\\[[0-9a-f:]+\\])(:\\d{1,5})?([/?#].*)?$");
    private static final Pattern HOST = Pattern.compile(
            "(?i)^[\\p{L}\\p{N}-]+(\\.[\\p{L}\\p{N}-]+)*\\.\\p{L}[\\p{L}\\p{N}-]{1,62}(:\\d{1,5})?([/?#].*)?$");

    /** @return the URL if the input is a web address, otherwise null (empty input or plain words). */
    static String toAddress(String input) {
        String t = input == null ? "" : input.trim();
        if (t.isEmpty()) return null;
        if (HAS_SCHEME.matcher(t).matches() || SPECIAL.matcher(t).matches()) return t;
        if (!t.contains(" ")) {
            if (LOCAL.matcher(t).matches()) return "http://" + t;
            if (HOST.matcher(t).matches()) return "https://" + t;
        }
        return null;
    }

    /** @return the URL to load (an address, or a search for the words), or null if the input is empty. */
    static String toUrl(String input, String searchTemplate) {
        String t = input == null ? "" : input.trim();
        if (t.isEmpty()) return null;
        String address = toAddress(t);
        if (address != null) return address;
        String q;
        try { q = URLEncoder.encode(t, "UTF-8"); } catch (UnsupportedEncodingException e) { q = t; }
        String tpl = searchTemplate == null || searchTemplate.isEmpty() ? Prefs.DEF_SEARCH : searchTemplate;
        return tpl.contains("%s") ? tpl.replace("%s", q) : tpl + q;
    }

    /** Short form for the address bar: no "https://", no lone trailing slash. */
    static String display(String url) {
        if (url == null) return "";
        String u = url;
        if (u.toLowerCase(Locale.ROOT).startsWith("https://")) u = u.substring(8);
        int firstSlash = u.indexOf('/', u.startsWith("http://") ? 7 : 0);
        if (firstSlash >= 0 && firstSlash == u.length() - 1) u = u.substring(0, u.length() - 1);
        return u;
    }

    static boolean isHttp(String url) {
        if (url == null) return false;
        String l = url.toLowerCase(Locale.ROOT);
        return l.startsWith("http://") || l.startsWith("https://");
    }

    /** Friendlier text for common network errors. */
    static String friendlyError(CharSequence raw) {
        String s = raw == null ? "" : raw.toString();
        if (s.contains("INTERNET_DISCONNECTED")) return "You're offline. Check your internet connection and try again.";
        if (s.contains("NAME_NOT_RESOLVED")) return "Couldn't find this website. Check the address, or check your connection.";
        if (s.contains("TIMED_OUT")) return "The website took too long to respond.";
        if (s.contains("CONNECTION_REFUSED")) return "The website refused the connection.";
        if (s.contains("CONNECTION_RESET") || s.contains("CONNECTION_CLOSED")) return "The connection was interrupted.";
        return s.isEmpty() ? "Something went wrong while loading this page." : s;
    }
}
