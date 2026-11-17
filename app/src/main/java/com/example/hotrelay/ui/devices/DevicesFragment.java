package com.example.hotrelay.ui.devices;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.hotrelay.R;
import com.example.hotrelay.model.Participant;
import com.example.hotrelay.ui.adapter.ParticipantAdapter;

import java.util.ArrayList;
import java.util.List;

public class DevicesFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_devices, container, false);

        RecyclerView rvDevices = root.findViewById(R.id.rvDevices);
        rvDevices.setLayoutManager(new LinearLayoutManager(requireContext()));

        ParticipantAdapter adapter = new ParticipantAdapter();
        rvDevices.setAdapter(adapter);

        List<Participant> devices = new ArrayList<>();
        devices.add(new Participant("1", "Hôte / Relais Central", "192.168.43.1", 8888, true, true));
        devices.add(new Participant("2", "Maya", "192.168.43.10", 8888, true, false));
        devices.add(new Participant("3", "Noah", "192.168.43.11", 8888, true, false));
        devices.add(new Participant("4", "Lina", "192.168.43.12", 8888, true, false));

        adapter.setParticipants(devices);

        return root;
    }
}