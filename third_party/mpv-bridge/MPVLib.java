/* SPDX-License-Identifier: GPL-3.0-or-later
 * JNI ABI for unmodified mpv-android fdf74f6830c47dbaa8a22ac79726e8303f1db5af.
 * Compiled into a separate DEX so its FFmpeg stays in an isolated native namespace.
 */
package is.xyz.mpv;

import android.content.Context;
import android.view.Surface;

public final class MPVLib {
    static { System.loadLibrary("mpv"); System.loadLibrary("player"); }
    public static native void create(Context context);
    public static native void init();
    public static native void destroy();
    public static native void attachSurface(Surface surface);
    public static native void detachSurface();
    public static native void command(String[] args);
    public static native int setOptionString(String name, String value);
    public static native String getPropertyString(String name);
    public static native void setPropertyString(String name, String value);
    // Native callbacks are intentionally tiny; host polls state on its application looper.
    public static void eventProperty(String name) {}
    public static void eventProperty(String name, long value) {}
    public static void eventProperty(String name, double value) {}
    public static void eventProperty(String name, boolean value) {}
    public static void eventProperty(String name, String value) {}
    public static void event(int id) {}
    public static void logMessage(String prefix, int level, String text) {
        // Do not forward native filenames, URLs or server diagnostics to public logs.
    }
}
