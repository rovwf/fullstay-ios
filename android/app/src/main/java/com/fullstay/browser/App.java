package com.fullstay.browser;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import com.google.android.material.color.DynamicColors;

import java.lang.ref.WeakReference;

public class App extends Application implements Application.ActivityLifecycleCallbacks {
    static WeakReference<MainActivity> main = new WeakReference<>(null);
    private static int resumed;

    /** True while any FullStay screen is in front. */
    static boolean inForeground() { return resumed > 0; }

    /** Tests only. */
    static void setResumedCountForTest(int n) { resumed = n; }

    @Override public void onCreate() {
        super.onCreate();
        resumed = 0;
        DynamicColors.applyToActivitiesIfAvailable(this);   // Material You colours on Android 12+
        registerActivityLifecycleCallbacks(this);
    }

    @Override public void onActivityResumed(Activity a) { resumed++; }
    @Override public void onActivityPaused(Activity a) { if (resumed > 0) resumed--; }
    @Override public void onActivityCreated(Activity a, Bundle b) { }
    @Override public void onActivityStarted(Activity a) { }
    @Override public void onActivityStopped(Activity a) { }
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
    @Override public void onActivityDestroyed(Activity a) { }
}
