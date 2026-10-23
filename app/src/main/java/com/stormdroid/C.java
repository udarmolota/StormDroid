package com.stormdroid;

public class C {

    public static final String STORAGE_PROVIDER_AUTHORITY = "com.stormdroid.STORAGE_PROVIDER_AUTHORITY";

    public static class mime {
        /** SAF filter for installing the game: ZIP only. octet-stream is included because some providers
         *  report zips as that. */
        public static final String[] GAME_ARCHIVE = {
            "application/zip", "application/x-zip-compressed", "application/octet-stream",
        };
    }

    public static class shprefs {
        public static final String NAME = "com.stormdroid.PREFS";
    }
}
