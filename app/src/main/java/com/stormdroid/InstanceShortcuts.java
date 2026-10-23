package com.stormdroid;

import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import com.stormdroid.game.GameInstance;

import java.util.ArrayList;
import java.util.List;

/**
 * Home-screen shortcuts that start an instance straight away, as emulators have: a pinned one per
 * instance from the card's ⋮ menu, and the instances as the app icon's long-press actions. The
 * shortcut carries the launcher's own icon and the instance's name; LauncherActivity reads the
 * intent and LauncherFragment presses Play.
 */
public final class InstanceShortcuts {
    private static final int MAX_DYNAMIC = 4;

    private InstanceShortcuts() {}

    private static Intent launchIntent(Context context, GameInstance gi) {
        Intent i = new Intent(context, LauncherActivity.class);
        i.setAction(LauncherActivity.ACTION_LAUNCH_INSTANCE);
        i.putExtra(LauncherActivity.EXTRA_LAUNCH_INSTANCE, gi.getName());
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return i;
    }

    /** The game process (the Unity player's ":unity" process) is alive. */
    public static boolean isGameRunning(Context context) {
        android.app.ActivityManager am = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        List<android.app.ActivityManager.RunningAppProcessInfo> procs = am != null ? am.getRunningAppProcesses() : null;
        if (procs != null)
            for (android.app.ActivityManager.RunningAppProcessInfo p : procs)
                if (p.processName != null && p.processName.endsWith(":unity")) return true;
        return false;
    }

    /**
     * A game already running comes back to the front instead of starting again: its ":unity" process
     * runs a singleTask activity, so starting it again brings it forward. Returns false when no game
     * is running.
     */
    public static boolean bringRunningGameToFront(Context context) {
        android.app.ActivityManager am = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        List<android.app.ActivityManager.RunningAppProcessInfo> procs = am != null ? am.getRunningAppProcesses() : null;
        if (procs != null) {
            for (android.app.ActivityManager.RunningAppProcessInfo p : procs) {
                if (p.processName != null && p.processName.endsWith(":unity")) {
                    Intent i = new Intent();
                    i.setClassName(context.getPackageName(), "com.stormdroid.nativeunity.NativeUnityActivity");
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        context.startActivity(i);
                        return true;
                    } catch (RuntimeException e) {
                        return false;
                    }
                }
            }
        }
        return false;
    }

    private static ShortcutInfoCompat shortcut(Context context, GameInstance gi) {
        return new ShortcutInfoCompat.Builder(context, "instance:" + gi.getName())
                .setShortLabel(gi.getName())
                .setLongLabel(context.getString(R.string.instance_shortcut_long, gi.getName()))
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(launchIntent(context, gi))
                .build();
    }

    /** Asks the home screen to pin a shortcut for the instance (the system confirms with the player). */
    public static void pin(Context context, GameInstance gi) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            Toast.makeText(context, R.string.instance_shortcut_unsupported, Toast.LENGTH_SHORT).show();
            return;
        }
        ShortcutManagerCompat.requestPinShortcut(context, shortcut(context, gi), null);
    }

    /** The app icon's long-press actions: the instances, the first few. Never throws. */
    public static void publish(Context context, List<GameInstance> instances) {
        try {
            List<ShortcutInfoCompat> list = new ArrayList<>();
            for (GameInstance gi : instances) {
                if (list.size() >= MAX_DYNAMIC) break;
                list.add(shortcut(context, gi));
            }
            ShortcutManagerCompat.setDynamicShortcuts(context, list);
        } catch (RuntimeException e) {
            android.util.Log.w("StormDroid/Shortcuts", "dynamic shortcuts: " + e);
        }
    }
}
