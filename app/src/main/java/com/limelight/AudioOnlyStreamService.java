package com.limelight;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import com.limelight.binding.PlatformBinding;
import com.limelight.binding.audio.AndroidAudioRenderer;
import com.limelight.binding.video.NoOpVideoRenderer;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.NvConnectionListener;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AudioOnlyStreamService extends Service {
    public static final String ACTION_START = "com.limelight.action.START_AUDIO_ONLY_STREAM";
    public static final String ACTION_STOP = "com.limelight.action.STOP_AUDIO_ONLY_STREAM";

    public static final int STATE_STOPPED = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_PLAYING = 2;
    public static final int STATE_STOPPING = 3;
    public static final int STATE_ERROR = 4;

    private static final int NOTIFICATION_ID = 1001;
    private static final String NOTIFICATION_CHANNEL_ID = "audio_only_stream";
    private static volatile boolean sessionActive;

    public interface StateListener {
        void onStateChanged(int state, String appName, String pcName, String detail);
    }

    public static final class PerformanceSnapshot {
        public final int estimatedRttMs;
        public final int rttVarianceMs;
        public final int pendingAudioDurationMs;
        public final float audioTrackWriteAverageMs;
        public final float audioTrackWriteMaximumMs;
        public final float videoReceivedFps;
        public final float videoFrameLossPercentage;

        private PerformanceSnapshot(int estimatedRttMs, int rttVarianceMs,
                                    int pendingAudioDurationMs,
                                    AndroidAudioRenderer.PerformanceSnapshot audioSnapshot,
                                    NoOpVideoRenderer.PerformanceSnapshot videoSnapshot) {
            this.estimatedRttMs = estimatedRttMs;
            this.rttVarianceMs = rttVarianceMs;
            this.pendingAudioDurationMs = pendingAudioDurationMs;
            this.audioTrackWriteAverageMs = audioSnapshot.averageWriteTimeMs;
            this.audioTrackWriteMaximumMs = audioSnapshot.maximumWriteTimeMs;
            this.videoReceivedFps = videoSnapshot.receivedFps;
            this.videoFrameLossPercentage = videoSnapshot.frameLossPercentage;
        }
    }

    public final class LocalBinder extends Binder {
        public int getState() {
            return state;
        }

        public String getAppName() {
            return appName;
        }

        public String getPcName() {
            return pcName;
        }

        public String getDetail() {
            return stateDetail;
        }

        public void addStateListener(StateListener listener) {
            if (listener != null) {
                stateListeners.add(listener);
                listener.onStateChanged(state, appName, pcName, stateDetail);
            }
        }

        public void removeStateListener(StateListener listener) {
            stateListeners.remove(listener);
        }

        public PerformanceSnapshot getPerformanceSnapshot() {
            return AudioOnlyStreamService.this.getPerformanceSnapshot();
        }

        public boolean isPerformanceMetricsEnabled() {
            synchronized (connectionLock) {
                return performanceMetricsEnabled;
            }
        }
    }

    private final Object connectionLock = new Object();
    private final LocalBinder binder = new LocalBinder();
    private final Set<StateListener> stateListeners = new HashSet<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService connectionExecutor = Executors.newSingleThreadExecutor();

    private NotificationManager notificationManager;
    private MediaSession mediaSession;
    private WifiManager.WifiLock highPerfWifiLock;
    private WifiManager.WifiLock lowLatencyWifiLock;
    private PowerManager.WakeLock wakeLock;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private boolean hasAudioFocus;

    private NvConnection connection;
    private AndroidAudioRenderer audioRenderer;
    private NoOpVideoRenderer videoRenderer;
    private boolean performanceMetricsEnabled;
    private long connectionGeneration;
    private String sessionKey;
    private int state = STATE_STOPPED;
    private String appName;
    private String pcName;
    private String stateDetail;
    private float playbackVolume = 1.0f;

    public static boolean isSessionActive() {
        return sessionActive;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        createNotificationChannel();

        mediaSession = new MediaSession(this, "MoonlightAudioOnly");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onStop() {
                requestStop(true);
            }
        });
        mediaSession.setPlaybackToLocal(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        mediaSession.setActive(true);
        updateMediaSessionState();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        // The service enters the foreground before any connection setup or teardown work.
        startForeground(NOTIFICATION_ID, buildNotification());

        if (ACTION_STOP.equals(intent.getAction())) {
            requestStop(true);
        }
        else if (ACTION_START.equals(intent.getAction())) {
            startConnection(new Intent(intent));
        }

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private void startConnection(final Intent startIntent) {
        final int appId = startIntent.getIntExtra(Game.EXTRA_APP_ID, StreamConfiguration.INVALID_APP_ID);
        final String host = startIntent.getStringExtra(Game.EXTRA_HOST);
        if (appId == StreamConfiguration.INVALID_APP_ID || host == null) {
            setState(STATE_ERROR, getString(R.string.conn_error_msg));
            return;
        }

        final String requestedAppName = startIntent.getStringExtra(Game.EXTRA_APP_NAME);
        final String requestedPcName = startIntent.getStringExtra(Game.EXTRA_PC_NAME);
        final String streamAppName = requestedAppName != null ? requestedAppName :
                getString(R.string.audio_only_player_title);
        final int port = startIntent.getIntExtra(Game.EXTRA_PORT, NvHTTP.DEFAULT_HTTP_PORT);
        final String requestedSessionKey = host + ":" + port + ":" + appId + ":" +
                startIntent.getStringExtra(Game.EXTRA_UNIQUEID);

        if (requestedSessionKey.equals(sessionKey) &&
                (state == STATE_CONNECTING || state == STATE_PLAYING)) {
            return;
        }

        final NvConnection oldConnection;
        final long generation;
        synchronized (connectionLock) {
            generation = ++connectionGeneration;
            oldConnection = connection;
            connection = null;
            audioRenderer = null;
            videoRenderer = null;
            performanceMetricsEnabled = false;
        }

        sessionKey = requestedSessionKey;
        sessionActive = true;
        appName = streamAppName;
        pcName = requestedPcName != null ? requestedPcName : host;
        acquireLocks();
        setState(STATE_CONNECTING, getString(R.string.audio_only_connecting));
        updateMediaMetadata();

        if (!requestAudioFocus()) {
            sessionActive = false;
            releaseSessionResources();
            setState(STATE_ERROR, getString(R.string.audio_only_audio_focus_failed));
            stopForeground(true);
            stopSelf();
            return;
        }

        connectionExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (oldConnection != null) {
                    oldConnection.stop();
                }

                if (!isCurrentGeneration(generation)) {
                    return;
                }

                try {
                    PreferenceConfiguration prefConfig = PreferenceConfiguration.readPreferences(AudioOnlyStreamService.this);
                    NvApp app = new NvApp(streamAppName, appId,
                            startIntent.getBooleanExtra(Game.EXTRA_APP_HDR, false));

                    StreamConfiguration config = new StreamConfiguration.Builder()
                            .setResolution(prefConfig.audioOnlyVideoWidth,
                                    prefConfig.audioOnlyVideoHeight)
                            .setLaunchRefreshRate(prefConfig.audioOnlyVideoFps)
                            .setRefreshRate(prefConfig.audioOnlyVideoFps)
                            .setApp(app)
                            .setBitrate(prefConfig.audioOnlyVideoBitrate)
                            .setEnableSops(false)
                            .enableLocalAudioPlayback(prefConfig.playHostAudio)
                            .setMaxPacketSize(1392)
                            .setRemoteConfiguration(StreamConfiguration.STREAM_CFG_AUTO)
                            .setSupportedVideoFormats(MoonBridge.VIDEO_FORMAT_H264)
                            .setAttachedGamepadMask(0)
                            .setClientRefreshRateX100(0)
                            .setAudioConfiguration(prefConfig.audioConfiguration)
                            .setColorSpace(0)
                            .setColorRange(0)
                            .setPersistGamepadsAfterDisconnect(false)
                            .build();

                    X509Certificate serverCert = decodeServerCertificate(
                            startIntent.getByteArrayExtra(Game.EXTRA_SERVER_CERT));
                    final NvConnection newConnection = new NvConnection(
                            getApplicationContext(),
                            new ComputerDetails.AddressTuple(host, port),
                            startIntent.getIntExtra(Game.EXTRA_HTTPS_PORT, 0),
                            startIntent.getStringExtra(Game.EXTRA_UNIQUEID),
                            config,
                            PlatformBinding.getCryptoProvider(AudioOnlyStreamService.this),
                            serverCert);

                    synchronized (connectionLock) {
                        if (generation != connectionGeneration) {
                            return;
                        }
                        connection = newConnection;
                    }

                    AndroidAudioRenderer newAudioRenderer = new AndroidAudioRenderer(
                            AudioOnlyStreamService.this,
                            prefConfig.enableAudioFx,
                            AudioAttributes.USAGE_MEDIA,
                            AudioAttributes.CONTENT_TYPE_MUSIC,
                            android.media.audiofx.AudioEffect.CONTENT_TYPE_MUSIC,
                            prefConfig.enablePerfOverlay);
                    NoOpVideoRenderer newVideoRenderer = new NoOpVideoRenderer(
                            prefConfig.enablePerfOverlay);
                    synchronized (connectionLock) {
                        if (generation != connectionGeneration) {
                            return;
                        }
                        newAudioRenderer.setVolume(playbackVolume);
                        audioRenderer = newAudioRenderer;
                        videoRenderer = newVideoRenderer;
                        performanceMetricsEnabled = prefConfig.enablePerfOverlay;
                    }

                    newConnection.start(
                            newAudioRenderer,
                            newVideoRenderer,
                            new AudioConnectionListener(generation));
                } catch (Exception e) {
                    LimeLog.severe("Unable to start audio-only stream: " + e);
                    postConnectionError(generation, e.getMessage() != null ?
                            e.getMessage() : getString(R.string.conn_error_msg), true);
                }
            }
        });
    }

    private X509Certificate decodeServerCertificate(byte[] certificateData) throws CertificateException {
        if (certificateData == null) {
            return null;
        }

        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certificateData));
    }

    private boolean isCurrentGeneration(long generation) {
        synchronized (connectionLock) {
            return generation == connectionGeneration;
        }
    }

    private void requestStop(final boolean stopServiceWhenComplete) {
        final NvConnection connectionToStop;
        final long generation;
        synchronized (connectionLock) {
            generation = ++connectionGeneration;
            connectionToStop = connection;
            connection = null;
            audioRenderer = null;
            videoRenderer = null;
            performanceMetricsEnabled = false;
        }
        sessionKey = null;
        setState(STATE_STOPPING, getString(R.string.audio_only_stopping));

        connectionExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (connectionToStop != null) {
                    connectionToStop.stop();
                }

                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!isCurrentGeneration(generation)) {
                            return;
                        }
                        releaseSessionResources();
                        sessionActive = false;
                        setState(STATE_STOPPED, getString(R.string.audio_only_stopped));
                        if (stopServiceWhenComplete) {
                            stopForeground(true);
                            stopSelf();
                        }
                    }
                });
            }
        });
    }

    private void postConnectionError(final long generation, final String message,
                                     final boolean cleanupConnection) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!isCurrentGeneration(generation)) {
                    return;
                }

                final NvConnection connectionToStop;
                synchronized (connectionLock) {
                    ++connectionGeneration;
                    connectionToStop = connection;
                    connection = null;
                    audioRenderer = null;
                    videoRenderer = null;
                    performanceMetricsEnabled = false;
                }
                sessionKey = null;
                sessionActive = false;
                releaseSessionResources();
                setState(STATE_ERROR, message);

                if (cleanupConnection && connectionToStop != null) {
                    connectionExecutor.execute(new Runnable() {
                        @Override
                        public void run() {
                            connectionToStop.stop();
                        }
                    });
                }

                stopForeground(true);
                stopSelf();
            }
        });
    }

    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int focusChange) {
                    switch (focusChange) {
                        case AudioManager.AUDIOFOCUS_GAIN:
                            setRendererVolume(1.0f);
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                            setRendererVolume(0.2f);
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                            setRendererVolume(0.0f);
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS:
                            requestStop(true);
                            break;
                        default:
                            break;
                    }
                }
            };

    private boolean requestAudioFocus() {
        if (hasAudioFocus) {
            return true;
        }

        synchronized (connectionLock) {
            playbackVolume = 1.0f;
        }

        int result;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setOnAudioFocusChangeListener(audioFocusChangeListener, mainHandler)
                    .build();
            result = audioManager.requestAudioFocus(audioFocusRequest);
        }
        else {
            result = audioManager.requestAudioFocus(audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }

        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return hasAudioFocus;
    }

    private void abandonAudioFocus() {
        if (!hasAudioFocus) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        }
        else {
            audioManager.abandonAudioFocus(audioFocusChangeListener);
        }
        hasAudioFocus = false;
    }

    private void setRendererVolume(float volume) {
        synchronized (connectionLock) {
            playbackVolume = volume;
            if (audioRenderer != null) {
                audioRenderer.setVolume(volume);
            }
        }
    }

    private PerformanceSnapshot getPerformanceSnapshot() {
        final AndroidAudioRenderer currentAudioRenderer;
        final NoOpVideoRenderer currentVideoRenderer;
        synchronized (connectionLock) {
            if (state != STATE_PLAYING || !performanceMetricsEnabled ||
                    audioRenderer == null || videoRenderer == null) {
                return null;
            }
            currentAudioRenderer = audioRenderer;
            currentVideoRenderer = videoRenderer;
        }

        AndroidAudioRenderer.PerformanceSnapshot audioSnapshot =
                currentAudioRenderer.getPerformanceSnapshot();
        NoOpVideoRenderer.PerformanceSnapshot videoSnapshot =
                currentVideoRenderer.getPerformanceSnapshot();
        if (audioSnapshot == null || videoSnapshot == null) {
            return null;
        }

        long rttInfo = MoonBridge.getEstimatedRttInfo();
        int estimatedRttMs = rttInfo == -1 ? -1 : (int) (rttInfo >> 32);
        int rttVarianceMs = rttInfo == -1 ? -1 : (int) rttInfo;
        return new PerformanceSnapshot(estimatedRttMs, rttVarianceMs,
                MoonBridge.getPendingAudioDuration(), audioSnapshot, videoSnapshot);
    }

    private void setState(int newState, String detail) {
        state = newState;
        stateDetail = detail;
        updateMediaSessionState();

        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification());
        }

        StateListener[] listeners = stateListeners.toArray(new StateListener[0]);
        for (StateListener listener : listeners) {
            listener.onStateChanged(state, appName, pcName, stateDetail);
        }
    }

    private void updateMediaMetadata() {
        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, appName)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, pcName);
        mediaSession.setMetadata(metadata.build());
    }

    private void updateMediaSessionState() {
        if (mediaSession == null) {
            return;
        }

        int playbackState;
        switch (state) {
            case STATE_CONNECTING:
                playbackState = PlaybackState.STATE_CONNECTING;
                break;
            case STATE_PLAYING:
                playbackState = PlaybackState.STATE_PLAYING;
                break;
            case STATE_STOPPING:
                playbackState = PlaybackState.STATE_STOPPED;
                break;
            case STATE_ERROR:
                playbackState = PlaybackState.STATE_ERROR;
                break;
            case STATE_STOPPED:
            default:
                playbackState = PlaybackState.STATE_STOPPED;
                break;
        }

        PlaybackState.Builder builder = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_STOP)
                .setState(playbackState, PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        playbackState == PlaybackState.STATE_PLAYING ? 1.0f : 0.0f);
        if (state == STATE_ERROR && stateDetail != null) {
            builder.setErrorMessage(stateDetail);
        }
        mediaSession.setPlaybackState(builder.build());
    }

    private Notification buildNotification() {
        Intent playerIntent = new Intent(this, AudioOnlyPlayerActivity.class)
                .setAction(AudioOnlyPlayerActivity.ACTION_OPEN_PLAYER)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Intent stopIntent = new Intent(this, AudioOnlyStreamService.class).setAction(ACTION_STOP);
        int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent playerPendingIntent = PendingIntent.getActivity(
                this, 0, playerIntent, pendingIntentFlags);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 1, stopIntent, pendingIntentFlags);
        mediaSession.setSessionActivity(playerPendingIntent);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID);
        }
        else {
            builder = new Notification.Builder(this);
        }

        builder.setSmallIcon(R.drawable.ic_audio_stream)
                .setContentTitle(getString(R.string.audio_only_notification_title,
                        appName != null ? appName : getString(R.string.audio_only_player_title)))
                .setContentText(getNotificationText())
                .setContentIntent(playerPendingIntent)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setOngoing(state != STATE_STOPPED)
                .addAction(new Notification.Action.Builder(
                        R.drawable.ic_stop_stream,
                        getString(R.string.audio_only_stop),
                        stopPendingIntent).build())
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0));

        return builder.build();
    }

    private String getNotificationText() {
        switch (state) {
            case STATE_CONNECTING:
                return getString(R.string.audio_only_notification_connecting,
                        pcName != null ? pcName : "");
            case STATE_PLAYING:
                return getString(R.string.audio_only_notification_playing,
                        pcName != null ? pcName : "");
            case STATE_STOPPING:
                return getString(R.string.audio_only_stopping);
            case STATE_ERROR:
                return getString(R.string.audio_only_error,
                        stateDetail != null ? stateDetail : getString(R.string.conn_error_msg));
            case STATE_STOPPED:
            default:
                return getString(R.string.audio_only_stopped);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    getString(R.string.audio_only_notification_channel),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.audio_only_notification_channel_description));
            notificationManager.createNotificationChannel(channel);
        }
    }

    @SuppressLint("WakelockTimeout")
    private void acquireLocks() {
        if (wakeLock == null) {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "Moonlight:AudioOnlyWakeLock");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) {
            wakeLock.acquire();
        }

        WifiManager wifiManager = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        try {
            if (highPerfWifiLock == null) {
                highPerfWifiLock = wifiManager.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Moonlight Audio High Perf Lock");
                highPerfWifiLock.setReferenceCounted(false);
            }
            if (!highPerfWifiLock.isHeld()) {
                highPerfWifiLock.acquire();
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (lowLatencyWifiLock == null) {
                    lowLatencyWifiLock = wifiManager.createWifiLock(
                            WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Moonlight Audio Low Latency Lock");
                    lowLatencyWifiLock.setReferenceCounted(false);
                }
                if (!lowLatencyWifiLock.isHeld()) {
                    lowLatencyWifiLock.acquire();
                }
            }
        } catch (SecurityException e) {
            LimeLog.warning("Unable to acquire Wi-Fi lock for audio-only stream: " + e);
        }
    }

    private void releaseLocks() {
        if (lowLatencyWifiLock != null && lowLatencyWifiLock.isHeld()) {
            lowLatencyWifiLock.release();
        }
        if (highPerfWifiLock != null && highPerfWifiLock.isHeld()) {
            highPerfWifiLock.release();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void releaseSessionResources() {
        abandonAudioFocus();
        releaseLocks();
    }

    @Override
    public void onDestroy() {
        final NvConnection connectionToStop;
        synchronized (connectionLock) {
            ++connectionGeneration;
            connectionToStop = connection;
            connection = null;
            audioRenderer = null;
            videoRenderer = null;
            performanceMetricsEnabled = false;
        }
        sessionActive = false;
        if (connectionToStop != null) {
            connectionExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    connectionToStop.stop();
                }
            });
        }
        connectionExecutor.shutdown();
        releaseSessionResources();
        stateListeners.clear();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        super.onDestroy();
    }

    private final class AudioConnectionListener implements NvConnectionListener {
        private final long generation;

        private AudioConnectionListener(long generation) {
            this.generation = generation;
        }

        @Override
        public void stageStarting(final String stage) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (isCurrentGeneration(generation)) {
                        setState(STATE_CONNECTING, getString(R.string.audio_only_starting, stage));
                    }
                }
            });
        }

        @Override
        public void stageComplete(String stage) {
        }

        @Override
        public void stageFailed(String stage, int portFlags, int errorCode) {
            String message = getString(R.string.conn_error_msg) + " " + stage +
                    " (error " + errorCode + ")";
            if (portFlags != 0) {
                message += "\n" + getString(R.string.check_ports_msg) + "\n" +
                        MoonBridge.stringifyPortFlags(portFlags, "\n");
            }
            postConnectionError(generation, message, false);
        }

        @Override
        public void connectionStarted() {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (isCurrentGeneration(generation)) {
                        setState(STATE_PLAYING, getString(R.string.audio_only_playing));
                    }
                }
            });
        }

        @Override
        public void connectionTerminated(int errorCode) {
            if (errorCode == MoonBridge.ML_ERROR_GRACEFUL_TERMINATION) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isCurrentGeneration(generation)) {
                            requestStop(true);
                        }
                    }
                });
                return;
            }

            String errorCodeString = Math.abs(errorCode) > 1000 ?
                    Integer.toHexString(errorCode) : Integer.toString(errorCode);
            String message = getString(R.string.conn_terminated_msg) + " " +
                    getString(R.string.error_code_prefix) + " " + errorCodeString;
            postConnectionError(generation, message, true);
        }

        @Override
        public void connectionStatusUpdate(final int connectionStatus) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (!isCurrentGeneration(generation) || state != STATE_PLAYING) {
                        return;
                    }
                    if (connectionStatus == MoonBridge.CONN_STATUS_POOR) {
                        setState(STATE_PLAYING, getString(R.string.poor_connection_msg));
                    }
                    else if (connectionStatus == MoonBridge.CONN_STATUS_OKAY) {
                        setState(STATE_PLAYING, getString(R.string.audio_only_playing));
                    }
                }
            });
        }

        @Override
        public void displayMessage(String message) {
            postConnectionError(generation, message, false);
        }

        @Override
        public void displayTransientMessage(final String message) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (isCurrentGeneration(generation)) {
                        setState(state, message);
                    }
                }
            });
        }

        @Override
        public void rumble(short controllerNumber, short lowFreqMotor, short highFreqMotor) {
        }

        @Override
        public void rumbleTriggers(short controllerNumber, short leftTrigger, short rightTrigger) {
        }

        @Override
        public void setHdrMode(boolean enabled, byte[] hdrMetadata) {
        }

        @Override
        public void setMotionEventState(short controllerNumber, byte motionType, short reportRateHz) {
        }

        @Override
        public void setControllerLED(short controllerNumber, byte r, byte g, byte b) {
        }
    }
}
