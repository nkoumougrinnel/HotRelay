package com.example.hotrelay.ui.settings;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.hotrelay.R;
import com.example.hotrelay.session.SessionManager;

/** Paramètres : nom de l'appareil, visible par les autres participants. */
public class SettingsFragment extends Fragment {

    private EditText etDeviceName;
    private SessionManager session;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_settings, container, false);
        session = SessionManager.get(requireContext());

        etDeviceName = root.findViewById(R.id.etDeviceName);
        etDeviceName.setText(session.getDeviceName());
        etDeviceName.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                save(true);
            }
            return false;
        });
        return root;
    }

    @Override
    public void onPause() {
        save(false); // enregistre aussi quand on change d'onglet
        super.onPause();
    }

    private void save(boolean confirm) {
        String name = etDeviceName.getText().toString().trim();
        if (name.isEmpty() || name.equals(session.getDeviceName())) return;
        session.setDeviceName(name);
        if (confirm) Toast.makeText(requireContext(), R.string.device_name_saved, Toast.LENGTH_SHORT).show();
    }
}
