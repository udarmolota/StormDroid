package com.stormdroid.game;

import java.io.File;

/**
 * Against the Storm: the Windows build of the game (Unity 2021.3.45f2, Mono) on Unity 2021.3's Android
 * player (the :unity module), with the game's shaders retargeted to Vulkan on the phone (ShaderPrep).
 * An instance is the game folder as a PC install has it: "Against the Storm.exe" and
 * "Against the Storm_Data" at its root.
 */
public final class AtsGame {
    /** The game's data folder inside an instance. */
    public static final String DATA_DIR = "Against the Storm_Data";
    /** The game's executable: the install marker (only its presence counts, it never runs). */
    public static final String EXE = "Against the Storm.exe";
    /** persistentDataPath inside the instance, as the Windows player's LocalLow/<company>/<product>. */
    public static final String USER_DATA = "unity3d/Eremite Games/Against the Storm";
    /** Unity RuntimePlatform.WindowsPlayer: what the game's managed code expects from Application.platform. */
    public static final int RUNTIME_PLATFORM_WINDOWS_PLAYER = 2;

    private AtsGame() {}

    /** Whether the folder holds Against the Storm's Windows build. */
    public static boolean isAts(File gameDir) {
        return gameDir != null && new File(new File(gameDir, DATA_DIR), "globalgamemanagers").isFile();
    }

    public static boolean isAts(GameInstance instance) {
        return instance != null && isAts(new File(instance.getGamePath()));
    }
}
