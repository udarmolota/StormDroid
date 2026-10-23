package com.stormdroid.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;

import com.stormdroid.LauncherPreferences;
import com.stormdroid.R;

/**
 * Global app settings: the theme (Light / Dark / Follow system). Per-instance settings live behind
 * each launcher card's gear.
 */
public class AppSettingsFragment extends Fragment {

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_app_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        LauncherPreferences prefs = LauncherPreferences.requireSingleton();
        RadioGroup rg = v.findViewById(R.id.rg_theme);

        int mode = prefs.getThemeMode();
        if (mode == AppCompatDelegate.MODE_NIGHT_NO) rg.check(R.id.rb_theme_light);
        else if (mode == AppCompatDelegate.MODE_NIGHT_YES) rg.check(R.id.rb_theme_dark);
        else rg.check(R.id.rb_theme_system);

        rg.setOnCheckedChangeListener((group, checkedId) -> {
            int m;
            if (checkedId == R.id.rb_theme_light) m = AppCompatDelegate.MODE_NIGHT_NO;
            else if (checkedId == R.id.rb_theme_dark) m = AppCompatDelegate.MODE_NIGHT_YES;
            else m = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            prefs.setThemeMode(m);
            AppCompatDelegate.setDefaultNightMode(m);   // recreates the activity to apply
        });
    }
}
