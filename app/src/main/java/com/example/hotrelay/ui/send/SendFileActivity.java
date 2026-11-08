package com.example.hotrelay.ui.send;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.hotrelay.R;

public class SendFileActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_send_file);

        ImageView btnBackSend = findViewById(R.id.btnBackSend);
        View cardSelectRecipient = findViewById(R.id.cardSelectRecipient);
        View cardSelectFile = findViewById(R.id.cardSelectFile);
        View btnSendViaHost = findViewById(R.id.btnSendViaHost);
        TextView tvRecipientName = findViewById(R.id.tvRecipientName);
        TextView tvRouteRecipientName = findViewById(R.id.tvRouteRecipientName);

        btnBackSend.setOnClickListener(v -> finish());

        cardSelectRecipient.setOnClickListener(v -> {
            Toast.makeText(this, "Sélection du destinataire", Toast.LENGTH_SHORT).show();
        });

        cardSelectFile.setOnClickListener(v -> {
            Toast.makeText(this, "Sélection d'un fichier", Toast.LENGTH_SHORT).show();
        });

        btnSendViaHost.setOnClickListener(v -> {
            Toast.makeText(this, "Envoi du fichier via l'hôte...", Toast.LENGTH_LONG).show();
            finish();
        });
    }
}