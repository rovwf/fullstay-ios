package com.fullstay.browser;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Saves a bitmap into Pictures/Screenshots so it shows up in the gallery. */
final class ScreenshotSaver {
    private ScreenshotSaver() {}

    static final String FOLDER = Environment.DIRECTORY_PICTURES + "/Screenshots";

    static String fileName(long timeMillis) {
        return "Screenshot_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date(timeMillis))
                + "_FullStay.png";
    }

    /** Call from a background thread. Returns the saved image's Uri, or null if it failed. */
    static Uri save(Context c, Bitmap bmp) {
        if (bmp == null) return null;
        ContentResolver cr = c.getContentResolver();
        String name = fileName(System.currentTimeMillis());
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, FOLDER);
        v.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri;
        try {
            uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        } catch (Exception e) {
            return null;
        }
        if (uri == null) return null;
        try (OutputStream out = cr.openOutputStream(uri)) {
            if (out == null || !bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new java.io.IOException("write failed");
        } catch (Exception e) {
            try { cr.delete(uri, null, null); } catch (Exception ignored) { }
            return null;
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Images.Media.IS_PENDING, 0);
        try { cr.update(uri, done, null, null); } catch (Exception ignored) { }
        return uri;
    }
}
