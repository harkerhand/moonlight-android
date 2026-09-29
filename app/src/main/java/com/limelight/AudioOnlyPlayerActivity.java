package com.limelight;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.limelight.nvstream.StreamConfiguration;
import com.limelight.utils.UiHelper;

public class AudioOnlyPlayerActivity extends Activity implements AudioOnlyStreamService.StateListener {
    public static final String ACTION_OPEN_PLAYER = "com.limelight.action.OPEN_AUDIO_ONLY_PLAYER";

    private static final int NOTIFICATION_PERMISSION_REQUEST = 1;

    private TextView appNameView;
    private TextView pcNameView;
    private TextView statusView;
    private Button stopButton;

    private AudioOnlyStreamService.LocalBinder serviceBinder;
    private boolean serviceBound;
    private boolean serviceBinding;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            serviceBinder = (AudioOnlyStreamService.LocalBinder) service;
            serviceBound = true;
            serviceBinder.addStateListener(AudioOnlyPlayerActivity.this);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            serviceBinder = null;
            showState(AudioOnlyStreamService.STATE_STOPPED, null, null,
                    getString(R.string.audio_only_stopped));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        UiHelper.setLocale(this);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        setContentView(R.layout.activity_audio_only_player);

        appNameView = findViewById(R.id.audioOnlyAppName);
        pcNameView = findViewById(R.id.audioOnlyPcName);
        statusView = findViewById(R.id.audioOnlyStatus);
        stopButton = findViewById(R.id.audioOnlyStopButton);
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopAudioStream();
            }
        });

        showLaunchDetails(getIntent());
        startAudioStreamIfRequested(getIntent());
        requestNotificationPermission();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        showLaunchDetails(intent);
        startAudioStreamIfRequested(intent);
    }

    private void showLaunchDetails(Intent intent) {
        boolean startsStream = intent.hasExtra(Game.EXTRA_APP_ID) &&
                intent.getIntExtra(Game.EXTRA_APP_ID,
                        StreamConfiguration.INVALID_APP_ID) != StreamConfiguration.INVALID_APP_ID;
        String launchAppName = intent.getStringExtra(Game.EXTRA_APP_NAME);
        String launchPcName = intent.getStringExtra(Game.EXTRA_PC_NAME);
        if (launchAppName != null) {
            appNameView.setText(launchAppName);
        }
        else if (appNameView.getText().length() == 0) {
            appNameView.setText(R.string.audio_only_player_title);
        }
        if (launchPcName != null) {
            pcNameView.setText(getString(R.string.audio_only_host, launchPcName));
        }
        if (startsStream || statusView.getText().length() == 0) {
            statusView.setText(R.string.audio_only_connecting);
        }
    }

    private void startAudioStreamIfRequested(Intent sourceIntent) {
        if (!sourceIntent.hasExtra(Game.EXTRA_APP_ID) ||
                sourceIntent.getIntExtra(Game.EXTRA_APP_ID,
                        StreamConfiguration.INVALID_APP_ID) == StreamConfiguration.INVALID_APP_ID) {
            // Notification launches intentionally contain no stream parameters, so opening the
            // player never starts a duplicate connection.
            return;
        }

        Intent serviceIntent = new Intent(this, AudioOnlyStreamService.class)
                .setAction(AudioOnlyStreamService.ACTION_START);
        if (sourceIntent.getExtras() != null) {
            serviceIntent.putExtras(sourceIntent.getExtras());
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        }
        else {
            startService(serviceIntent);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // Playback has already been requested. Denying this permission must not block it.
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS },
                    NOTIFICATION_PERMISSION_REQUEST);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        serviceBinding = bindService(new Intent(this, AudioOnlyStreamService.class),
                serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        if (serviceBinding) {
            if (serviceBound) {
                serviceBinder.removeStateListener(this);
            }
            unbindService(serviceConnection);
            serviceBinding = false;
            serviceBound = false;
            serviceBinder = null;
        }
        super.onStop();
    }

    private void stopAudioStream() {
        statusView.setText(R.string.audio_only_stopping);
        stopButton.setEnabled(false);
        startService(new Intent(this, AudioOnlyStreamService.class)
                .setAction(AudioOnlyStreamService.ACTION_STOP));
        finish();
    }

    @Override
    public void onStateChanged(int state, String appName, String pcName, String detail) {
        showState(state, appName, pcName, detail);
    }

    private void showState(int state, String appName, String pcName, String detail) {
        if (appName != null) {
            appNameView.setText(appName);
        }
        if (pcName != null) {
            pcNameView.setText(getString(R.string.audio_only_host, pcName));
        }

        switch (state) {
            case AudioOnlyStreamService.STATE_CONNECTING:
                statusView.setText(detail != null ? detail : getString(R.string.audio_only_connecting));
                break;
            case AudioOnlyStreamService.STATE_PLAYING:
                statusView.setText(detail != null ? detail : getString(R.string.audio_only_playing));
                break;
            case AudioOnlyStreamService.STATE_STOPPING:
                statusView.setText(R.string.audio_only_stopping);
                break;
            case AudioOnlyStreamService.STATE_ERROR:
                statusView.setText(getString(R.string.audio_only_error,
                        detail != null ? detail : getString(R.string.conn_error_msg)));
                break;
            case AudioOnlyStreamService.STATE_STOPPED:
            default:
                statusView.setText(R.string.audio_only_stopped);
                break;
        }

        stopButton.setEnabled(state != AudioOnlyStreamService.STATE_STOPPED &&
                state != AudioOnlyStreamService.STATE_STOPPING);
    }
}
