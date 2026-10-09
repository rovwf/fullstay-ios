package com.fullstay.browser;

import static com.fullstay.browser.KeyLogic.Action.CONSUME;
import static com.fullstay.browser.KeyLogic.Action.PASS;
import static com.fullstay.browser.KeyLogic.Action.SCREENSHOT;
import static com.fullstay.browser.KeyLogic.Action.SWITCH;
import static org.junit.Assert.assertEquals;

import android.view.KeyEvent;

import org.junit.Test;

public class KeyLogicTest {
    private static KeyLogic.Action d(int key, int action, int repeat) {
        return KeyLogic.decide(key, action, repeat, true, true, true, false);
    }

    @Test public void onePressActsImmediately() {
        assertEquals(SCREENSHOT, d(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN, 0));
        assertEquals(SWITCH, d(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN, 0));
    }

    @Test public void releaseAndHoldAreSwallowed() {
        assertEquals(CONSUME, d(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_UP, 0));
        assertEquals(CONSUME, d(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_UP, 0));
        assertEquals(CONSUME, d(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN, 1));
        assertEquals(CONSUME, d(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN, 5));
    }

    @Test public void otherKeysPass() {
        assertEquals(PASS, d(KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.ACTION_DOWN, 0));
        assertEquals(PASS, d(KeyEvent.KEYCODE_POWER, KeyEvent.ACTION_DOWN, 0));
        assertEquals(PASS, d(KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN, 0));
    }

    @Test public void switchesTurnButtonsBackIntoVolume() {
        int up = KeyEvent.KEYCODE_VOLUME_UP, down = KeyEvent.KEYCODE_VOLUME_DOWN, dn = KeyEvent.ACTION_DOWN;
        assertEquals(PASS, KeyLogic.decide(up, dn, 0, false, true, true, false));
        assertEquals(PASS, KeyLogic.decide(down, dn, 0, false, true, true, false));
        assertEquals(PASS, KeyLogic.decide(up, dn, 0, true, false, true, false));
        assertEquals(SWITCH, KeyLogic.decide(down, dn, 0, true, false, true, false));
        assertEquals(SCREENSHOT, KeyLogic.decide(up, dn, 0, true, true, false, false));
        assertEquals(PASS, KeyLogic.decide(down, dn, 0, true, true, false, false));
        assertEquals(PASS, KeyLogic.decide(up, KeyEvent.ACTION_UP, 0, true, false, true, false));
    }

    @Test public void lockScreenAndCallsPassThrough() {
        assertEquals(PASS, KeyLogic.decide(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN, 0, true, true, true, true));
        assertEquals(PASS, KeyLogic.decide(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_UP, 0, true, true, true, true));
    }
}
