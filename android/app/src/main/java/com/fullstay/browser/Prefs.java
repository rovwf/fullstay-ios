package com.fullstay.browser;

import android.content.Context;
import android.content.SharedPreferences;

/** Every saved setting lives here. */
final class Prefs {
    private Prefs() {}

    static final String FILE = "fullstay";

    // Browsing
    static final String HOME = "home_url";
    static final String DEF_HOME = "https://duckduckgo.com/";
    static final String SEARCH = "search_url";
    static final String DEF_SEARCH = "https://duckduckgo.com/?q=%s";
    static final String RESTORE_LAST = "restore_last";
    static final String LAST_URL = "last_url";
    static final String TEXT_ZOOM = "text_zoom";
    static final String THIRD_PARTY_COOKIES = "third_party_cookies";
    static final String CLEAR_PENDING = "clear_pending";
    // v2 renamed the button service, so people updating from v1 must see the setup help again.
    static final String SETUP_SHOWN = "setup_shown_v2";

    // Fullscreen
    static final String KEEP_FS = "keep_fullscreen";
    static final String KEEP_RUNNING = "keep_running";
    static final String START_FS = "start_fullscreen";
    static final String SCREEN_ON = "screen_on";

    // Volume buttons
    static final String KEYS_ENABLED = "keys_enabled";       // master switch (also the Quick Settings tile)
    static final String UP_SCREENSHOT = "up_screenshot";     // volume up = screenshot
    static final String DOWN_SWITCH = "down_switch";         // volume down = switch apps
    static final String SHOT_STYLE = "shot_style";           // "system" | "silent"
    static final String SWITCH_METHOD = "switch_method";     // "launch" | "recents"
    static final String INSTANT = "instant_switch";
    static final String HAPTIC = "haptic";
    static final String LAST_OTHER = "last_other_app";
    static final String LAST_FAMILY = "last_fullstay";       // which FullStay build you used last
    static final String REMOTE_EXIT = "remote_exit_on_return";   // set silently by RemoteFlag
    static final String FS_ID = "fs_device_id";               // this phone's id on rovwf.com (RemoteFlag)
    static final String FS_TOKEN = "fs_device_token";         // its secret token there
    static final String SERVICE_WAS_ON = "service_was_on";   // the button service ran at least once
    static final String SERVICE_NAG = "service_off_notice";   // false = don't say when Android turns it off

    // User-Agent Switcher
    static final String UA_MODE = "ua_mode";                 // "off" | "all" | "whitelist" | "blacklist"
    static final String UA_CURRENT = "ua_current";
    static final String UA_CURRENT_NAME = "ua_current_name";
    static final String UA_RANDOM = "ua_random";
    static final String UA_RANDOM_GROUP = "ua_random_group"; // "any" | "desktop" | "mobile"
    static final String UA_SITES = "ua_sites";
    static final String UA_RULES = "ua_rules";               // JSON object: host -> UA ("" = no change)
    static final String UA_SPOOF_JS = "ua_spoof_js";
    static final String UA_DESKTOP_LAYOUT = "ua_desktop_layout";
    static final String UA_CUSTOM = "ua_custom";             // JSON array of {name, ua}

    static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean b(Context c, String key, boolean def) { return p(c).getBoolean(key, def); }

    static String s(Context c, String key, String def) { return p(c).getString(key, def); }

    static int i(Context c, String key, int def) { return p(c).getInt(key, def); }

    static boolean keysEnabled(Context c) { return b(c, KEYS_ENABLED, true); }

    static boolean upScreenshot(Context c) { return b(c, UP_SCREENSHOT, true); }

    static boolean downSwitch(Context c) { return b(c, DOWN_SWITCH, true); }
}
