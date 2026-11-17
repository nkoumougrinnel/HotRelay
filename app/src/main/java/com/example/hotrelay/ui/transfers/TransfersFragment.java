package com.example.hotrelay.ui.transfers;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.hotrelay.R;

public class TransfersFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_transfers, container, false);

        View btnCancelSend = root.findViewById(R.id.btnCancelSend);
        if (btnCancelSend != null) {
            btnCancelSend.setOnClickListener(v -> {
                // UI Action for cancel button
            });
        }

        return root;
    }
}