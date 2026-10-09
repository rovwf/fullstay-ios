package com.fullstay.browser;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Opening "the last app you used". Shared by the button service and the in-app volume keys. */
final class Switcher {
    private Switcher() {}

    enum Result { STARTED, NONE_YET, UNINSTALLED, FAILED }

    static Result openLastApp(Context c) {
        String last = Prefs.s(c, Prefs.LAST_OTHER, null);
        if (last == null) return Result.NONE_YET;
        Intent i = c.getPackageManager().getLaunchIntentForPackage(last);
        if (i == null) {
            Prefs.p(c).edit().remove(Prefs.LAST_OTHER).apply();
            return Result.UNINSTALLED;
        }
        return start(c, i) ? Result.STARTED : Result.FAILED;
    }

    /** What to tell you when the switch didn't happen (null = nothing to say). */
    static String message(Result r) {
        switch (r) {
            case NONE_YET: return "Open another app once \u2014 then volume down will switch to it";
            case UNINSTALLED: return "Your last app isn't installed anymore \u2014 open another app once";
            default: return null;
        }
    }

    static boolean start(Context c, Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Bundle opts = null;
        if (Prefs.b(c, Prefs.INSTANT, true)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            opts = ActivityOptions.makeCustomAnimation(c, 0, 0).toBundle();
        }
        try {
            c.startActivity(i, opts);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
