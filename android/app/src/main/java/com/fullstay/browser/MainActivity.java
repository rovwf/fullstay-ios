package com.fullstay.browser;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.TooltipCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.webkit.ScriptHandler;
import androidx.webkit.UserAgentMetadata;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    static final long SWITCH_WINDOW_MS = 3000;
    private static final Set<String> ALL_ORIGINS = Collections.singleton("*");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Chrome chromeClient = new Chrome();
    private ExecutorService io;

    // Views
    private FrameLayout root, webHolder, fsContainer;
    private LinearLayout column, topBar, errorView;
    private ImageButton backBtn, actionBtn, menuBtn;
    private ImageView siteIcon;
    private EditText urlField;
    private TextView errorText;
    private String errorUrl;                              // page the error screen belongs to
    private LinearProgressIndicator progress;
    private View flash;
    private WebView web;

    // Fullscreen state
    private View customView;                              // the page's own fullscreen view
    private WebChromeClient.CustomViewCallback customCallback;
    private boolean appFullscreen;                        // from the menu / "start in fullscreen"
    private boolean autoFullscreen;                       // kept on after Android forced the page out
    private boolean userExitingFs, resumed, loading, editing;
    private long lastHideAt = -SWITCH_WINDOW_MS * 2, lastShowAt = -SWITCH_WINDOW_MS * 2;

    // User-agent / scripts
    private String defaultUa;
    private UserAgentMetadata defaultMeta;
    private boolean metaSupported, docStartSupported;
    private ScriptHandler keepHandler, uaHandler;
    private String keepJsApplied;
    private String uaScriptKey, uaScriptNow;
    private String keepJs, uaJsTemplate;

    private ValueCallback<Uri[]> fileCallback;
    private ActivityResultLauncher<Intent> fileLauncher;

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle state) {
        EdgeToEdge.enable(this);
        super.onCreate(state);
        App.main = new WeakReference<>(this);
        io = Executors.newSingleThreadExecutor();
        keepJs = readAsset("keep.js");
        uaJsTemplate = readAsset("ua.js");
        RemoteFlag.refresh(this);
        fileLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.getResultCode(), r.getData()));
                fileCallback = null;
            }
        });
        buildUi();
        setupWebView();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { handleBack(); }
        });

        appFullscreen = Prefs.b(this, Prefs.START_FS, false);
        // Fresh start: new random user-agent (the process itself may live on for days
        // because the volume-button service keeps it running).
        if (state == null && Prefs.b(this, Prefs.UA_RANDOM, false)) UaManager.reroll(this);
        boolean restored = state != null && web.restoreState(state) != null;
        if (!restored) {
            String url = urlFromIntent(getIntent());
            if (url == null && Prefs.b(this, Prefs.RESTORE_LAST, true)) url = Prefs.s(this, Prefs.LAST_URL, null);
            if (url == null || url.isEmpty()) url = Prefs.s(this, Prefs.HOME, Prefs.DEF_HOME);
            load(url);
        }
        updateFullscreenUi();
        maybeShowSetup();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        RemoteFlag.refresh(this);            // silent background check, at most once a minute
        lastShowAt = SystemClock.uptimeMillis();
        web.onResume();
        web.resumeTimers();
        if (Prefs.b(this, Prefs.CLEAR_PENDING, false)) {
            web.clearCache(true);
            web.clearHistory();
            Prefs.p(this).edit().putBoolean(Prefs.CLEAR_PENDING, false).apply();
        }
        applyWebPrefs();
        syncKeepScript();
        String shown = web.getUrl();                     // null while the first page is still loading
        if (shown != null && applyUaFor(shown)) reloadPage();   // settings may have changed meanwhile
        updateFullscreenUi();
        updateBar();
        web.evaluateJavascript("window.__fsKeepSetBg&&window.__fsKeepSetBg(false)", null);
        // A moment later, so a service that Android is just reconnecting isn't mistaken for one that's off.
        handler.postDelayed(this::checkButtonService, 1500);
    }

    @Override protected void onRestart() {
        super.onRestart();
        // When the remote flag says so, always leave fullscreen on return (after an app switch,
        // or after the screen was off). Driven silently by RemoteFlag; nothing is shown.
        if (RemoteFlag.exitOnReturn(this)) leaveFullscreen();
        // Ask again right away: if the switch was turned off while you were away, apply it now, not next time.
        final long back = SystemClock.uptimeMillis();
        RemoteFlag.refresh(this, true, () -> {
            if (!isFinishing() && !isDestroyed() && SystemClock.uptimeMillis() - back < 5000) {
                leaveFullscreen();
                syncKeepScript();
            }
        });
    }

    @Override protected void onStop() {
        super.onStop();
        RemoteFlag.refresh(this);            // so the setting is fresh when you come back
    }

    @Override protected void onPause() {
        resumed = false;
        lastHideAt = SystemClock.uptimeMillis();
        web.evaluateJavascript("window.__fsKeepSetBg&&window.__fsKeepSetBg(true)", null);
        // With "keep pages running" on, the page is deliberately NOT paused.
        if (!Prefs.b(this, Prefs.KEEP_RUNNING, true)) web.onPause();
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) updateFullscreenUi();              // hide the system bars again every time we're back
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String url = urlFromIntent(intent);
        if (url != null) load(url);
    }

    @Override protected void onDestroy() {
        if (App.main.get() == this) App.main = new WeakReference<>(null);
        if (io != null) io.shutdown();
        handler.removeCallbacksAndMessages(null);
        web.stopLoading();
        if (customView != null) fsContainer.removeView(customView);
        webHolder.removeView(web);                        // must be detached before destroy()
        web.destroy();
        super.onDestroy();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        return handleVolumeKey(keyCode, event) || super.onKeyDown(keyCode, event);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        return handleVolumeKey(keyCode, event) || super.onKeyUp(keyCode, event);
    }

    /** Only used when the accessibility service is off: the buttons still work inside FullStay. */
    private boolean handleVolumeKey(int keyCode, KeyEvent e) {
        if (ButtonService.running) return false;         // the service already decided
        KeyLogic.Action a = KeyLogic.decide(keyCode, e.getAction(), e.getRepeatCount(),
                Prefs.keysEnabled(this), Prefs.upScreenshot(this), Prefs.downSwitch(this), false);
        switch (a) {
            case PASS: return false;
            case SCREENSHOT: captureInApp(); return true;
            case SWITCH: switchFromApp(); return true;
            default: return true;
        }
    }

    /**
     * Volume down inside FullStay while the button service is off (e.g. after Force stop, which makes Android
     * switch it off). Opens your last app like the service would. It used to only hide FullStay, which looked
     * like the app closing. If there's no last app to open, FullStay stays where it is.
     */
    private void switchFromApp() {
        Switcher.Result r = Switcher.openLastApp(this);
        if (r == Switcher.Result.STARTED) return;
        String msg = Switcher.message(r);
        toast(msg != null ? msg : "Couldn't open your last app");
    }

    private static boolean serviceNoticeShown;               // once per app start, never nagging

    /** Tests only. */
    static void resetServiceNoticeForTest() { serviceNoticeShown = false; }

    /** Android switches the button service off on Force stop. Turn it back on, or say so and offer to. */
    private void checkButtonService() {
        if (!resumed || ButtonService.running || isFinishing()) return;
        if (!Prefs.keysEnabled(this) || (!Prefs.upScreenshot(this) && !Prefs.downSwitch(this))) return;
        if (!Prefs.b(this, Prefs.SERVICE_WAS_ON, false)) return;   // never set up: the first-run help covers that
        if (ButtonService.tryReenable(this)) { toast("Volume buttons turned back on"); return; }
        if (serviceNoticeShown || !Prefs.b(this, Prefs.SERVICE_NAG, true)) return;
        serviceNoticeShown = true;
        new MaterialAlertDialogBuilder(this)
                .setTitle("Volume buttons are off")
                .setMessage("Android switched off \u201CFullStay buttons\u201D. This happens after Force stop "
                        + "(and sometimes after an update or a battery clean-up). Until it's back on, volume down "
                        + "only works inside FullStay.\n\nTurn on \u201CFullStay buttons\u201D in Accessibility to fix it.")
                .setPositiveButton("Open Accessibility", (d, w) -> {
                    try { startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
                    catch (Exception ex) { toast("Open Settings \u2192 Accessibility"); }
                })
                .setNeutralButton("Don't ask again", (d, w) -> Prefs.p(this).edit().putBoolean(Prefs.SERVICE_NAG, false).apply())
                .setNegativeButton("Not now", null)
                .show();
    }

    private void leaveFullscreen() {
        if (customView != null) exitPageFullscreenByUser();
        appFullscreen = false;
        autoFullscreen = false;
        updateFullscreenUi();
    }

    void handleBack() {
        if (editing) { stopEditing(); return; }
        if (customView != null) { exitPageFullscreenByUser(); return; }
        if (appFullscreen || autoFullscreen) {
            appFullscreen = false;
            autoFullscreen = false;
            updateFullscreenUi();
            return;
        }
        if (web.canGoBack()) { web.goBack(); return; }
        moveTaskToBack(true);                             // keep the page alive instead of closing it
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        int barColor = Ui.color(this, Ui.ATTR_SURFACE_CONTAINER);
        root = new FrameLayout(this);
        root.setBackgroundColor(barColor);

        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(barColor);

        backBtn = iconButton(R.drawable.ic_arrow_back, "Back", v -> { if (web.canGoBack()) web.goBack(); });
        topBar.addView(backBtn);

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(Ui.color(this, Ui.ATTR_SURFACE_HIGHEST));
        pillBg.setCornerRadius(Ui.dp(this, 24));
        pill.setBackground(pillBg);
        pill.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 2), 0);

        siteIcon = Ui.icon(this, R.drawable.ic_search, Ui.ATTR_ON_SURFACE_VARIANT, 18);
        pill.addView(siteIcon);

        urlField = new EditText(this);
        urlField.setBackground(null);
        urlField.setSingleLine(true);
        urlField.setHint("Search or type a web address");
        urlField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        urlField.setTextColor(Ui.color(this, Ui.ATTR_ON_SURFACE));
        urlField.setHintTextColor(Ui.color(this, Ui.ATTR_ON_SURFACE_VARIANT));
        urlField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlField.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        urlField.setSelectAllOnFocus(true);
        urlField.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 4), 0);
        urlField.setOnEditorActionListener((v, id, ev) -> {
            boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN;
            if (id == EditorInfo.IME_ACTION_GO || enter) { go(v.getText().toString()); return true; }
            return false;
        });
        urlField.setOnFocusChangeListener((v, focused) -> {
            editing = focused;
            if (focused) {
                String u = web.getUrl();
                if (u != null && UrlUtil.isHttp(u)) urlField.setText(u);
                urlField.selectAll();
            } else {
                showUrl(web.getUrl());
            }
            updateBar();
        });
        pill.addView(urlField, new LinearLayout.LayoutParams(0, -1, 1f));

        actionBtn = iconButton(R.drawable.ic_refresh, "Reload", v -> onActionButton());
        pill.addView(actionBtn, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));

        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1f);
        pillLp.setMargins(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0);
        topBar.addView(pill, pillLp);

        menuBtn = iconButton(R.drawable.ic_more_vert, "Menu", v -> showMenu());
        topBar.addView(menuBtn);
        column.addView(topBar, new LinearLayout.LayoutParams(-1, -2));

        progress = new LinearProgressIndicator(this);
        progress.setMax(100);
        progress.setTrackThickness(Ui.dp(this, 3));
        progress.setTrackCornerRadius(Ui.dp(this, 2));
        progress.setVisibility(View.INVISIBLE);
        column.addView(progress, new LinearLayout.LayoutParams(-1, -2));

        webHolder = new FrameLayout(this);
        webHolder.setBackgroundColor(Ui.color(this, Ui.ATTR_SURFACE));
        web = new WebView(this);
        webHolder.addView(web, new FrameLayout.LayoutParams(-1, -1));
        errorView = buildErrorView();
        webHolder.addView(errorView, new FrameLayout.LayoutParams(-1, -1));
        column.addView(webHolder, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        fsContainer = new FrameLayout(this);
        fsContainer.setBackgroundColor(Color.BLACK);
        fsContainer.setVisibility(View.GONE);
        root.addView(fsContainer, new FrameLayout.LayoutParams(-1, -1));

        flash = new View(this);
        flash.setBackgroundColor(Color.WHITE);
        flash.setAlpha(0f);
        flash.setVisibility(View.GONE);
        root.addView(flash, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            applyInsets(insets);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    private void applyInsets(WindowInsetsCompat insets) {
        Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
        Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
        int p = Ui.dp(this, 4);
        fsContainer.setPadding(0, 0, 0, ime.bottom);         // keep text boxes above the keyboard
        if (isFullscreenUi()) {
            column.setPadding(0, 0, 0, ime.bottom);
            webHolder.setPadding(0, 0, 0, 0);
        } else {
            topBar.setPadding(p + bars.left, p + bars.top, p + bars.right, p);
            column.setPadding(0, 0, 0, Math.max(bars.bottom, ime.bottom));
            webHolder.setPadding(bars.left, 0, bars.right, 0);
        }
    }

    private LinearLayout buildErrorView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Ui.color(this, Ui.ATTR_SURFACE));
        int pad = Ui.dp(this, 32);
        box.setPadding(pad, pad, pad, pad);
        box.setVisibility(View.GONE);
        box.setClickable(true);

        ImageView icon = Ui.icon(this, R.drawable.ic_cloud_off, Ui.ATTR_ON_SURFACE_VARIANT, 56);
        box.addView(icon);
        TextView title = new TextView(this);
        Ui.text(title, com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        title.setTextColor(Ui.color(this, Ui.ATTR_ON_SURFACE));
        title.setText("Can't open this page");
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 8));
        box.addView(title);
        errorText = new TextView(this);
        Ui.text(errorText, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        errorText.setTextColor(Ui.color(this, Ui.ATTR_ON_SURFACE_VARIANT));
        errorText.setGravity(Gravity.CENTER);
        box.addView(errorText);
        MaterialButton retry = new MaterialButton(this);
        retry.setText("Try again");
        retry.setOnClickListener(v -> reloadPage());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = Ui.dp(this, 24);
        box.addView(retry, lp);
        return box;
    }

    private ImageButton iconButton(int res, String desc, View.OnClickListener l) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        ImageViewCompat.setImageTintList(b, ColorStateList.valueOf(Ui.color(this, Ui.ATTR_ON_SURFACE_VARIANT)));
        b.setContentDescription(desc);
        TooltipCompat.setTooltipText(b, desc);
        Ui.ripple(b, true);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));
        return b;
    }

    private void setIcon(ImageButton b, int res, String desc) {
        b.setImageResource(res);
        b.setContentDescription(desc);
        TooltipCompat.setTooltipText(b, desc);
    }

    /** Refreshes the address-bar icons and the back button. */
    private void updateBar() {
        if (editing) {
            siteIcon.setImageResource(R.drawable.ic_search);
            setIcon(actionBtn, R.drawable.ic_close, "Clear");
        } else {
            String u = web.getUrl();
            siteIcon.setImageResource(u != null && u.toLowerCase(Locale.ROOT).startsWith("https://") ? R.drawable.ic_lock
                    : UrlUtil.isHttp(u) ? R.drawable.ic_info : R.drawable.ic_search);
            if (loading) setIcon(actionBtn, R.drawable.ic_close, "Stop");
            else setIcon(actionBtn, R.drawable.ic_refresh, "Reload");
        }
        boolean canBack = web.canGoBack();
        backBtn.setEnabled(canBack);
        backBtn.setAlpha(canBack ? 1f : 0.38f);
    }

    private void onActionButton() {
        if (editing) urlField.setText("");
        else if (loading) web.stopLoading();
        else reloadPage();
    }

    private void reloadPage() {
        hideError();
        web.reload();
    }

    private void stopEditing() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(urlField.getWindowToken(), 0);
        urlField.clearFocus();
        web.requestFocus();
    }

    private void showUrl(String url) {
        if (!editing) urlField.setText(url == null ? "" : UrlUtil.display(url));
        updateBar();
    }

    /** @param replace false = keep an earlier, more specific message for the same page */
    void showError(String url, CharSequence raw, boolean replace) {
        if (!replace && errorView.getVisibility() == View.VISIBLE && Objects.equals(url, errorUrl)) return;
        errorUrl = url;
        errorText.setText(UrlUtil.friendlyError(raw));
        errorView.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        errorUrl = null;
        errorView.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------------ menu

    private void showMenu() {
        if (editing) stopEditing();
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 16));

        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        quick.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), Ui.dp(this, 4));
        quick.addView(quickAction(sheet, R.drawable.ic_arrow_forward, "Forward", web.canGoForward(), web::goForward));
        quick.addView(quickAction(sheet, R.drawable.ic_refresh, "Reload", true, this::reloadPage));
        quick.addView(quickAction(sheet, R.drawable.ic_home, "Home", true,
                () -> load(Prefs.s(this, Prefs.HOME, Prefs.DEF_HOME))));
        quick.addView(quickAction(sheet, R.drawable.ic_share, "Share", UrlUtil.isHttp(web.getUrl()), this::share));
        quick.addView(quickAction(sheet, R.drawable.ic_camera, "Screenshot", true,
                () -> handler.postDelayed(this::screenshotFromMenu, 450)));
        box.addView(quick);
        Ui.divider(box);

        box.addView(menuItem(sheet, R.drawable.ic_fullscreen, "Fullscreen", "Press Back to leave it", this::enterAppFullscreen));
        box.addView(menuItem(sheet, R.drawable.ic_devices, "User-agent", UaManager.menuLabel(this), this::quickUa));
        box.addView(menuItem(sheet, R.drawable.ic_copy, "Copy link", null, this::copyLink));
        box.addView(menuItem(sheet, R.drawable.ic_volume, "Volume", "Show the volume slider", this::showVolume));
        box.addView(menuItem(sheet, R.drawable.ic_settings, "Settings", null,
                () -> startActivity(new Intent(this, SettingsActivity.class))));
        box.addView(menuItem(sheet, R.drawable.ic_power, "Close app", "Closes the page completely", this::finishAndRemoveTask));

        sheet.setContentView(box);
        sheet.show();
    }

    private View quickAction(BottomSheetDialog sheet, int icon, String label, boolean enabled, Runnable action) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        ImageView iv = Ui.icon(this, icon, Ui.ATTR_ON_SURFACE, 24);
        item.addView(iv);
        TextView t = new TextView(this);
        Ui.text(t, com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
        t.setTextColor(Ui.color(this, Ui.ATTR_ON_SURFACE_VARIANT));
        t.setText(label);
        t.setPadding(0, Ui.dp(this, 6), 0, 0);
        item.addView(t);
        item.setContentDescription(label);
        item.setEnabled(enabled);
        item.setAlpha(enabled ? 1f : 0.38f);
        if (enabled) {
            Ui.ripple(item, true);
            item.setOnClickListener(v -> { sheet.dismiss(); action.run(); });
        }
        item.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        return item;
    }

    private View menuItem(BottomSheetDialog sheet, int icon, String title, String subtitle, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(this, 56));
        row.setPadding(Ui.dp(this, 24), Ui.dp(this, 8), Ui.dp(this, 24), Ui.dp(this, 8));
        row.addView(Ui.icon(this, icon, Ui.ATTR_ON_SURFACE_VARIANT, 24));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Ui.dp(this, 20), 0, 0, 0);
        TextView t = new TextView(this);
        Ui.text(t, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        t.setTextColor(Ui.color(this, Ui.ATTR_ON_SURFACE));
        t.setText(title);
        texts.addView(t);
        if (subtitle != null) {
            TextView s = new TextView(this);
            Ui.text(s, com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            s.setTextColor(Ui.color(this, Ui.ATTR_ON_SURFACE_VARIANT));
            s.setText(subtitle);
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        row.setContentDescription(title);
        Ui.ripple(row, false);
        row.setOnClickListener(v -> { sheet.dismiss(); action.run(); });
        return row;
    }

    private void quickUa() {
        List<UaManager.Preset> list = UaManager.allPresets(this);
        String[] labels = new String[list.size() + 1];
        labels[0] = "Off (normal user-agent)";
        int checked = "off".equals(Prefs.s(this, Prefs.UA_MODE, "off")) ? 0 : -1;
        String current = Prefs.b(this, Prefs.UA_RANDOM, false) ? null : Prefs.s(this, Prefs.UA_CURRENT, "");
        for (int i = 0; i < list.size(); i++) {
            labels[i + 1] = list.get(i).label();
            if (checked == -1 && list.get(i).ua.equals(current)) checked = i + 1;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("User-agent")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    if (which == 0) Prefs.p(this).edit().putString(Prefs.UA_MODE, "off").apply();
                    else { UaManager.Preset p = list.get(which - 1); UaManager.select(this, p.label(), p.ua); }
                    d.dismiss();
                    if (applyUaFor(currentUrl())) reloadPage();
                })
                .setNeutralButton("More options", (d, w) -> startActivity(new Intent(this, UaActivity.class)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void share() {
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, currentUrl());
        startActivity(Intent.createChooser(i, "Share page"));
    }

    private void copyLink() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("link", currentUrl()));
        if (Build.VERSION.SDK_INT < 33) toast("Link copied");   // Android 13+ shows its own confirmation
    }

    private void showVolume() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI);
    }

    private void maybeShowSetup() {
        if (!Prefs.keysEnabled(this) || (!Prefs.upScreenshot(this) && !Prefs.downSwitch(this))) return;
        if (ButtonService.isEnabled(this) || Prefs.b(this, Prefs.SETUP_SHOWN, false)) return;
        Prefs.p(this).edit().putBoolean(Prefs.SETUP_SHOWN, true).apply();
        new MaterialAlertDialogBuilder(this)
                .setTitle("Set up the volume buttons")
                .setMessage("Volume up = screenshot, volume down = switch between FullStay and your last app. "
                        + "To make this work in every app, turn on \u201CFullStay buttons\u201D in Accessibility.\n\n"
                        + "If Android says it's a restricted setting: go to Settings \u2192 Apps \u2192 FullStay, tap \u22EE "
                        + "(top right) \u2192 \u201CAllow restricted settings\u201D, then try again.")
                .setPositiveButton("Open Accessibility", (d, w) -> {
                    try { startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
                    catch (Exception e) { toast("Open Settings \u2192 Accessibility"); }
                })
                .setNegativeButton("Later", null)
                .show();
    }

    // ------------------------------------------------------------------ screenshots

    private void screenshotFromMenu() {
        ButtonService s = ButtonService.get();
        if (s != null) s.takeShot(); else captureInApp();
    }

    /** Screenshot of FullStay's own window (used when the accessibility service is off). */
    private void captureInApp() {
        View decor = getWindow().getDecorView();
        int w = decor.getWidth(), h = decor.getHeight();
        if (w <= 0 || h <= 0) return;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(getWindow(), bmp, result -> {
                if (result != PixelCopy.SUCCESS) { bmp.recycle(); toast("Couldn't take a screenshot"); return; }
                playFlash();
                if (io == null || io.isShutdown()) { bmp.recycle(); return; }
                io.execute(() -> {
                    Uri saved = ScreenshotSaver.save(getApplicationContext(), bmp);
                    bmp.recycle();
                    handler.post(() -> toast(saved != null ? "Screenshot saved to Pictures/Screenshots" : "Couldn't save the screenshot"));
                });
            }, handler);
        } catch (Exception e) {
            bmp.recycle();
            toast("Couldn't take a screenshot");
        }
    }

    private void playFlash() {
        flash.animate().cancel();
        flash.setVisibility(View.VISIBLE);
        flash.setAlpha(0.8f);
        flash.animate().alpha(0f).setDuration(220).withEndAction(() -> flash.setVisibility(View.GONE)).start();
    }

    // ------------------------------------------------------------------ fullscreen

    boolean isFullscreenUi() { return customView != null || appFullscreen || autoFullscreen; }

    void enterAppFullscreen() {
        appFullscreen = true;
        updateFullscreenUi();
        toast("Press Back to leave fullscreen");
    }

    private boolean nearAppSwitch() {
        long now = SystemClock.uptimeMillis();
        return !resumed || now - lastHideAt < SWITCH_WINDOW_MS || now - lastShowAt < SWITCH_WINDOW_MS;
    }

    private void exitPageFullscreenByUser() {
        userExitingFs = true;
        WebChromeClient.CustomViewCallback cb = customCallback;
        if (cb != null) cb.onCustomViewHidden();
        if (customView != null) chromeClient.onHideCustomView();
        userExitingFs = false;
    }

    private void updateFullscreenUi() {
        boolean fs = isFullscreenUi();
        topBar.setVisibility(fs ? View.GONE : View.VISIBLE);
        progress.setVisibility(fs ? View.GONE : (loading ? View.VISIBLE : View.INVISIBLE));

        Window w = getWindow();
        if (fs && Prefs.b(this, Prefs.SCREEN_ON, false)) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        WindowManager.LayoutParams lp = w.getAttributes();
        int mode = !fs ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                : Build.VERSION.SDK_INT >= 30 ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        if (lp.layoutInDisplayCutoutMode != mode) {
            lp.layoutInDisplayCutoutMode = mode;
            w.setAttributes(lp);
        }
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, w.getDecorView());
        if (fs) {
            c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            c.hide(WindowInsetsCompat.Type.systemBars());
        } else {
            c.show(WindowInsetsCompat.Type.systemBars());
        }
        ViewCompat.requestApplyInsets(root);
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings st = web.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setMediaPlaybackRequiresUserGesture(false);
        st.setLoadWithOverviewMode(true);
        st.setUseWideViewPort(true);
        st.setSupportZoom(true);
        st.setBuiltInZoomControls(true);
        st.setDisplayZoomControls(false);
        st.setAllowFileAccess(false);
        st.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        CookieManager.getInstance().setAcceptCookie(true);

        // Normal user-agent, minus the "embedded WebView" markers that some sites block.
        String base = st.getUserAgentString();
        defaultUa = base == null ? "" : base.replace("; wv)", ")").replaceFirst("Version/\\d+(\\.\\d+)* ", "");
        st.setUserAgentString(defaultUa);

        try { docStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT); }
        catch (Throwable t) { docStartSupported = false; }
        try {
            metaSupported = WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA);
            if (metaSupported) defaultMeta = WebSettingsCompat.getUserAgentMetadata(st);
        } catch (Throwable t) {
            metaSupported = false;
        }

        web.setWebViewClient(new Client());
        web.setWebChromeClient(chromeClient);
        web.setDownloadListener(this::download);
        applyWebPrefs();
        syncKeepScript();
    }

    private void applyWebPrefs() {
        WebSettings st = web.getSettings();
        int zoom = Prefs.i(this, Prefs.TEXT_ZOOM, 100);
        if (st.getTextZoom() != zoom) st.setTextZoom(zoom);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, Prefs.b(this, Prefs.THIRD_PARTY_COOKIES, true));
    }

    private void syncKeepScript() {
        if (!docStartSupported) return;
        // When exit-on-return is active, also stop pages from restoring their own fullscreen.
        String keep = RemoteFlag.exitOnReturn(this) ? "window.__fsNoRestore=true;" + keepJs : keepJs;
        if (!keep.equals(keepJsApplied)) {                 // script contents changed: re-add it
            keepJsApplied = keep;
            if (keepHandler != null) { try { keepHandler.remove(); } catch (Throwable ignored) { } keepHandler = null; }
        }
        boolean want = Prefs.b(this, Prefs.KEEP_FS, true);
        try {
            if (want && keepHandler == null) {
                keepHandler = WebViewCompat.addDocumentStartJavaScript(web, keep, ALL_ORIGINS);
            } else if (!want && keepHandler != null) {
                keepHandler.remove();
                keepHandler = null;
            }
        } catch (Throwable t) {
            docStartSupported = false;                    // fall back to injecting on each page
        }
    }

    /** Applies the right user-agent for this URL. Returns true if it changed. */
    private boolean applyUaFor(String url) {
        String ua = UaManager.uaForUrl(this, url);
        boolean desktopLayout = ua != null && UaManager.isDesktop(ua) && Prefs.b(this, Prefs.UA_DESKTOP_LAYOUT, true);
        String jsUa = ua != null && Prefs.b(this, Prefs.UA_SPOOF_JS, true) ? ua : null;
        String key = jsUa == null && !desktopLayout ? null : jsUa + "|" + desktopLayout;
        if (!Objects.equals(key, uaScriptKey)) {
            uaScriptKey = key;
            uaScriptNow = key == null ? null : uaJsTemplate
                    .replace("__UA__", jsUa == null ? "null" : JSONObject.quote(jsUa))
                    .replace("__DESKTOP__", desktopLayout ? "true" : "false");
            if (docStartSupported) {
                try {
                    if (uaHandler != null) { uaHandler.remove(); uaHandler = null; }
                    if (uaScriptNow != null) uaHandler = WebViewCompat.addDocumentStartJavaScript(web, uaScriptNow, ALL_ORIGINS);
                } catch (Throwable t) {
                    docStartSupported = false;
                }
            }
        }

        String target = ua != null ? ua : defaultUa;
        WebSettings st = web.getSettings();
        if (target.equals(st.getUserAgentString())) return false;
        if (metaSupported) {
            try {
                UserAgentMetadata meta = ua != null ? UaManager.metaFor(ua) : defaultMeta;
                if (meta != null) WebSettingsCompat.setUserAgentMetadata(st, meta);
            } catch (Throwable ignored) { }
        }
        st.setUserAgentString(target);
        return true;
    }

    /** For WebViews without document-start scripts: inject as early as possible. */
    private void injectFallback() {
        if (Prefs.b(this, Prefs.KEEP_FS, true)) {
            if (RemoteFlag.exitOnReturn(this)) web.evaluateJavascript("window.__fsNoRestore=true;", null);
            web.evaluateJavascript(keepJs, null);
        }
        if (uaScriptNow != null) web.evaluateJavascript(uaScriptNow, null);
    }

    void load(String url) {
        hideError();
        applyUaFor(url);
        web.loadUrl(url);
    }

    void go(String text) {
        String url = UrlUtil.toUrl(text, Prefs.s(this, Prefs.SEARCH, Prefs.DEF_SEARCH));
        if (url == null) return;
        stopEditing();
        load(url);
    }

    private String currentUrl() {
        String u = web.getUrl();
        return u == null ? "about:blank" : u;
    }

    private void download(String url, String userAgent, String disposition, String mime, long length) {
        if (!UrlUtil.isHttp(url)) {   // blob:/data: files are made by the page; Android's downloader can't fetch them
            toast("This file was made by the page itself \u2014 FullStay can't save that kind of download");
            return;
        }
        try {
            String name = URLUtil.guessFileName(url, disposition, mime);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setMimeType(mime);
            r.addRequestHeader("User-Agent", userAgent);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) r.addRequestHeader("Cookie", cookie);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm == null) throw new IllegalStateException("no download manager");
            dm.enqueue(r);
            toast("Downloading " + name);
        } catch (Exception e) {
            openExternal(url);
        }
    }

    private void askOpenExternal(String url) {
        Snackbar.make(root, "This page wants to open another app", Snackbar.LENGTH_LONG)
                .setAction("Open", v -> openExternal(url))
                .show();
    }

    private void openExternal(String url) {
        try {
            if (url.startsWith("intent:")) {
                Intent i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                i.addCategory(Intent.CATEGORY_BROWSABLE);
                i.setComponent(null);
                i.setSelector(null);
                try {
                    startActivity(i);
                } catch (Exception e) {
                    String fallback = i.getStringExtra("browser_fallback_url");
                    if (fallback != null && UrlUtil.isHttp(fallback)) load(fallback);
                    else toast("No app can open this link");
                }
                return;
            }
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("No app can open this link");
        }
    }

    /** Links from other apps: web pages only (never javascript:, file: or content: links). */
    private static String urlFromIntent(Intent i) {
        if (i == null || !Intent.ACTION_VIEW.equals(i.getAction()) || i.getData() == null) return null;
        String u = i.getDataString();
        return UrlUtil.isHttp(u) ? u : null;
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "";
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ------------------------------------------------------------------ test hooks

    WebView webForTest() { return web; }

    WebChromeClient chromeForTest() { return chromeClient; }

    View topBarForTest() { return topBar; }

    View fullscreenBoxForTest() { return fsContainer; }

    String keepScriptForTest() { return keepJs; }

    /** What the keep-fullscreen script looks like as applied to a page right now (either injection path). */
    String keepScriptAppliedToPageForTest() {
        return (RemoteFlag.exitOnReturn(this) ? "window.__fsNoRestore=true;" : "") + keepJs;
    }

    View rootForTest() { return root; }

    boolean autoFullscreenForTest() { return autoFullscreen; }

    TextView errorForTest() { return errorView.getVisibility() == View.VISIBLE ? errorText : null; }

    // ------------------------------------------------------------------ clients

    private class Client extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
            Uri u = req.getUrl();
            String s = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (s.equals("http") || s.equals("https")) {
                // Per-website rules: switch the user-agent before going to the new site.
                if (req.isForMainFrame() && "GET".equalsIgnoreCase(req.getMethod()) && applyUaFor(u.toString())) {
                    v.loadUrl(u.toString());
                    return true;
                }
                return false;
            }
            if (s.equals("about") || s.equals("data") || s.equals("blob") || s.equals("javascript")) return false;
            if (req.hasGesture()) openExternal(u.toString());
            else askOpenExternal(u.toString());      // the page tried to open an app without a tap
            return true;
        }

        @Override public void onPageStarted(WebView v, String url, Bitmap icon) {
            loading = true;
            // WebView may report a failed page's start after its error, so only a *different* page clears it.
            if (errorUrl == null || !errorUrl.equals(url)) hideError();
            applyUaFor(url);
            if (!docStartSupported) injectFallback();
            if (!isFullscreenUi()) { progress.setProgressCompat(5, false); progress.setVisibility(View.VISIBLE); }
            showUrl(url);
        }

        @Override public void onPageFinished(WebView v, String url) {
            loading = false;
            if (!docStartSupported) injectFallback();
            progress.setVisibility(isFullscreenUi() ? View.GONE : View.INVISIBLE);
            showUrl(url);
            if (UrlUtil.isHttp(url)) Prefs.p(MainActivity.this).edit().putString(Prefs.LAST_URL, url).apply();
        }

        @Override public void doUpdateVisitedHistory(WebView v, String url, boolean isReload) { showUrl(url); }

        @Override public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
            if (req.isForMainFrame() && !String.valueOf(err.getDescription()).contains("ERR_ABORTED")) {
                showError(req.getUrl().toString(), err.getDescription(), false);
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        @Override public void onReceivedSslError(WebView v, SslErrorHandler h, SslError error) {
            h.cancel();   // never load pages with a bad certificate
            showError(error.getUrl(), "This site's security certificate isn't trusted, so FullStay didn't open it.", true);
        }
    }

    private class Chrome extends WebChromeClient {
        @Override public void onProgressChanged(WebView v, int p) {
            progress.setProgressCompat(Math.max(5, p), true);
        }

        @Override public void onShowCustomView(View view, CustomViewCallback cb) {
            if (customView != null) { cb.onCustomViewHidden(); return; }
            customView = view;
            customCallback = cb;
            fsContainer.addView(view, new FrameLayout.LayoutParams(-1, -1));
            fsContainer.setVisibility(View.VISIBLE);
            autoFullscreen = false;
            updateFullscreenUi();
        }

        @Override public void onHideCustomView() {
            if (customView == null) return;
            fsContainer.removeView(customView);
            fsContainer.setVisibility(View.GONE);
            customView = null;
            customCallback = null;
            // Caused by an app switch (not by you pressing Back or the page's own exit button)?
            // Then keep the whole app fullscreen so nothing visibly changes.
            if (!userExitingFs && Prefs.b(MainActivity.this, Prefs.KEEP_FS, true) && nearAppSwitch()) {
                autoFullscreen = true;
            }
            updateFullscreenUi();
        }

        @Override public Bitmap getDefaultVideoPoster() {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }

        @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = cb;
            try {
                fileLauncher.launch(params.createIntent());
            } catch (Exception e) {
                fileCallback = null;
                return false;
            }
            return true;
        }

        @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }

        @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
            cb.invoke(origin, false, false);   // answer "no" right away so the page doesn't wait forever
        }
    }
}
