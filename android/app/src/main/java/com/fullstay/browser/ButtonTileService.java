package com.fullstay.browser;

import android.content.ComponentName;
import android.content.Context;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile: turn the volume-button shortcuts on/off (e.g. when you need normal volume). */
public class ButtonTileService extends TileService {

    static void refresh(Context c) {
        try { TileService.requestListeningState(c, new ComponentName(c, ButtonTileService.class)); } catch (Exception ignored) { }
    }

    @Override public void onStartListening() { update(); }

    @Override public void onClick() {
        Prefs.p(this).edit().putBoolean(Prefs.KEYS_ENABLED, !Prefs.keysEnabled(this)).apply();
        update();
    }

    private void update() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean on = Prefs.keysEnabled(this);
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel(getString(R.string.tile_label));
        t.setSubtitle(!on ? "Off \u2014 normal volume" : ButtonService.isEnabled(this) ? "On" : "Needs setup");
        t.updateTile();
    }
}
