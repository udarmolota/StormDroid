package com.stormdroid.game;

import com.stormdroid.AppStorage;

import java.io.File;
import java.util.ArrayList;

public class GameInstance {

    private final String name;

    public GameInstance(String name) {
        this.name = name;
    }

    public String getName() { return name; }

    /** Per-instance launch settings (render size, frame rate, controls). */
    public com.stormdroid.InstanceSettings settings() {
        return new com.stormdroid.InstanceSettings(name);
    }

    public String getGamePath() {
        return AppStorage.requireSingleton().getInstanceDir(name).getAbsolutePath();
    }

    /** The game's persistentDataPath inside this instance: saves and settings. */
    public File getUserDataDir() {
        return new File(getGamePath(), AtsGame.USER_DATA);
    }

    public boolean isInstalled() {
        return AtsGame.isAts(this);
    }

    /**
     * Completeness check, separate from {@link #isInstalled()}: isInstalled() stays lenient so an
     * instance never vanishes from the launcher over a layout quirk, while this lists the required files
     * that are missing (empty = complete), to warn at install and block the launch.
     */
    public java.util.List<String> missingCoreFiles() {
        File root = new File(getGamePath());
        java.util.List<String> missing = new ArrayList<>();
        for (String rel : new String[] { AtsGame.DATA_DIR + "/globalgamemanagers",
                AtsGame.DATA_DIR + "/Managed/Assembly-CSharp.dll", AtsGame.DATA_DIR + "/ScriptingAssemblies.json",
                "MonoBleedingEdge/etc/mono/config" })
            if (!new File(root, rel).isFile()) missing.add(rel);
        return missing;
    }

    /** True if the game's files are complete enough to start (see {@link #missingCoreFiles}). */
    public boolean isComplete() { return missingCoreFiles().isEmpty(); }
}
