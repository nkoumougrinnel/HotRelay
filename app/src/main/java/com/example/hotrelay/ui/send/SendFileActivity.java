package com.example.hotrelay.ui.send;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.hotrelay.R;

public class SendFileActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_send_file);

        ImageView btnBackSend = findViewById(R.id.btnBackSend);
        View btnSendViaHost = findViewById(R.id.btnSendViaHost);

        if (btnBackSend != null) {
            btnBackSend.setOnClickListener(v -> finish());
        }

        if (btnSendViaHost != null) {
            btnSendViaHost.setOnClickListener(v -> finish());
        }
    }
}