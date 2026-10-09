package com.fullstay.browser;

import android.view.KeyEvent;

/** Decides what a volume-button event should do. Pure logic, so it's easy to test. */
final class KeyLogic {
    private KeyLogic() {}

    enum Action {
        /** Let Android handle it (normal volume). */
        PASS,
        /** Swallow it and do nothing (key-up, or the button being held). */
        CONSUME,
        SCREENSHOT,
        SWITCH
    }

    /**
     * @param passThrough true on the lock screen or during a call: buttons work normally there.
     */
    static Action decide(int keyCode, int action, int repeatCount,
                         boolean enabled, boolean upScreenshot, boolean downSwitch, boolean passThrough) {
        boolean up = keyCode == KeyEvent.KEYCODE_VOLUME_UP;
        boolean down = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN;
        if (!up && !down) return Action.PASS;
        if (!enabled || passThrough) return Action.PASS;
        if (up && !upScreenshot) return Action.PASS;
        if (down && !downSwitch) return Action.PASS;
        // Act immediately on the press (not on release) so it feels instant. Holding does nothing more.
        if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) return up ? Action.SCREENSHOT : Action.SWITCH;
        return Action.CONSUME;
    }
}
