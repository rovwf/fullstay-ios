package com.fullstay.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.webkit.UserAgentMetadata;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The built-in User-Agent Switcher: presets, modes, per-site rules, random mode. */
final class UaManager {
    private UaManager() {}

    static final class Preset {
        final String browser, os, ua;
        final boolean desktop, special;

        Preset(String browser, String os, boolean desktop, String ua) { this(browser, os, desktop, ua, false); }

        Preset(String browser, String os, boolean desktop, String ua, boolean special) {
            this.browser = browser; this.os = os; this.desktop = desktop; this.ua = ua; this.special = special;
        }

        String label() { return os.isEmpty() ? browser : browser + " \u2014 " + os; }
    }

    // Version numbers: Chrome 154 was current stable in Sept 2026. Others are estimates;
    // you can always add your own exact strings in "My saved user-agents".
    private static final String CH = "154";
    private static final String FX = "155";
    static final Preset[] BUILTIN = {
        new Preset("Chrome", "Windows", true, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + CH + ".0.0.0 Safari/537.36"),
        new Preset("Chrome", "macOS", true, "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + CH + ".0.0.0 Safari/537.36"),
        new Preset("Chrome", "Linux", true, "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + CH + ".0.0.0 Safari/537.36"),
        new Preset("Chrome", "ChromeOS", true, "Mozilla/5.0 (X11; CrOS x86_64 14541.0.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + CH + ".0.0.0 Safari/537.36"),
        new Preset("Chrome", "Android phone", false, "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + CH + ".0.0.0 Mobile Safari/537.36"),
        new Preset("Chrome", "Android tablet", false, "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + CH + ".0.0.0 Safari/537.36"),
        new Preset("Chrome", "iPhone", false, "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/" + CH + ".0.0.0 Mobile/15E148 Safari/604.1"),
        new Preset("Firefox", "Windows", true, "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:" + FX + ".0) Gecko/20100101 Firefox/" + FX + ".0"),
        new Preset("Firefox", "macOS", true, "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:" + FX + ".0) Gecko/20100101 Firefox/" + FX + ".0"),
        new Preset("Firefox", "Linux", true, "Mozilla/5.0 (X11; Linux x86_64; rv:" + FX + ".0) Gecko/20100101 Firefox/" + FX + ".0"),
        new Preset("Firefox", "Android", false, "Mozilla/5.0 (Android 15; Mobile; rv:" + FX + ".0) Gecko/" + FX + ".0 Firefox/" + FX + ".0"),
        new Preset("Edge", "Windows", true, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + CH + ".0.0.0 Safari/537.36 Edg/" + CH + ".0.0.0"),
        new Preset("Edge", "macOS", true, "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + CH + ".0.0.0 Safari/537.36 Edg/" + CH + ".0.0.0"),
        new Preset("Edge", "Android", false, "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + CH + ".0.0.0 Mobile Safari/537.36 EdgA/" + CH + ".0.0.0"),
        new Preset("Safari", "macOS", true, "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"),
        new Preset("Safari", "iPhone", false, "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Mobile/15E148 Safari/604.1"),
        new Preset("Safari", "iPad", false, "Mozilla/5.0 (iPad; CPU OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Mobile/15E148 Safari/604.1"),
        new Preset("Opera", "Windows", true, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36 OPR/139.0.0.0"),
        new Preset("Samsung Internet", "Android", false, "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/29.0 Chrome/136.0.0.0 Mobile Safari/537.36"),
        new Preset("Internet Explorer 11", "Windows", true, "Mozilla/5.0 (Windows NT 10.0; Trident/7.0; rv:11.0) like Gecko", true),
        new Preset("Googlebot", "", false, "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)", true),
        new Preset("Bingbot", "", false, "Mozilla/5.0 (compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm)", true),
    };

    private static String randomUa, randomName;

    static void reroll(Context c) {
        String group = Prefs.s(c, Prefs.UA_RANDOM_GROUP, "any");
        List<Preset> pool = new ArrayList<>();
        for (Preset p : allPresets(c)) {
            if (p.special) continue;
            if ("desktop".equals(group) && !p.desktop) continue;
            if ("mobile".equals(group) && p.desktop) continue;
            pool.add(p);
        }
        if (pool.isEmpty()) return;
        Preset p = pool.get(new Random().nextInt(pool.size()));
        randomUa = p.ua;
        randomName = p.label();
    }

    static List<Preset> allPresets(Context c) {
        List<Preset> out = new ArrayList<>();
        for (Preset p : BUILTIN) out.add(p);
        JSONArray arr = customs(c);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String ua = o.optString("ua", "");
            if (ua.isEmpty()) continue;
            out.add(new Preset("\u2605 " + o.optString("name", "Custom"), "", isDesktop(ua), ua));
        }
        return out;
    }

    static JSONArray customs(Context c) {
        try { return new JSONArray(Prefs.s(c, Prefs.UA_CUSTOM, "[]")); } catch (JSONException e) { return new JSONArray(); }
    }

    static void addCustom(Context c, String name, String ua) {
        JSONArray arr = customs(c);
        try { arr.put(new JSONObject().put("name", name.isEmpty() ? "Custom" : name).put("ua", ua)); } catch (JSONException ignored) {}
        Prefs.p(c).edit().putString(Prefs.UA_CUSTOM, arr.toString()).apply();
    }

    static void removeCustom(Context c, int index) {
        JSONArray arr = customs(c);
        arr.remove(index);
        Prefs.p(c).edit().putString(Prefs.UA_CUSTOM, arr.toString()).apply();
    }

    /** Pick a user-agent as the global one (turns random off, turns the switcher on). */
    static void select(Context c, String name, String ua) {
        SharedPreferences.Editor e = Prefs.p(c).edit()
                .putString(Prefs.UA_CURRENT, ua)
                .putString(Prefs.UA_CURRENT_NAME, name)
                .putBoolean(Prefs.UA_RANDOM, false);
        if ("off".equals(Prefs.s(c, Prefs.UA_MODE, "off"))) e.putString(Prefs.UA_MODE, "all");
        e.apply();
    }

    static String globalUa(Context c) {
        if (Prefs.b(c, Prefs.UA_RANDOM, false)) {
            if (randomUa == null) reroll(c);
            return randomUa;
        }
        return Prefs.s(c, Prefs.UA_CURRENT, "");
    }

    static String selectedLabel(Context c) {
        if (Prefs.b(c, Prefs.UA_RANDOM, false)) {
            if (randomUa == null) reroll(c);
            return "Random: " + (randomName == null ? "none" : randomName);
        }
        String ua = Prefs.s(c, Prefs.UA_CURRENT, "");
        if (ua.isEmpty()) return "None chosen yet";
        String n = Prefs.s(c, Prefs.UA_CURRENT_NAME, "");
        return n.isEmpty() ? "Custom" : n;
    }

    static String menuLabel(Context c) {
        return "off".equals(Prefs.s(c, Prefs.UA_MODE, "off")) ? "Off" : selectedLabel(c);
    }

    static String nameFor(Context c, String ua) {
        if (ua == null || ua.isEmpty()) return "Normal user-agent (no change)";
        for (Preset p : allPresets(c)) if (p.ua.equals(ua)) return p.label();
        return "Custom: " + ua;
    }

    /** The user-agent to use for this page, or null for the normal one. */
    static String uaForUrl(Context c, String url) {
        SharedPreferences p = Prefs.p(c);
        String mode = p.getString(Prefs.UA_MODE, "off");
        if ("off".equals(mode)) return null;
        String host = hostOf(url);
        if (host != null) {
            String rule = ruleFor(c, host);
            if (rule != null) return rule.isEmpty() ? null : rule;
        }
        String g = globalUa(c);
        if (g == null || g.isEmpty()) return null;
        boolean listed = host != null && inList(p.getString(Prefs.UA_SITES, ""), host);
        switch (mode) {
            case "all": return g;
            case "whitelist": return listed ? g : null;
            case "blacklist": return listed ? null : g;
            default: return null;
        }
    }

    static String hostOf(String url) {
        if (url == null) return null;
        try {
            String h = Uri.parse(url).getHost();
            return h == null ? null : h.toLowerCase(Locale.ROOT);
        } catch (Exception e) { return null; }
    }

    static String normalizeHost(String s) {
        if (s == null) return "";
        String h = s.trim().toLowerCase(Locale.ROOT);
        h = h.replaceFirst("^[a-z][a-z0-9+.-]*://", "");
        int slash = h.indexOf('/');
        if (slash >= 0) h = h.substring(0, slash);
        while (h.startsWith("*.") || h.startsWith(".")) h = h.substring(h.startsWith("*.") ? 2 : 1);
        return h;
    }

    static boolean matches(String host, String pattern) {
        String p = normalizeHost(pattern);
        return !p.isEmpty() && (host.equals(p) || host.endsWith("." + p));
    }

    static boolean inList(String list, String host) {
        for (String line : list.split("[\\n,;\\s]+")) if (matches(host, line)) return true;
        return false;
    }

    static JSONObject rules(Context c) {
        try { return new JSONObject(Prefs.s(c, Prefs.UA_RULES, "{}")); } catch (JSONException e) { return new JSONObject(); }
    }

    static void putRule(Context c, String host, String ua) {
        JSONObject r = rules(c);
        try { r.put(normalizeHost(host), ua == null ? "" : ua); } catch (JSONException ignored) {}
        Prefs.p(c).edit().putString(Prefs.UA_RULES, r.toString()).apply();
    }

    static void removeRule(Context c, String host) {
        JSONObject r = rules(c);
        r.remove(host);
        Prefs.p(c).edit().putString(Prefs.UA_RULES, r.toString()).apply();
    }

    /** Most specific matching rule, or null if none. "" means "don't change". */
    static String ruleFor(Context c, String host) {
        JSONObject r = rules(c);
        String best = null, bestUa = null;
        Iterator<String> it = r.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (matches(host, k) && (best == null || k.length() > best.length())) { best = k; bestUa = r.optString(k, ""); }
        }
        return bestUa;
    }

    static boolean isDesktop(String ua) {
        return (ua.contains("Windows NT") || ua.contains("Macintosh") || ua.contains("X11") || ua.contains("CrOS"))
                && !ua.contains("Mobile");
    }

    /** Client-hint metadata (Sec-CH-UA headers) that matches a spoofed user-agent. */
    static UserAgentMetadata metaFor(String ua) {
        Matcher m = Pattern.compile("Chrome/(\\d+)").matcher(ua);
        boolean chromium = !ua.contains("Firefox/") && m.find();
        String major = chromium ? m.group(1) : "24";
        List<UserAgentMetadata.BrandVersion> brands = new ArrayList<>();
        if (chromium) {
            String brand = ua.contains("Edg") ? "Microsoft Edge" : ua.contains("OPR/") ? "Opera"
                    : ua.contains("SamsungBrowser/") ? "Samsung Internet" : "Google Chrome";
            // Opera and Samsung Internet report their own version, not Chrome's.
            Matcher own = Pattern.compile("(?:OPR|SamsungBrowser)/(\\d+)").matcher(ua);
            brands.add(bv("Chromium", major));
            brands.add(bv(brand, own.find() ? own.group(1) : major));
        }
        brands.add(bv("Not)A;Brand", "24"));
        String platform = ua.contains("Windows") ? "Windows" : ua.contains("Android") ? "Android"
                : ua.contains("CrOS") ? "Chrome OS" : (ua.contains("iPhone") || ua.contains("iPad")) ? "iOS"
                : ua.contains("Macintosh") ? "macOS" : "Linux";
        String pv = "Windows".equals(platform) ? "19.0.0" : "macOS".equals(platform) ? "15.0.0"
                : "Android".equals(platform) ? "15.0.0" : "";
        return new UserAgentMetadata.Builder()
                .setBrandVersionList(brands)
                .setFullVersion(major + ".0.0.0")
                .setPlatform(platform)
                .setPlatformVersion(pv)
                .setArchitecture("Android".equals(platform) || "iOS".equals(platform) ? "arm" : "x86")
                .setModel("")
                .setMobile(ua.contains("Mobile"))
                .setBitness(64)
                .setWow64(false)
                .build();
    }

    private static UserAgentMetadata.BrandVersion bv(String brand, String major) {
        return new UserAgentMetadata.BrandVersion.Builder()
                .setBrand(brand).setMajorVersion(major).setFullVersion(major + ".0.0.0").build();
    }

    // ---- Backup ----
    private static final String[] STR_KEYS = {Prefs.UA_MODE, Prefs.UA_CURRENT, Prefs.UA_CURRENT_NAME, Prefs.UA_RANDOM_GROUP, Prefs.UA_SITES};
    private static final String[] BOOL_KEYS = {Prefs.UA_RANDOM, Prefs.UA_SPOOF_JS, Prefs.UA_DESKTOP_LAYOUT};

    static String exportJson(Context c) throws JSONException {
        SharedPreferences p = Prefs.p(c);
        JSONObject o = new JSONObject();
        o.put("app", "FullStay user-agent switcher");
        for (String k : STR_KEYS) if (p.contains(k)) o.put(k, p.getString(k, ""));
        for (String k : BOOL_KEYS) if (p.contains(k)) o.put(k, p.getBoolean(k, false));
        o.put(Prefs.UA_RULES, rules(c));
        o.put(Prefs.UA_CUSTOM, customs(c));
        return o.toString(2);
    }

    static void importJson(Context c, String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        boolean ours = o.has(Prefs.UA_RULES) || o.has(Prefs.UA_CUSTOM);
        for (String k : STR_KEYS) ours |= o.has(k);
        for (String k : BOOL_KEYS) ours |= o.has(k);
        if (!ours) throw new JSONException("Not FullStay user-agent settings");
        if (o.has(Prefs.UA_MODE) && !java.util.Arrays.asList("off", "all", "whitelist", "blacklist").contains(o.getString(Prefs.UA_MODE)))
            throw new JSONException("Unknown mode");
        if (o.has(Prefs.UA_RANDOM_GROUP) && !java.util.Arrays.asList("any", "desktop", "mobile").contains(o.getString(Prefs.UA_RANDOM_GROUP)))
            throw new JSONException("Unknown random group");
        SharedPreferences.Editor e = Prefs.p(c).edit();
        for (String k : STR_KEYS) if (o.has(k)) e.putString(k, o.getString(k));
        for (String k : BOOL_KEYS) if (o.has(k)) e.putBoolean(k, o.getBoolean(k));
        if (o.has(Prefs.UA_RULES)) e.putString(Prefs.UA_RULES, o.getJSONObject(Prefs.UA_RULES).toString());
        if (o.has(Prefs.UA_CUSTOM)) e.putString(Prefs.UA_CUSTOM, o.getJSONArray(Prefs.UA_CUSTOM).toString());
        e.apply();
        randomUa = null;
    }

    static void reset(Context c) {
        SharedPreferences.Editor e = Prefs.p(c).edit();
        for (String k : STR_KEYS) e.remove(k);
        for (String k : BOOL_KEYS) e.remove(k);
        e.remove(Prefs.UA_RULES).remove(Prefs.UA_CUSTOM).apply();
        randomUa = null;
    }
}
