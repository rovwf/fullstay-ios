package com.fullstay.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.core.widget.NestedScrollView;
import androidx.core.widget.TextViewCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;

/** Small Material 3 building blocks so the screens can be built in code. */
final class Ui {
    private Ui() {}

    static final int ATTR_SURFACE = com.google.android.material.R.attr.colorSurface;
    static final int ATTR_SURFACE_CONTAINER = com.google.android.material.R.attr.colorSurfaceContainer;
    static final int ATTR_SURFACE_HIGHEST = com.google.android.material.R.attr.colorSurfaceContainerHighest;
    static final int ATTR_ON_SURFACE = com.google.android.material.R.attr.colorOnSurface;
    static final int ATTR_ON_SURFACE_VARIANT = com.google.android.material.R.attr.colorOnSurfaceVariant;
    static final int ATTR_OUTLINE_VARIANT = com.google.android.material.R.attr.colorOutlineVariant;
    static final int ATTR_PRIMARY = androidx.appcompat.R.attr.colorPrimary;
    static final int ATTR_ERROR = androidx.appcompat.R.attr.colorError;

    static final class Row {
        final LinearLayout view;
        final TextView title, summary;
        MaterialSwitch toggle;

        Row(LinearLayout view, TextView title, TextView summary) {
            this.view = view; this.title = title; this.summary = summary;
        }

        void setSummary(CharSequence s) {
            summary.setText(s);
            summary.setVisibility(s == null || s.length() == 0 ? View.GONE : View.VISIBLE);
        }
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static int color(Context c, int attr) { return MaterialColors.getColor(c, attr, Color.GRAY); }

    static void text(TextView t, int appearance) { TextViewCompat.setTextAppearance(t, appearance); }

    static void ripple(View v, boolean borderless) {
        TypedValue tv = new TypedValue();
        v.getContext().getTheme().resolveAttribute(borderless ? android.R.attr.selectableItemBackgroundBorderless
                : android.R.attr.selectableItemBackground, tv, true);
        v.setBackgroundResource(tv.resourceId);
    }

    static ImageView icon(Context c, int res, int attrTint, int sizeDp) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(res);
        ImageViewCompat.setImageTintList(iv, ColorStateList.valueOf(color(c, attrTint)));
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return iv;
    }

    /** A settings-style screen: top app bar + scrolling column. Returns the column. */
    static LinearLayout screen(AppCompatActivity a, String title) {
        EdgeToEdge.enable(a);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(color(a, ATTR_SURFACE));

        MaterialToolbar bar = new MaterialToolbar(a);
        bar.setTitle(title);
        bar.setNavigationIcon(R.drawable.ic_arrow_back);
        bar.setNavigationIconTint(color(a, ATTR_ON_SURFACE));
        bar.setNavigationContentDescription("Back");
        bar.setNavigationOnClickListener(v -> a.getOnBackPressedDispatcher().onBackPressed());
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        NestedScrollView scroll = new NestedScrollView(a);
        scroll.setClipToPadding(false);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(col, new NestedScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        a.setContentView(root);

        int pad = dp(a, 16);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets b = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            bar.setPadding(b.left, b.top, b.right, 0);
            col.setPadding(pad + b.left, 0, pad + b.right, pad * 2 + Math.max(b.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
        return col;
    }

    static TextView section(LinearLayout parent, String text) {
        Context c = parent.getContext();
        TextView t = new TextView(c);
        text(t, com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        t.setTextColor(color(c, ATTR_PRIMARY));
        t.setText(text);
        t.setPadding(dp(c, 16), dp(c, 20), dp(c, 16), dp(c, 8));
        parent.addView(t);
        return t;
    }

    /** A rounded card; returns the vertical layout inside it. */
    static LinearLayout card(LinearLayout parent) {
        Context c = parent.getContext();
        MaterialCardView card = new MaterialCardView(c);
        card.setCardBackgroundColor(color(c, ATTR_SURFACE_CONTAINER));
        card.setStrokeWidth(0);
        card.setCardElevation(0);
        card.setRadius(dp(c, 24));
        LinearLayout inner = new LinearLayout(c);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(0, dp(c, 6), 0, dp(c, 6));
        card.addView(inner, new MaterialCardView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(c, 4);
        parent.addView(card, lp);
        return inner;
    }

    static TextView note(LinearLayout parent, String text) {
        Context c = parent.getContext();
        TextView t = new TextView(c);
        text(t, com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        t.setTextColor(color(c, ATTR_ON_SURFACE_VARIANT));
        t.setText(text);
        t.setPadding(dp(c, 16), dp(c, 6), dp(c, 16), dp(c, 10));
        parent.addView(t);
        return t;
    }

    /** A tappable row with a title, optional summary and a chevron. */
    static Row row(LinearLayout parent, String title, CharSequence summary, View.OnClickListener l) {
        Row r = baseRow(parent, title, summary);
        if (l != null) {
            ImageView chevron = icon(parent.getContext(), R.drawable.ic_chevron_right, ATTR_ON_SURFACE_VARIANT, 20);
            chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);   // decoration only
            r.view.addView(chevron);
            r.view.setOnClickListener(l);
            ripple(r.view, false);
        }
        return r;
    }

    /** A row with a switch bound to a saved setting. */
    static Row switchRow(LinearLayout parent, String title, CharSequence summary, String key, boolean def, Runnable after) {
        Context c = parent.getContext();
        SharedPreferences p = Prefs.p(c);
        Row r = baseRow(parent, title, summary);
        MaterialSwitch sw = new MaterialSwitch(c);
        sw.setContentDescription(title);                  // screen readers say which setting this is
        sw.setChecked(p.getBoolean(key, def));
        sw.setOnCheckedChangeListener((b, v) -> {
            p.edit().putBoolean(key, v).apply();
            if (after != null) after.run();
        });
        r.view.addView(sw, new LinearLayout.LayoutParams(-2, -2));
        r.toggle = sw;
        r.view.setOnClickListener(v -> sw.toggle());
        ripple(r.view, false);
        return r;
    }

    private static Row baseRow(LinearLayout parent, String title, CharSequence summary) {
        Context c = parent.getContext();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(c, 60));
        row.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(c);
        text(t, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        t.setTextColor(color(c, ATTR_ON_SURFACE));
        t.setText(title);
        TextView s = new TextView(c);
        text(s, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        s.setTextColor(color(c, ATTR_ON_SURFACE_VARIANT));
        texts.addView(t);
        texts.addView(s);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.setMarginEnd(dp(c, 12));
        row.addView(texts, tl);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        Row r = new Row(row, t, s);
        r.setSummary(summary);
        return r;
    }

    /** Filled (primary) or outlined (secondary) button inside a card. */
    static MaterialButton button(LinearLayout parent, String text, boolean primary, View.OnClickListener l) {
        Context c = parent.getContext();
        MaterialButton b = primary ? new MaterialButton(c)
                : new MaterialButton(c, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(text);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(c, 16), dp(c, 4), dp(c, 16), dp(c, 4));
        parent.addView(b, lp);
        return b;
    }

    static void divider(LinearLayout parent) {
        Context c = parent.getContext();
        View d = new View(c);
        d.setBackgroundColor(color(c, ATTR_OUTLINE_VARIANT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(c, 1) / 2));
        lp.setMargins(dp(c, 16), dp(c, 4), dp(c, 16), dp(c, 4));
        parent.addView(d, lp);
    }
}
