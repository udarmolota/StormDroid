package com.stormdroid;

import android.widget.ArrayAdapter;
import android.widget.Spinner;

import androidx.annotation.Nullable;

import com.stormdroid.game.GameInstance;

import java.util.ArrayList;
import java.util.List;

/**
 * The instance picker of the Mods, Import / export and Steam Cloud screens. With one instance it is
 * selected already; with several the picker starts on a prompt, so the player chooses where saves or
 * mods go instead of acting on whichever instance happens to come first.
 */
public final class InstanceSpinner {
    private InstanceSpinner() {}

    /**
     * Fills the spinner. {@code emptyText} is shown when there is no instance at all. Returns the
     * spinner position of the first instance (1 when a prompt comes first, else 0), for
     * {@link #selected}.
     */
    public static int fill(Spinner spinner, List<GameInstance> instances, String emptyText) {
        List<String> names = new ArrayList<>();
        int offset = 0;
        if (instances.isEmpty()) {
            names.add(emptyText);
        } else if (instances.size() > 1) {
            names.add(spinner.getContext().getString(R.string.choose_instance_prompt));
            offset = 1;
        }
        for (GameInstance gi : instances) names.add(gi.getName());
        ArrayAdapter<String> a = new ArrayAdapter<>(spinner.getContext(), android.R.layout.simple_spinner_item, names);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(a);
        return offset;
    }

    /** The chosen instance, or null while the prompt (or "no instances") is shown. */
    @Nullable
    public static GameInstance selected(@Nullable Spinner spinner, List<GameInstance> instances, int offset) {
        int i = spinner == null ? -1 : spinner.getSelectedItemPosition() - offset;
        return (i >= 0 && i < instances.size()) ? instances.get(i) : null;
    }
}
