package com.halo.decomp;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.widget.Toast;

import org.libsdl.app.HaloRelativeMouse;
import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLSurface;

/**
 * SDL's surface, plus a laptop-style trackpad (a keyboard case's, say) while
 * the game holds the pointer (SDL's relative mouse mode, which Android does
 * with pointer capture).
 *
 * Android hands a captured trackpad over as raw finger positions
 * (InputDevice.SOURCE_TOUCHPAD), which SDL leaves alone. This turns them
 * into the relative mouse that SDL already gives the game:
 * - one finger moving: relative motion, in thousandths of the pad's width
 *   (port/linux/src/xinput_sdl.c turns it into the right stick, or into
 *   direct aim, by input.trackpad_mode);
 * - any finger on the pad: the X2 button held, so the game knows when the
 *   last finger lifts (the virtual stick recentres then);
 * - the pad clicked: the left button (fire), or the right button (grenade)
 *   while two fingers are on it;
 * - a tap: a short left click; a tap with two fingers: a short right click;
 *   a tap, then a touch held: the left button held until that finger lifts
 *   (automatic weapons), still aiming as it moves;
 * - two fingers moving up or down: the wheel (switch weapons).
 * A held mouse goes to SDL here too (see "the mouse" below), since SDL drops
 * most of what Android sends for one; the game tells the two apart by the X2
 * button, held only while a finger is on the trackpad.
 */
public class TrackpadSurface extends SDLSurface {
    /* SDL's mouse actions (SDL_androidmouse.c) */
    private static final int ACTION_DOWN = 0;
    private static final int ACTION_UP = 1;
    private static final int ACTION_MOVE = 2;
    private static final int ACTION_SCROLL = 8;

    /** motion units per pad width */
    private static final float MOTION_UNITS = 1000.0f;
    /** a tap: down and up within this long, moving no further than this
    (a fraction of the pad's width) */
    private static final long TAP_MS = 200;
    private static final float TAP_TRAVEL = 0.03f;
    /** how long a tap's click is held */
    private static final long TAP_CLICK_MS = 90;
    /** a touch this soon after a tap holds the tap's click */
    private static final long TAP_HOLD_WINDOW_MS = 250;
    /** two fingers' travel (a fraction of the pad's height) per wheel notch */
    private static final float SCROLL_STEP = 0.06f;

    private static final int FINGER = MotionEvent.BUTTON_BACK; /* SDL's X2 */
    private static final int FIRE = MotionEvent.BUTTON_PRIMARY;
    private static final int GRENADE = MotionEvent.BUTTON_SECONDARY;

    private final Handler handler = new Handler(Looper.getMainLooper());

    /* the pad's size, in its own units */
    private int padDevice = -1;
    private float padWidth = 1.0f, padHeight = 1.0f;

    /* the buttons SDL last heard of, and those the trackpad and the mouse
    hold (SDL hears the two together) */
    private int sentButtons;
    private int padButtons;
    private int mouseButtons;

    /* the touch: where the tracked finger(s) last were */
    private boolean haveBaseline;
    private int baselinePointerId;
    private float baselineX, baselineY;
    private float scrollAccumulated;

    /* the touch, for taps */
    private long touchDownTime;
    private float touchTravel;
    private int touchMostFingers;
    private boolean touchClicked;

    /* the pad's own click */
    private boolean padPressed;
    private int padClickButton;

    /* a tap's click, released by tapRelease unless a touch holds it */
    private boolean tapReleasePending;
    private long tapUpTime;
    private boolean tapHeld;
    private final Runnable tapRelease = () -> {
        tapReleasePending = false;
        setButton(FIRE, false);
        setButton(GRENADE, false);
    };

    public TrackpadSurface(Context context) {
        super(context);
    }

    /* ---------- holding the pointer

    SDL asks Android for pointer capture once, when the game turns the
    relative mouse mode on, and Android ignores the request unless the window
    has focus right then; nothing asks again, so the pointer can stay free all
    the while: it shows, and the view stops turning at the screen's edge.
    While SDL wants the pointer held and the window has focus, this asks
    again until Android grants it; once, after a few seconds without it, it
    says so. */

    private static final long CAPTURE_CHECK_MS = 300;
    private static final long CAPTURE_WARN_MS = 4000;
    private long captureMissingSince;
    private boolean captureWarned;
    private boolean captureChecking;

    private final Runnable captureCheck = new Runnable() {
        @Override
        public void run() {
            if (!captureChecking)
                return;
            if (hasWindowFocus() && HaloRelativeMouse.wanted() && !hasPointerCapture()) {
                long now = SystemClock.uptimeMillis();

                if (captureMissingSince == 0) {
                    captureMissingSince = now;
                } else if (!captureWarned && now - captureMissingSince >= CAPTURE_WARN_MS) {
                    captureWarned = true;
                    Toast.makeText(getContext(), "Halo could not take hold of the mouse pointer: "
                        + "turning stops at the screen's edge.", Toast.LENGTH_LONG).show();
                }
                requestPointerCapture();
            } else {
                captureMissingSince = 0;
            }
            handler.postDelayed(this, CAPTURE_CHECK_MS);
        }
    };

