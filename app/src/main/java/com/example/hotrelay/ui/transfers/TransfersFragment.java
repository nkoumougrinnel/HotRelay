package com.example.hotrelay.ui.transfers;

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
import com.example.hotrelay.model.TransferItem;
import com.example.hotrelay.model.TransferStatus;
import com.example.hotrelay.ui.adapter.TransferAdapter;

import java.util.ArrayList;
import java.util.List;

public class TransfersFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_transfers, container, false);

        RecyclerView rvHistory = root.findViewById(R.id.rvTransferHistory);
        rvHistory.setLayoutManager(new LinearLayoutManager(requireContext()));

        TransferAdapter adapter = new TransferAdapter();
        rvHistory.setAdapter(adapter);

        // Mock data matching mockup screen 4
        List<TransferItem> history = new ArrayList<>();
        history.add(new TransferItem("t1", "Document.pdf", 4500000, "4.5 Mo", 100, TransferStatus.SUCCESS, "Moi", "Noah", false));
        history.add(new TransferItem("t2", "Vidéo-courte.mp4", 18000000, "18 Mo", 0, TransferStatus.FAILED, "Lina", "Moi", true));

        adapter.setTransfers(history);

        return root;
    }
}