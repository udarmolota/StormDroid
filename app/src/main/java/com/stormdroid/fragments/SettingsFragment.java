package com.stormdroid.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.stormdroid.InstanceSettings;
import com.stormdroid.LauncherPreferences;
import com.stormdroid.R;
import com.stormdroid.game.GameInstance;
import com.stormdroid.game.GameInstanceManager;

import java.util.List;

/** One instance's launch settings: render size, texture quality, Vulkan, frame rate, controls. */
public class SettingsFragment extends Fragment {

    /** Pass the instance name via arguments so this page edits THAT instance's settings. */
    public static final String ARG_INSTANCE = "instance";

    private LauncherPreferences prefs;
    private InstanceSettings inst;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        prefs = LauncherPreferences.requireSingleton();

        // Which instance are we editing? Argument (from the launcher card's gear) → last launched →
        // first installed → a "default" profile (degenerate case when no instances exist yet).
        String instName = getArguments() != null ? getArguments().getString(ARG_INSTANCE) : null;
        if (instName == null || instName.isEmpty()) instName = prefs.getLastInstanceName();
        if (instName == null || instName.isEmpty()) {
            List<GameInstance> all = GameInstanceManager.requireSingleton().getInstances();
            if (!all.isEmpty()) instName = all.get(0).getName();
        }
        if (instName == null || instName.isEmpty()) instName = "default";
        inst = new InstanceSettings(instName);
        requireActivity().setTitle(getString(R.string.nav_settings) + " — " + instName);

        // Mirrored landscape: opt-in for USB-C gamepad cradles that hold the phone the other way up.
        Switch swReverse = view.findViewById(R.id.sw_reverse_landscape);
        swReverse.setChecked(inst.isReverseLandscape());
        swReverse.setOnCheckedChangeListener((btn, checked) -> inst.setReverseLandscape(checked));
        Switch swHaptic = view.findViewById(R.id.sw_haptic);
        swHaptic.setChecked(inst.isHapticFeedback());
        swHaptic.setOnCheckedChangeListener((btn, checked) -> inst.setHapticFeedback(checked));