    private void checkCaptureSoon() {
        if (!captureChecking)
            return;
        handler.removeCallbacks(captureCheck);
        handler.postDelayed(captureCheck, 50);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        captureChecking = true;
        checkCaptureSoon();
    }

    @Override
    protected void onDetachedFromWindow() {
        captureChecking = false;
        handler.removeCallbacks(captureCheck);
        super.onDetachedFromWindow();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus)
            checkCaptureSoon();
    }

    /* ---------- the trackpad */

    @Override
    public boolean onCapturedPointerEvent(MotionEvent event) {
        if (event.isFromSource(InputDevice.SOURCE_TOUCHPAD)) {
            trackpad(event);
            return true;
        }
        if (event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)) {
            mouse(event);
            return true;
        }
        return super.onCapturedPointerEvent(event);
    }

    @Override
    public void onPointerCaptureChange(boolean hasCapture) {
        super.onPointerCaptureChange(hasCapture);
        if (!hasCapture) {
            releaseAll();
            checkCaptureSoon();
        }
    }

    private void trackpad(MotionEvent event) {
        int action = event.getActionMasked();
        int fingers = event.getPointerCount();

        measurePad(event);
        switch (action) {
        case MotionEvent.ACTION_DOWN:
            touchDownTime = event.getEventTime();
            touchTravel = 0.0f;
            touchMostFingers = 1;
            touchClicked = false;
            scrollAccumulated = 0.0f;
            setBaseline(event);
            if (tapReleasePending && event.getEventTime() - tapUpTime <= TAP_HOLD_WINDOW_MS &&
                (padButtons & FIRE) != 0) {
                /* tap, then hold: the tap's click stays down */
                handler.removeCallbacks(tapRelease);
                tapReleasePending = false;
                tapHeld = true;
            }
            setButton(FINGER, true);
            break;
        case MotionEvent.ACTION_POINTER_DOWN:
        case MotionEvent.ACTION_POINTER_UP:
            touchMostFingers = Math.max(touchMostFingers, fingers);
            haveBaseline = false;
            scrollAccumulated = 0.0f;
            break;
        case MotionEvent.ACTION_MOVE:
            move(event, fingers);
            break;
        default:
            break;
        }

        padClick(event, fingers);

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            setButton(FINGER, false);
            haveBaseline = false;
            if (tapHeld) {
                tapHeld = false;
                setButton(FIRE, false);
            } else if (action == MotionEvent.ACTION_UP && !touchClicked &&
                event.getEventTime() - touchDownTime <= TAP_MS && touchTravel <= TAP_TRAVEL) {
                tap(event.getEventTime(), touchMostFingers >= 2 ? GRENADE : FIRE);
            }
        }
    }

    private void move(MotionEvent event, int fingers) {
        if (!haveBaseline || event.findPointerIndex(baselinePointerId) < 0) {
            setBaseline(event);
            return;
        }
        if (fingers == 1) {
            float x = event.getX(0), y = event.getY(0);
            /* both axes in the pad's width, so a circle stays round */
            float dx = (x - baselineX) / padWidth;
            float dy = (y - baselineY) / padWidth;

            baselineX = x;
            baselineY = y;
            touchTravel += Math.abs(dx) + Math.abs(dy);
            if (dx != 0.0f || dy != 0.0f)
                SDLActivity.onNativeMouse(sentButtons, ACTION_MOVE, dx * MOTION_UNITS, dy * MOTION_UNITS, true);
        } else {
            float y = averageY(event);
            float dy = (y - baselineY) / padHeight;

            baselineY = y;
            touchTravel += Math.abs(dy);
            scrollAccumulated += dy;
            while (Math.abs(scrollAccumulated) >= SCROLL_STEP) {
                float notch = scrollAccumulated > 0.0f ? -1.0f : 1.0f;

                scrollAccumulated += notch * SCROLL_STEP;
                SDLActivity.onNativeMouse(0, ACTION_SCROLL, 0.0f, notch, false);
            }
        }
    }

    private void padClick(MotionEvent event, int fingers) {
        boolean pressed = (event.getButtonState() &
            (MotionEvent.BUTTON_PRIMARY | MotionEvent.BUTTON_SECONDARY)) != 0;

        if (pressed && !padPressed) {
            boolean secondary = fingers >= 2 ||
                (event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0;

            padPressed = true;
            touchClicked = true;
            padClickButton = secondary ? GRENADE : FIRE;
            setButton(padClickButton, true);
        } else if (!pressed && padPressed) {
            padPressed = false;
            if (!tapHeld || padClickButton != FIRE)
                setButton(padClickButton, false);
        }
    }

    private void tap(long time, int button) {
        handler.removeCallbacks(tapRelease);
        setButton(button, true);
        tapUpTime = time;
        tapReleasePending = true;
        handler.postDelayed(tapRelease, TAP_CLICK_MS);
    }

    private void setBaseline(MotionEvent event) {
        baselinePointerId = event.getPointerId(0);
        baselineX = event.getX(0);
        baselineY = event.getPointerCount() > 1 ? averageY(event) : event.getY(0);
        haveBaseline = true;
    }

    private static float averageY(MotionEvent event) {
        float sum = 0.0f;
        int count = event.getPointerCount();

        for (int index = 0; index < count; index++)
            sum += event.getY(index);
        return sum / count;
    }

    private void measurePad(MotionEvent event) {
        InputDevice device = event.getDevice();

        if (device == null || device.getId() == padDevice)
            return;
        padDevice = device.getId();
        padWidth = range(device, MotionEvent.AXIS_X);
        padHeight = range(device, MotionEvent.AXIS_Y);
    }

    private static float range(InputDevice device, int axis) {
        InputDevice.MotionRange range = device.getMotionRange(axis, InputDevice.SOURCE_TOUCHPAD);

        if (range == null || range.getRange() <= 0.0f)
            return 1000.0f;
        return range.getRange();
    }

    /** presses or releases one of the trackpad's buttons */
    private void setButton(int button, boolean down) {
        if (down)
            padButtons |= button;
        else
            padButtons &= ~button;
        syncButtons();
    }

    /** tells SDL of every button whose state changed, one at a time: SDL
    works out which button an event is about from the change */
    private void syncButtons() {
        int wanted = padButtons | mouseButtons;

        for (int bit = 1; bit <= MotionEvent.BUTTON_FORWARD; bit <<= 1) {
            if ((wanted & bit) != 0 && (sentButtons & bit) == 0) {
                sentButtons |= bit;
                SDLActivity.onNativeMouse(sentButtons, ACTION_DOWN, 0.0f, 0.0f, true);
            } else if ((wanted & bit) == 0 && (sentButtons & bit) != 0) {
                sentButtons &= ~bit;
                SDLActivity.onNativeMouse(sentButtons, ACTION_UP, 0.0f, 0.0f, true);
            }
        }
    }

    private void releaseAll() {
        handler.removeCallbacks(tapRelease);
        tapReleasePending = false;
        tapHeld = false;
        padPressed = false;
        haveBaseline = false;
        padButtons = 0;
        mouseButtons = 0;
        syncButtons();
    }

    /* ---------- the mouse

    A held mouse comes as relative motion (ACTION_MOVE while a button is down,
    ACTION_HOVER_MOVE otherwise, often several moves batched in one event),
    ACTION_DOWN and ACTION_UP, ACTION_BUTTON_PRESS and _RELEASE, and
    ACTION_SCROLL. SDL 3.4 passes on only the last of a batch's hover moves
    and the scroll, so this passes on all of it: the motion as the relative
    mouse (the game aims with it directly, as on a computer: xinput_sdl.c),
    the buttons as the buttons, the side button as SDL's X1 (melee), and
    the wheel. */

    private void mouse(MotionEvent event) {
        switch (event.getActionMasked()) {
        case MotionEvent.ACTION_SCROLL:
            SDLActivity.onNativeMouse(0, ACTION_SCROLL, event.getAxisValue(MotionEvent.AXIS_HSCROLL),
                event.getAxisValue(MotionEvent.AXIS_VSCROLL), false);
            return;
        case MotionEvent.ACTION_MOVE:
        case MotionEvent.ACTION_HOVER_MOVE: {
            float dx = event.getX(0), dy = event.getY(0);
            int history = event.getHistorySize();

            for (int index = 0; index < history; index++) {
                dx += event.getHistoricalX(0, index);
                dy += event.getHistoricalY(0, index);
            }
            if (dx != 0.0f || dy != 0.0f)
                SDLActivity.onNativeMouse(sentButtons, ACTION_MOVE, dx, dy, true);
            break;
        }
        default:
            break;
        }

        int state = event.getButtonState();
        int buttons = state & (MotionEvent.BUTTON_PRIMARY | MotionEvent.BUTTON_SECONDARY |
            MotionEvent.BUTTON_TERTIARY);

        /* (the trackpad's finger is SDL's X2: both side buttons are X1) */
        if ((state & (MotionEvent.BUTTON_BACK | MotionEvent.BUTTON_FORWARD)) != 0)
            buttons |= MotionEvent.BUTTON_FORWARD;
        if (buttons != mouseButtons) {
            mouseButtons = buttons;
            syncButtons();
        }
    }
}
