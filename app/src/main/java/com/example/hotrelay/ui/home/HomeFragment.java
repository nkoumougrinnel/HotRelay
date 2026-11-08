package com.example.hotrelay.ui.home;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.hotrelay.R;
import com.example.hotrelay.ui.host.HostSessionActivity;
import com.example.hotrelay.ui.send.SendFileActivity;
import com.example.hotrelay.utils.NetworkUtils;

public class HomeFragment extends Fragment {

    @SuppressLint("ClickableViewAccessibility")
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_home, container, false);

        View cardLiveStatus = root.findViewById(R.id.cardLiveStatus);
        View layoutHero = root.findViewById(R.id.layoutHero);
        View layoutStats = root.findViewById(R.id.layoutStats);
        View btnCreateSession = root.findViewById(R.id.btnCreateSession);
        View btnJoinSession = root.findViewById(R.id.btnJoinSession);
        View cardSecurityBanner = root.findViewById(R.id.cardSecurityBanner);

        View vStatusPulse = root.findViewById(R.id.vStatusPulse);
        View vLogoAura = root.findViewById(R.id.vLogoAura);
        TextView tvIpAddress = root.findViewById(R.id.tvIpAddress);
        TextView tvNetworkStatusTitle = root.findViewById(R.id.tvNetworkStatusTitle);

        // 1. Dynamic local IP address detection
        String ipAddress = NetworkUtils.getLocalIpAddress();
        if (ipAddress != null && !ipAddress.isEmpty()) {
            tvIpAddress.setText("IP locale : " + ipAddress);
            tvNetworkStatusTitle.setText("Réseau local prêt");
        } else {
            tvIpAddress.setText("Recherche du point d'accès...");
            tvNetworkStatusTitle.setText("Attente du réseau");
        }

        // 2. Start continuous pulse animations
        Animation pulseAnim = AnimationUtils.loadAnimation(requireContext(), R.anim.pulse_glow);
        if (vStatusPulse != null) {
            vStatusPulse.startAnimation(pulseAnim);
        }
        if (vLogoAura != null) {
            vLogoAura.startAnimation(pulseAnim);
        }

        // 3. Sequential Staggered Entrance Animations
        animateEntrance(cardLiveStatus, 0);
        animateEntrance(layoutHero, 100);
        animateEntrance(layoutStats, 200);
        animateEntrance(btnCreateSession, 300);
        animateEntrance(btnJoinSession, 400);
        animateEntrance(cardSecurityBanner, 500);

        // 4. Interactive Spring Touch Feedback
        addTouchEffect(btnCreateSession);
        addTouchEffect(btnJoinSession);

        btnCreateSession.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), HostSessionActivity.class);
            startActivity(intent);
        });

        btnJoinSession.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), SendFileActivity.class);
            startActivity(intent);
        });

        return root;
    }

    private void animateEntrance(View view, long delay) {
        if (view == null) return;
        view.setAlpha(0f);
        view.setTranslationY(40f);
        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(450)
                .setStartDelay(delay)
                .start();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void addTouchEffect(View view) {
        if (view == null) return;
        view.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(100).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start();
                    break;
            }
            return false;
        });
    }
}