        // Vulkan compatibility: Auto / On / Off, in the order of the VK_COMPAT_ values. Auto says what it
        // comes to on this phone.
        Spinner spVkCompat = view.findViewById(R.id.sp_vk_compat);
        ArrayAdapter<String> vkCompatAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, new String[]{
                        getString(com.stormdroid.game.NativeEngine.needsVulkanCompat()
                                ? R.string.vk_compat_auto_on : R.string.vk_compat_auto_off),
                        getString(R.string.vk_compat_on), getString(R.string.vk_compat_off) });
        vkCompatAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spVkCompat.setAdapter(vkCompatAdapter);
        spVkCompat.setSelection(inst.getVulkanCompat());
        spVkCompat.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                inst.setVulkanCompat(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // In-game overlay — GLOBAL: off / classic FPS counter / full performance bar. Takes effect next
        // game launch.
        android.widget.RadioGroup rgHud = view.findViewById(R.id.rg_hud);
        switch (prefs.getHudMode()) {
            case LauncherPreferences.HUD_FULL: rgHud.check(R.id.rb_hud_full); break;
            case LauncherPreferences.HUD_FPS:  rgHud.check(R.id.rb_hud_fps);  break;
            default:                           rgHud.check(R.id.rb_hud_off);  break;
        }
        rgHud.setOnCheckedChangeListener((group, checkedId) -> prefs.setHudMode(
                checkedId == R.id.rb_hud_full ? LauncherPreferences.HUD_FULL
              : checkedId == R.id.rb_hud_fps  ? LauncherPreferences.HUD_FPS
              : LauncherPreferences.HUD_OFF));

        // Advanced: per-instance extra env vars (KEY=VALUE, space-separated), applied last in
        // NativeEngine so they override the defaults (diagnostics, Unity arguments).
        final android.widget.EditText etEnv = view.findViewById(R.id.et_env_vars);
        String envCur = inst.getEnvVars();
        etEnv.setText(envCur != null ? envCur : "");
        etEnv.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                inst.setEnvVars(s.toString().replace('\n', ' '));   // newlines → spaces (KEY=VALUE list)
            }
        });
        // The whole Advanced card is collapsed by default. Tap the header to expand/collapse.
        final TextView advHeader = view.findViewById(R.id.tv_advanced_header);
        final View advContent = view.findViewById(R.id.ll_advanced_content);
        advHeader.setOnClickListener(v -> {
            boolean show = advContent.getVisibility() != View.VISIBLE;
            advContent.setVisibility(show ? View.VISIBLE : View.GONE);
            advHeader.setCompoundDrawablesWithIntrinsicBounds(0, 0,
                    show ? android.R.drawable.arrow_up_float : android.R.drawable.arrow_down_float, 0);
        });

        // --- Render resolution: per-device presets from a ~720p floor up to native, plus the
        // letterboxed 1366x768. Lower = more FPS on weak GPUs; native is sharpest. The game's interface
        // keeps its share of the screen whatever the resolution (its own UI size option scales it).
        // Applied at the next launch.
        android.widget.RadioGroup rgRes = view.findViewById(R.id.rg_render_res);
        android.graphics.Rect bounds =
                requireActivity().getWindowManager().getCurrentWindowMetrics().getBounds();
        final int sLong  = Math.max(bounds.width(), bounds.height());   // landscape width
        final int sShort = Math.min(bounds.width(), bounds.height());   // landscape height
        final int MIN = LauncherPreferences.minRenderScalePercent(sLong, sShort);
        final int MID = Math.max(MIN + 1, Math.min(99, (MIN + 100) / 2));
        java.util.LinkedHashSet<Integer> pctSet = new java.util.LinkedHashSet<>();
        pctSet.add(MIN); pctSet.add(MID); pctSet.add(100);
        final java.util.List<Integer> pcts = new java.util.ArrayList<>(pctSet);
        java.util.Collections.sort(pcts);   // ascending; displayed native (high) → floor (low)

        final int curPct = Math.max(MIN, inst.getRenderScalePercent());
        final boolean curFixed = inst.getFixedResMode() != InstanceSettings.FIXED_NONE;
        int nearestPct = pcts.get(0), nearestD = Integer.MAX_VALUE;   // preset closest to the stored %
        for (int p : pcts) { int d = Math.abs(p - curPct); if (d < nearestD) { nearestD = d; nearestPct = p; } }

        final int FIXED_TAG = -1;   // sentinel tag = the fixed 1366x768 letterboxed radio (not a percent)
        for (int i = pcts.size() - 1; i >= 0; i--) {   // native first, down to the floor
            int p = pcts.get(i);
            int W = Math.round(sLong * p / 100f), H = Math.round(sShort * p / 100f);
            android.widget.RadioButton rb = new android.widget.RadioButton(requireContext());
            rb.setId(View.generateViewId());
            rb.setText(W + "×" + H + " (" + getString(R.string.render_res_fullscreen) + ")");
            rb.setTag(p);
            rgRes.addView(rb);
            if (!curFixed && p == nearestPct) rb.setChecked(true);
        }
        android.widget.RadioButton rbFixed = new android.widget.RadioButton(requireContext());
        rbFixed.setId(View.generateViewId());
        rbFixed.setText("1366×768 (" + getString(R.string.render_res_black_margins) + ")");
        rbFixed.setTag(FIXED_TAG);
        rgRes.addView(rbFixed);
        if (curFixed) rbFixed.setChecked(true);

        rgRes.setOnCheckedChangeListener((group, checkedId) -> {   // set after the initial checks → no spurious writes
            View rb = group.findViewById(checkedId);
            if (rb == null || rb.getTag() == null) return;
            int tag = (Integer) rb.getTag();
            if (tag == FIXED_TAG) {
                inst.setFixedResMode(InstanceSettings.FIXED_720_16_9);
            } else {
                inst.setFixedResMode(InstanceSettings.FIXED_NONE);
                inst.setRenderScalePercent(tag);
            }
        });

        // Texture quality: No / Low / Ultra low (Unity's texture mipmap limit, see NativeEngine).
        // Takes effect on the next launch.
        android.widget.RadioGroup rgTexq = view.findViewById(R.id.rg_texq);
        switch (inst.getTexTier()) {
            case InstanceSettings.TEX_ULTRA: rgTexq.check(R.id.rb_texq_ultra); break;
            case InstanceSettings.TEX_LOW:   rgTexq.check(R.id.rb_texq_low);   break;
            default:                         rgTexq.check(R.id.rb_texq_no);    break;
        }
        rgTexq.setOnCheckedChangeListener((group, checkedId) -> {
            int tier = (checkedId == R.id.rb_texq_ultra) ? InstanceSettings.TEX_ULTRA
                     : (checkedId == R.id.rb_texq_low)   ? InstanceSettings.TEX_LOW
                     : InstanceSettings.TEX_NONE;
            inst.setTexTier(tier);
        });

        // FPS cap: three radio buttons — 30 / 60 / No limit (0 = off). Takes effect on next launch.
        android.widget.RadioGroup rgFps = view.findViewById(R.id.rg_fps_cap);
        int curCap = inst.getFpsCap();
        if (curCap == 30)      rgFps.check(R.id.rb_fps_30);
        else if (curCap == 60) rgFps.check(R.id.rb_fps_60);
        else                   rgFps.check(R.id.rb_fps_off);
        rgFps.setOnCheckedChangeListener((group, checkedId) -> {
            int cap = (checkedId == R.id.rb_fps_30) ? 30
                    : (checkedId == R.id.rb_fps_60) ? 60 : 0;
            inst.setFpsCap(cap);
        });
    }
}
