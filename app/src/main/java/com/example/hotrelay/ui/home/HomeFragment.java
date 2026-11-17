package com.example.hotrelay.ui.home;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.hotrelay.R;
import com.example.hotrelay.ui.host.HostSessionActivity;
import com.example.hotrelay.ui.join.JoinHostActivity;

public class HomeFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_home, container, false);

        View btnCreateSession = root.findViewById(R.id.btnCreateSession);
        View btnJoinSession = root.findViewById(R.id.btnJoinSession);

        if (btnCreateSession != null) {
            btnCreateSession.setOnClickListener(v -> {
                Intent intent = new Intent(requireContext(), HostSessionActivity.class);
                startActivity(intent);
            });
        }

        if (btnJoinSession != null) {
            btnJoinSession.setOnClickListener(v -> {
                Intent intent = new Intent(requireContext(), JoinHostActivity.class);
                startActivity(intent);
            });
        }

        return root;
    }
}