package com.halo.decomp;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.MotionEvent;

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
 * A mouse, and a trackpad that reports itself as a mouse, go on to SDL as
 * before.
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

    /* the buttons this has pressed, as SDL last heard */
    private int sentButtons;

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

    @Override
    public boolean onCapturedPointerEvent(MotionEvent event) {
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHPAD))
            return super.onCapturedPointerEvent(event);
        trackpad(event);
        return true;
    }

    @Override
    public void onPointerCaptureChange(boolean hasCapture) {
        super.onPointerCaptureChange(hasCapture);
        if (!hasCapture)
            releaseAll();
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
                (sentButtons & FIRE) != 0) {
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

    /** presses or releases one button; SDL works out which from the change */
    private void setButton(int button, boolean down) {
        if (down && (sentButtons & button) == 0) {
            sentButtons |= button;
            SDLActivity.onNativeMouse(sentButtons, ACTION_DOWN, 0.0f, 0.0f, true);
        } else if (!down && (sentButtons & button) != 0) {
            sentButtons &= ~button;
            SDLActivity.onNativeMouse(sentButtons, ACTION_UP, 0.0f, 0.0f, true);
        }
    }

    private void releaseAll() {
        handler.removeCallbacks(tapRelease);
        tapReleasePending = false;
        tapHeld = false;
        padPressed = false;
        haveBaseline = false;
        setButton(FIRE, false);
        setButton(GRENADE, false);
        setButton(FINGER, false);
    }
}
