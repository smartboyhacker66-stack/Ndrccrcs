package com.blindindiantech.tandem;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

public class MainActivity extends AppCompatActivity implements Session.StatusListener {
    private TextView tvStatus;
    private View panelController;
    private View panelSharer;
    private EditText etMeetingId;
    private Button btnConnectAnother;
    private Button btnAllowAccess;
    private Button btnGenerate;
    private Button btnConnect;
    private String pendingId;

    private final ActivityResultLauncher<Intent> projectionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null && pendingId != null) {
                    startSharing(pendingId, result.getData());
                } else {
                    Feedback.speak("Screen sharing permission was not granted.");
                    tvStatus.setText("Screen sharing permission was not granted.");
                }
            });

    private final ActivityResultLauncher<String> notificationLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> { });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Feedback.init(this);

        tvStatus = findViewById(R.id.tvStatus);
        panelController = findViewById(R.id.panelController);
        panelSharer = findViewById(R.id.panelSharer);
        etMeetingId = findViewById(R.id.etMeetingId);
        btnConnectAnother = findViewById(R.id.btnConnectAnother);
        btnAllowAccess = findViewById(R.id.btnAllowAccess);
        btnGenerate = findViewById(R.id.btnGenerate);
        btnConnect = findViewById(R.id.btnConnect);
        Button btnAccessibility = findViewById(R.id.btnAccessibility);
        Button btnDisconnect = findViewById(R.id.btnDisconnect);

        ViewCompat.setAccessibilityHeading(findViewById(R.id.tvTitle), true);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }

        btnConnectAnother.setOnClickListener(v -> {
            panelSharer.setVisibility(View.GONE);
            panelController.setVisibility(View.VISIBLE);
            focusOn(findViewById(R.id.tvControllerIntro));
        });

        btnAllowAccess.setOnClickListener(v -> {
            panelController.setVisibility(View.GONE);
            panelSharer.setVisibility(View.VISIBLE);
            focusOn(findViewById(R.id.tvConsent));
        });

        btnGenerate.setOnClickListener(v -> {
            if (Session.isActive()) {
                Feedback.speak("A connection is already active. Please press Disconnect Connection first.");
                return;
            }
            Intent i = new Intent(this, ControllerActivity.class);
            i.putExtra(ControllerActivity.EXTRA_ID, Meeting.generate());
            startActivity(i);
        });

        btnConnect.setOnClickListener(v -> onConnectPressed());
        etMeetingId.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                btnConnect.performClick();
                return true;
            }
            return false;
        });

        btnAccessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        btnDisconnect.setOnClickListener(v -> Session.disconnect());

        setActionsEnabled(ServerWaker.isReady());
        if (!ServerWaker.isReady()) {
            tvStatus.setText(R.string.status_setting_up);
            Feedback.startSetupLoop();
            ServerWaker.start(() -> runOnUiThread(() -> {
                Feedback.setupReady();
                if (!isDestroyed()) {
                    setActionsEnabled(true);
                    tvStatus.setText(R.string.status_ready);
                }
            }));
        } else {
            tvStatus.setText(R.string.status_ready);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Session.setListener(this);
    }

    @Override
    protected void onStop() {
        Session.clearListener(this);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ServerWaker.isReady()) setActionsEnabled(true);
    }

    @Override
    public void onStatus(String text, boolean connected) {
        if (ServerWaker.isReady()) tvStatus.setText(text);
    }

    private void setActionsEnabled(boolean enabled) {
        for (View v : new View[]{btnConnectAnother, btnAllowAccess, btnGenerate, btnConnect}) {
            v.setEnabled(enabled);
            v.setAlpha(enabled ? 1f : 0.5f);
        }
    }

    private void focusOn(final View v) {
        v.post(() -> v.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null));
    }

    private void onConnectPressed() {
        String id = etMeetingId.getText().toString().trim().toUpperCase();
        if (!Meeting.isValid(id)) {
            String msg = "Please enter a valid Meeting ID: 7 digits followed by 1 capital letter.";
            etMeetingId.setError(msg);
            Feedback.speak(msg);
            return;
        }
        if (Session.isActive()) {
            Feedback.speak("A connection is already active. Please press Disconnect Connection first.");
            return;
        }
        if (!RemoteAccessibilityService.isEnabled()) {
            Feedback.speak("To allow remote control, turn on BITS Tandem Centre in Accessibility settings, "
                    + "then come back and press Connect.");
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        pendingId = id;
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projectionLauncher.launch(mpm.createScreenCaptureIntent());
    }

    private void startSharing(String id, Intent projectionData) {
        Intent service = new Intent(this, ScreenShareService.class);
        service.putExtra(ScreenShareService.EXTRA_ID, id);
        service.putExtra(ScreenShareService.EXTRA_DATA, projectionData);
        ContextCompat.startForegroundService(this, service);
        tvStatus.setText("Connecting...");
    }
}
