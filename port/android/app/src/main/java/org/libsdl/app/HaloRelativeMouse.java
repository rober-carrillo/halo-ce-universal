package org.libsdl.app;

/**
 * Whether SDL has the relative mouse mode on (the game holds the pointer),
 * for com.halo.decomp.TrackpadSurface: SDL keeps its motion listener, which
 * knows, to its own package.
 */
public final class HaloRelativeMouse {
    private HaloRelativeMouse() {
    }

    public static boolean wanted() {
        SDLGenericMotionListener_API14 listener = SDLActivity.getMotionListener();

        return listener != null && listener.inRelativeMode();
    }
}
