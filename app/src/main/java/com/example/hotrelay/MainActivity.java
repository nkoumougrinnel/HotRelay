package com.example.hotrelay;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.example.hotrelay.session.SessionManager;
import com.example.hotrelay.ui.devices.DevicesFragment;
import com.example.hotrelay.ui.home.HomeFragment;
import com.example.hotrelay.ui.settings.SettingsFragment;
import com.example.hotrelay.ui.transfers.TransfersFragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    /** Onglet à ouvrir (ID du menu), ex. après avoir rejoint une session. */
    public static final String EXTRA_TAB = "tab";

    private BottomNavigationView bottomNav;

    /** Android 13+ : demander l'autorisation d'afficher les notifications de fin de transfert. */
    private final ActivityResultLauncher<String> askNotifications =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {});

    /** Prévient l'utilisateur quand la session se termine sans qu'il l'ait demandé. */
    private final SessionManager.Listener sessionEnded = new SessionManager.Listener() {
        @Override
        public void onSessionChanged(SessionManager session) {}

        @Override
        public void onSessionEnded(String reason) {
            Toast.makeText(MainActivity.this, getString(R.string.session_ended, reason), Toast.LENGTH_LONG).show();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.enableEdgeToEdge(getWindow());
        setContentView(R.layout.activity_main);

        bottomNav = findViewById(R.id.bottom_navigation);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0);

            if (bottomNav != null) {
                bottomNav.setPadding(0, 0, 0, systemBars.bottom);
            }
            return insets;
        });

        if (savedInstanceState == null) {
            loadFragment(new HomeFragment());
        }

        bottomNav.setOnItemSelectedListener(item -> {
            Fragment selectedFragment = null;
            int itemId = item.getItemId();

            if (itemId == R.id.navigation_home) {
                selectedFragment = new HomeFragment();
            } else if (itemId == R.id.navigation_transfers) {
                selectedFragment = new TransfersFragment();
            } else if (itemId == R.id.navigation_devices) {
                selectedFragment = new DevicesFragment();
            } else if (itemId == R.id.navigation_settings) {
                selectedFragment = new SettingsFragment();
            }

            if (selectedFragment != null) {
                loadFragment(selectedFragment);
                return true;
            }
            return false;
        });

        openRequestedTab(getIntent());

        if (savedInstanceState == null && Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        openRequestedTab(intent);
    }

    private void openRequestedTab(Intent intent) {
        int tab = intent == null ? 0 : intent.getIntExtra(EXTRA_TAB, 0);
        if (tab != 0) {
            bottomNav.setSelectedItemId(tab);
            intent.removeExtra(EXTRA_TAB);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        SessionManager.get(this).addListener(sessionEnded);
    }

    @Override
    protected void onStop() {
        SessionManager.get(this).removeListener(sessionEnded);
        super.onStop();
    }

    private void loadFragment(Fragment fragment) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();
    }
}