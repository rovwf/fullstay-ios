package com.fullstay.browser;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** The built-in User-Agent Switcher's settings. */
public class UaActivity extends AppCompatActivity {
    private interface Picked { void on(String name, String ua); }

    static final String[][] MODES = {
            {"off", "Off", "Websites see this phone's normal user-agent"},
            {"all", "All websites", "Use the user-agent below everywhere"},
            {"whitelist", "Only websites in my list", "Everything else stays normal"},
            {"blacklist", "All websites except my list", "Listed websites stay normal"},
    };

    private SharedPreferences p;
    private RadioGroup modeGroup;
    private TextView currentName, currentUa;
    private Ui.Row randomRow, poolRow;
    private TextInputEditText sitesEdit;
    private LinearLayout rulesBox, customBox;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = Prefs.p(this);
        LinearLayout page = Ui.screen(this, "User-Agent Switcher");
        Ui.note(page, "Choose which browser and device websites think you're using. "
                + "Tip: \u22EE \u2192 User-agent in the browser switches quickly.");

        // Mode
        Ui.section(page, "Mode");
        LinearLayout modeCard = Ui.card(page);
        modeGroup = new RadioGroup(this);
        modeGroup.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 16), 0);
        String cur = p.getString(Prefs.UA_MODE, "off");
        for (String[] m : MODES) {
            MaterialRadioButton rb = new MaterialRadioButton(this);
            rb.setId(View.generateViewId());
            rb.setTag(m[0]);
            rb.setText(m[1] + "\n" + m[2]);
            rb.setPadding(Ui.dp(this, 8), Ui.dp(this, 10), 0, Ui.dp(this, 10));
            modeGroup.addView(rb, new RadioGroup.LayoutParams(-1, -2));
            if (m[0].equals(cur)) modeGroup.check(rb.getId());
        }
        modeGroup.setOnCheckedChangeListener((g, id) -> {
            View v = g.findViewById(id);
            if (v != null) p.edit().putString(Prefs.UA_MODE, (String) v.getTag()).apply();
            refreshCurrent();
        });
        modeCard.addView(modeGroup);

        // Current
        Ui.section(page, "User-agent");
        LinearLayout curCard = Ui.card(page);
        currentName = new TextView(this);
        Ui.text(currentName, com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        currentName.setTextColor(Ui.color(this, Ui.ATTR_ON_SURFACE));
        currentName.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 4));
        curCard.addView(currentName);
        currentUa = new TextView(this);
        Ui.text(currentUa, com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        currentUa.setTypeface(Typeface.MONOSPACE);
        currentUa.setTextColor(Ui.color(this, Ui.ATTR_ON_SURFACE_VARIANT));
        currentUa.setTextIsSelectable(true);
        currentUa.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 10));
        curCard.addView(currentUa);
        Ui.button(curCard, "Choose from list", true, v -> pickPreset(false, this::useAsGlobal));
        Ui.button(curCard, "Enter a custom user-agent", false, v -> customDialog(this::useAsGlobal, false));

        // Random
        Ui.section(page, "Random");
        LinearLayout rnd = Ui.card(page);
        randomRow = Ui.switchRow(rnd, "New random user-agent each time you open FullStay", null, Prefs.UA_RANDOM, false, () -> {
            if (p.getBoolean(Prefs.UA_RANDOM, false)) {
                UaManager.reroll(this);
                if ("off".equals(p.getString(Prefs.UA_MODE, "off"))) setMode("all");
            }
            refreshCurrent();
        });
        poolRow = Ui.row(rnd, "Pick from", poolName(), v -> choosePool());
        Ui.button(rnd, "Pick a new random one now", false, v -> {
            if (!p.getBoolean(Prefs.UA_RANDOM, false)) randomRow.toggle.setChecked(true);
            else { UaManager.reroll(this); refreshCurrent(); }
        });

        // Website list
        Ui.section(page, "My website list");
        LinearLayout listCard = Ui.card(page);
        Ui.note(listCard, "One website per line, like example.com (its subdomains count too). "
                + "Used by the \u201COnly websites in my list\u201D and \u201CAll websites except my list\u201D modes.");
        TextInputLayout til = new TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        til.setHint("Websites");
        sitesEdit = new TextInputEditText(til.getContext());
        sitesEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_VARIATION_URI);
        sitesEdit.setMinLines(3);
        sitesEdit.setGravity(Gravity.TOP | Gravity.START);
        sitesEdit.setText(p.getString(Prefs.UA_SITES, ""));
        til.addView(sitesEdit);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.setMargins(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 12));
        listCard.addView(til, tl);

        // Rules
        Ui.section(page, "Per-website rules");
        LinearLayout rulesCard = Ui.card(page);
        Ui.note(rulesCard, "A rule sets the user-agent for one website and beats the settings above "
                + "(except when Mode is Off). Tap a rule to change or delete it.");
        rulesBox = new LinearLayout(this);
        rulesBox.setOrientation(LinearLayout.VERTICAL);
        rulesCard.addView(rulesBox);
        Ui.button(rulesCard, "Add a rule", false, v -> addRule());

        // Options
        Ui.section(page, "Options");
        LinearLayout opt = Ui.card(page);
        Ui.switchRow(opt, "Also change what JavaScript sees",
                "navigator.userAgent, platform, vendor and userAgentData", Prefs.UA_SPOOF_JS, true, null);
        Ui.switchRow(opt, "Desktop layout for desktop user-agents",
                "Pages render like on a computer", Prefs.UA_DESKTOP_LAYOUT, true, null);

        // Saved
        Ui.section(page, "My saved user-agents");
        LinearLayout savedCard = Ui.card(page);
        customBox = new LinearLayout(this);
        customBox.setOrientation(LinearLayout.VERTICAL);
        savedCard.addView(customBox);
        Ui.button(savedCard, "Add a user-agent", false, v -> customDialog(null, true));

        // Backup & test
        Ui.section(page, "Backup & test");
        LinearLayout bk = Ui.card(page);
        Ui.row(bk, "Copy settings to clipboard", "Rules, list, saved user-agents and options", v -> exportSettings());
        Ui.row(bk, "Import settings from clipboard", null, v -> importSettings());
        Ui.row(bk, "Reset all user-agent settings", null, v -> new MaterialAlertDialogBuilder(this)
                .setTitle("Reset everything?")
                .setMessage("Mode, rules, website list and saved user-agents will be removed.")
                .setPositiveButton("Reset", (d, w) -> { UaManager.reset(this); reloadScreen(); })
                .setNegativeButton("Cancel", null).show());
        Ui.row(bk, "Show what websites see", "Opens a test page in the browser", v -> {
            saveSites();
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://httpbin.org/headers"), this, MainActivity.class));
        });

        refreshCurrent();
        refreshRules();
        refreshCustom();
    }

    @Override protected void onPause() {
        super.onPause();
        saveSites();
    }

    private void saveSites() {
        if (sitesEdit != null && sitesEdit.getText() != null)
            p.edit().putString(Prefs.UA_SITES, sitesEdit.getText().toString()).apply();
    }

    // ---- current / random

    private void useAsGlobal(String name, String ua) {
        if (ua == null || ua.isEmpty()) return;
        UaManager.select(this, name, ua);
        if (randomRow.toggle.isChecked()) randomRow.toggle.setChecked(false);
        setMode(p.getString(Prefs.UA_MODE, "all"));
        refreshCurrent();
    }

    private void setMode(String mode) {
        for (int i = 0; i < modeGroup.getChildCount(); i++) {
            View v = modeGroup.getChildAt(i);
            if (mode.equals(v.getTag())) { modeGroup.check(v.getId()); break; }
        }
    }

    private void refreshCurrent() {
        String label = UaManager.selectedLabel(this);
        if ("off".equals(p.getString(Prefs.UA_MODE, "off"))) label += "  \u00B7  switcher is off";
        currentName.setText(label);
        String ua = UaManager.globalUa(this);
        currentUa.setText(ua == null || ua.isEmpty() ? "Choose one below." : ua);
    }

    private String poolName() {
        switch (p.getString(Prefs.UA_RANDOM_GROUP, "any")) {
            case "desktop": return "Desktop browsers only";
            case "mobile": return "Phone and tablet browsers only";
            default: return "Any browser";
        }
    }

    private void choosePool() {
        String[] opts = {"Any browser", "Desktop browsers only", "Phone and tablet browsers only"};
        String[] keys = {"any", "desktop", "mobile"};
        int checked = 0;
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(p.getString(Prefs.UA_RANDOM_GROUP, "any"))) checked = i;
        new MaterialAlertDialogBuilder(this).setTitle("Pick from")
                .setSingleChoiceItems(opts, checked, (d, w) -> {
                    p.edit().putString(Prefs.UA_RANDOM_GROUP, keys[w]).apply();
                    poolRow.setSummary(poolName());
                    if (p.getBoolean(Prefs.UA_RANDOM, false)) { UaManager.reroll(this); refreshCurrent(); }
                    d.dismiss();
                }).show();
    }

    // ---- pickers

    private void pickPreset(boolean withNoChange, Picked cb) {
        List<UaManager.Preset> list = UaManager.allPresets(this);
        int extra = withNoChange ? 1 : 0;
        String[] labels = new String[list.size() + extra + 1];
        if (withNoChange) labels[0] = "Normal user-agent (no change on this site)";
        for (int i = 0; i < list.size(); i++) labels[i + extra] = list.get(i).label();
        labels[labels.length - 1] = "Custom\u2026";
        new MaterialAlertDialogBuilder(this).setTitle("Choose a user-agent").setItems(labels, (d, w) -> {
            if (withNoChange && w == 0) cb.on("", "");
            else if (w == labels.length - 1) customDialog(cb, false);
            else { UaManager.Preset pr = list.get(w - extra); cb.on(pr.label(), pr.ua); }
        }).setNegativeButton("Cancel", null).show();
    }

    private void customDialog(Picked cb, boolean saveOnly) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 24);
        box.setPadding(pad, Ui.dp(this, 8), pad, 0);
        TextInputLayout nameL = new TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        nameL.setHint("Name (optional)");
        TextInputEditText name = new TextInputEditText(nameL.getContext());
        name.setSingleLine(true);
        nameL.addView(name);
        TextInputLayout uaL = new TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        uaL.setHint("User-agent");
        uaL.setPlaceholderText("Mozilla/5.0 (...)");
        TextInputEditText ua = new TextInputEditText(uaL.getContext());
        ua.setMinLines(3);
        ua.setGravity(Gravity.TOP | Gravity.START);
        ua.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        ua.setTypeface(Typeface.MONOSPACE);
        uaL.addView(ua);
        box.addView(nameL, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(-1, -2);
        ul.topMargin = Ui.dp(this, 8);
        box.addView(uaL, ul);

        MaterialAlertDialogBuilder bld = new MaterialAlertDialogBuilder(this)
                .setTitle(saveOnly ? "Save a user-agent" : "Custom user-agent")
                .setView(box)
                .setNegativeButton("Cancel", null);
        if (saveOnly) {
            bld.setPositiveButton("Save", (d, w) -> {
                String s = textOf(ua);
                if (s.isEmpty()) { toast("The user-agent is empty"); return; }
                UaManager.addCustom(this, textOf(name), s);
                refreshCustom();
            });
        } else {
            bld.setPositiveButton("Use", (d, w) -> {
                String s = textOf(ua);
                if (s.isEmpty()) { toast("The user-agent is empty"); return; }
                String n = textOf(name);
                cb.on(n.isEmpty() ? "Custom" : n, s);
            });
            bld.setNeutralButton("Use & save", (d, w) -> {
                String s = textOf(ua);
                if (s.isEmpty()) { toast("The user-agent is empty"); return; }
                String n = textOf(name);
                UaManager.addCustom(this, n, s);
                refreshCustom();
                cb.on("\u2605 " + (n.isEmpty() ? "Custom" : n), s);
            });
        }
        bld.show();
    }

    private static String textOf(TextInputEditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    // ---- rules

    private void refreshRules() {
        rulesBox.removeAllViews();
        JSONObject r = UaManager.rules(this);
        List<String> hosts = new ArrayList<>();
        Iterator<String> it = r.keys();
        while (it.hasNext()) hosts.add(it.next());
        Collections.sort(hosts);
        if (hosts.isEmpty()) { Ui.note(rulesBox, "No rules yet."); return; }
        for (String h : hosts) {
            String ua = r.optString(h, "");
            Ui.row(rulesBox, h, UaManager.nameFor(this, ua), v -> ruleOptions(h));
        }
    }

    private void addRule() {
        TextInputLayout til = new TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        til.setHint("Website, like example.com");
        TextInputEditText host = new TextInputEditText(til.getContext());
        host.setSingleLine(true);
        host.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        til.addView(host);
        LinearLayout box = new LinearLayout(this);
        int pad = Ui.dp(this, 24);
        box.setPadding(pad, Ui.dp(this, 8), pad, 0);
        box.addView(til, new LinearLayout.LayoutParams(-1, -2));
        new MaterialAlertDialogBuilder(this).setTitle("Add a rule").setView(box)
                .setPositiveButton("Next", (d, w) -> {
                    String h = UaManager.normalizeHost(textOf(host));
                    if (h.isEmpty()) { toast("Type a website first"); return; }
                    pickPreset(true, (name, ua) -> { UaManager.putRule(this, h, ua); refreshRules(); });
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void ruleOptions(String host) {
        new MaterialAlertDialogBuilder(this).setTitle(host)
                .setItems(new String[]{"Change user-agent", "Delete rule"}, (d, w) -> {
                    if (w == 0) pickPreset(true, (name, ua) -> { UaManager.putRule(this, host, ua); refreshRules(); });
                    else { UaManager.removeRule(this, host); refreshRules(); }
                }).show();
    }

    // ---- saved user-agents

    private void refreshCustom() {
        customBox.removeAllViews();
        JSONArray arr = UaManager.customs(this);
        if (arr.length() == 0) { Ui.note(customBox, "Nothing saved yet."); return; }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            final int index = i;
            final String n = o.optString("name", "Custom"), ua = o.optString("ua", "");
            Ui.row(customBox, "\u2605 " + n, ua, v -> new MaterialAlertDialogBuilder(this).setTitle(n)
                    .setItems(new String[]{"Use it now", "Delete"}, (d, w) -> {
                        if (w == 0) useAsGlobal("\u2605 " + n, ua);
                        else { UaManager.removeCustom(this, index); refreshCustom(); }
                    }).show());
        }
    }

    // ---- backup

    private void exportSettings() {
        try {
            saveSites();
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.setPrimaryClip(ClipData.newPlainText("FullStay user-agent settings", UaManager.exportJson(this)));
            toast("Settings copied to the clipboard");
        } catch (Exception e) {
            toast("Couldn't export the settings");
        }
    }

    private void importSettings() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData clip = cm == null ? null : cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) { toast("The clipboard is empty"); return; }
            UaManager.importJson(this, clip.getItemAt(0).coerceToText(this).toString());
            toast("Settings imported");
            reloadScreen();
        } catch (Exception e) {
            toast("That doesn't look like FullStay settings");
        }
    }

    /** Rebuilds the screen from saved settings (without saving the old list text over them). */
    private void reloadScreen() {
        sitesEdit.setText(p.getString(Prefs.UA_SITES, ""));
        recreate();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
