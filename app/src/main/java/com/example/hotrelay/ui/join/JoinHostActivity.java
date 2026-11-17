package com.example.hotrelay.ui.join;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.hotrelay.R;
import com.example.hotrelay.ui.send.SendFileActivity;

public class JoinHostActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_join_host);

        ImageView btnBack = findViewById(R.id.btnBack);
        View btnConnect = findViewById(R.id.btnConnect);
        EditText etHostIp = findViewById(R.id.etHostIp);
        EditText etPort = findViewById(R.id.etPort);
        EditText etUserName = findViewById(R.id.etUserName);

        if (etHostIp != null) {
            etHostIp.setText("192.168.43.1");
        }
        if (etPort != null) {
            etPort.setText("5000");
        }
        if (etUserName != null) {
            etUserName.setText("Paul");
        }

        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        if (btnConnect != null) {
            btnConnect.setOnClickListener(v -> {
                Intent intent = new Intent(this, SendFileActivity.class);
                startActivity(intent);
                finish();
            });
        }
    }
}