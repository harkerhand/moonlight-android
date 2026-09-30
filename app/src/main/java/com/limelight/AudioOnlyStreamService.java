package com.limelight;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
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
import android.os.SystemClock;
import android.util.Base64;

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
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class AudioOnlyStreamService extends Service {
    public static final String ACTION_START = "com.limelight.action.START_AUDIO_ONLY_STREAM";
    public static final String ACTION_STOP = "com.limelight.action.STOP_AUDIO_ONLY_STREAM";
    public static final String ACTION_RESTORE = "com.limelight.action.RESTORE_AUDIO_ONLY_STREAM";

    public static final int STATE_STOPPED = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_PLAYING = 2;
    public static final int STATE_STOPPING = 3;
    public static final int STATE_ERROR = 4;
    public static final int STATE_RECONNECTING = 5;

    private static final int NOTIFICATION_ID = 1001;
    private static final String NOTIFICATION_CHANNEL_ID = "audio_only_stream";
    private static final String SESSION_PREFS = "AudioOnlySession";
    private static final String SESSION_ACTIVE = "active";
    private static final String SESSION_HOST = "host";
    private static final String SESSION_PORT = "port";
    private static final String SESSION_HTTPS_PORT = "httpsPort";
    private static final String SESSION_APP_NAME = "appName";
    private static final String SESSION_APP_ID = "appId";
    private static final String SESSION_APP_HDR = "appHdr";
    private static final String SESSION_UNIQUE_ID = "uniqueId";
    private static final String SESSION_PC_UUID = "pcUuid";
    private static final String SESSION_PC_NAME = "pcName";
    private static final String SESSION_SERVER_CERT = "serverCert";
    private static final int[] RECONNECT_DELAYS_MS = { 1000, 2000, 5000, 10000, 30000 };
    private static final long TRACE_INTERVAL_MS = 200;
    private static final long TRACE_HISTORY_DURATION_MS = 60_000;
    private static final int TRACE_HISTORY_SIZE = 300;
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
        public final long sequence;
        public final long sampledAtMs;

        private PerformanceSnapshot(long sequence, long sampledAtMs,
                                    int estimatedRttMs, int rttVarianceMs,
                                    int pendingAudioDurationMs,
                                    AndroidAudioRenderer.PerformanceSnapshot audioSnapshot,
                                    NoOpVideoRenderer.PerformanceSnapshot videoSnapshot) {
            this.sequence = sequence;
            this.sampledAtMs = sampledAtMs;
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

        public PerformanceSnapshot[] getPerformanceSnapshotsAfter(long sequence) {
            return AudioOnlyStreamService.this.getPerformanceSnapshotsAfter(sequence);
        }

        public PerformanceSnapshot[] getPerformanceHistory() {
            return AudioOnlyStreamService.this.getPerformanceHistory();
        }

        public boolean isPerformanceDisplayEnabled() {
            synchronized (connectionLock) {
                return performanceDisplayEnabled;
            }
        }
    }

    private final Object connectionLock = new Object();
    private final LocalBinder binder = new LocalBinder();
    private final Set<StateListener> stateListeners = new HashSet<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService connectionExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService traceExecutor =
            Executors.newSingleThreadScheduledExecutor();
    private final Object traceLock = new Object();
    private final PerformanceSnapshot[] traceHistory =
            new PerformanceSnapshot[TRACE_HISTORY_SIZE];
    private int traceNextIndex;
    private int traceCount;
    private long traceSequence;
    private long traceSamplingGeneration;
    private ScheduledFuture<?> traceFuture;

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
    private boolean performanceDisplayEnabled;
    private long connectionGeneration;
    private String sessionKey;
    private volatile int state = STATE_STOPPED;
    private String appName;
    private String pcName;
    private String stateDetail;
    private float playbackVolume = 1.0f;
    private Intent lastStartIntent;
    private boolean sessionRequested;
    private boolean reconnectScheduled;
    private int reconnectAttempt;
    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            reconnectScheduled = false;
            if (!sessionRequested || lastStartIntent == null) {
                return;
            }
            startConnection(new Intent(lastStartIntent), true);
        }
    };

    public static boolean isSessionActive(Context context) {
        if (sessionActive) {
            return true;
        }
        try {
            return context.getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)
                    .getBoolean(SESSION_ACTIVE, false);
        } catch (ClassCastException e) {
            context.getSharedPreferences(SESSION_PREFS, MODE_PRIVATE).edit().clear().apply();
            return false;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        createNotificationChannel();

        mediaSession = new MediaSession(this, "MoonlightAudioOnly");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onStop() {
                requestStop(true, true);
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
        if (intent != null && ACTION_START.equals(intent.getAction()) &&
                (flags & START_FLAG_REDELIVERY) != 0 && !hasPersistedSession()) {
            // An explicit stop may have cleared the persisted session just before the process
            // was killed. Do not allow Android to resurrect that stale ACTION_START intent.
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        Intent effectiveIntent = intent;
        if (effectiveIntent == null || ACTION_RESTORE.equals(effectiveIntent.getAction())) {
            effectiveIntent = restoreStartIntent();
            if (effectiveIntent != null) {
                LimeLog.info("Restoring persisted audio-only session");
            }
        }

        if (effectiveIntent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        // The service enters the foreground before any connection setup or teardown work.
        enterForeground();

        if (ACTION_STOP.equals(effectiveIntent.getAction())) {
            requestStop(true, true);
        }
        else if (ACTION_START.equals(effectiveIntent.getAction())) {
            Intent startIntent = new Intent(effectiveIntent);
            persistStartIntent(startIntent);
            lastStartIntent = startIntent;
            sessionRequested = true;
            cancelReconnect();
            reconnectAttempt = 0;
            startConnection(startIntent, false);
        }
        else {
            stopForeground(true);
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        return START_REDELIVER_INTENT;
    }

    private void enterForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        }
        else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private void startConnection(final Intent startIntent, boolean reconnecting) {
        final int appId = startIntent.getIntExtra(Game.EXTRA_APP_ID, StreamConfiguration.INVALID_APP_ID);
        final String host = startIntent.getStringExtra(Game.EXTRA_HOST);
        if (appId == StreamConfiguration.INVALID_APP_ID || host == null) {
            clearPersistedSession();
            sessionRequested = false;
            sessionActive = false;
            setState(STATE_ERROR, getString(R.string.conn_error_msg));
            stopForeground(true);
            stopSelf();
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

        if (!requestedSessionKey.equals(sessionKey)) {
            stopTraceSampling();
            clearTraceHistory();
        }

        final NvConnection oldConnection;
        final long generation;
        synchronized (connectionLock) {
            generation = ++connectionGeneration;
            oldConnection = connection;
            connection = null;
            audioRenderer = null;
            videoRenderer = null;
            performanceDisplayEnabled = false;
        }

        sessionKey = requestedSessionKey;
        sessionActive = true;
        appName = streamAppName;
        pcName = requestedPcName != null ? requestedPcName : host;
        acquireLocks();
        setState(STATE_CONNECTING, reconnecting ?
                getString(R.string.audio_only_reconnecting_now) :
                getString(R.string.audio_only_connecting));
        updateMediaMetadata();

        if (!requestAudioFocus()) {
            handleConnectionFailure(generation,
                    getString(R.string.audio_only_audio_focus_failed));
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
                            true);
                    NoOpVideoRenderer newVideoRenderer = new NoOpVideoRenderer(true);
                    synchronized (connectionLock) {
                        if (generation != connectionGeneration) {
                            return;
                        }
                        newAudioRenderer.setVolume(playbackVolume);
                        audioRenderer = newAudioRenderer;
                        videoRenderer = newVideoRenderer;
                        performanceDisplayEnabled = prefConfig.enablePerfOverlay;
                    }

                    newConnection.start(
                            newAudioRenderer,
                            newVideoRenderer,
                            new AudioConnectionListener(generation));
                } catch (CertificateException | IllegalArgumentException e) {
                    LimeLog.severe("Invalid audio-only session configuration: " + e);
                    handleTerminalFailure(generation, e.getMessage() != null ?
                            e.getMessage() : getString(R.string.conn_error_msg));
                } catch (Exception e) {
                    LimeLog.severe("Unable to start audio-only stream: " + e);
                    handleConnectionFailure(generation, e.getMessage() != null ?
                            e.getMessage() : getString(R.string.conn_error_msg));
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

    private void persistStartIntent(Intent intent) {
        SharedPreferences.Editor editor = getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)
                .edit()
                .clear()
                .putBoolean(SESSION_ACTIVE, true)
                .putString(SESSION_HOST, intent.getStringExtra(Game.EXTRA_HOST))
                .putInt(SESSION_PORT, intent.getIntExtra(Game.EXTRA_PORT, NvHTTP.DEFAULT_HTTP_PORT))
                .putInt(SESSION_HTTPS_PORT, intent.getIntExtra(Game.EXTRA_HTTPS_PORT, 0))
                .putString(SESSION_APP_NAME, intent.getStringExtra(Game.EXTRA_APP_NAME))
                .putInt(SESSION_APP_ID, intent.getIntExtra(Game.EXTRA_APP_ID,
                        StreamConfiguration.INVALID_APP_ID))
                .putBoolean(SESSION_APP_HDR, intent.getBooleanExtra(Game.EXTRA_APP_HDR, false))
                .putString(SESSION_UNIQUE_ID, intent.getStringExtra(Game.EXTRA_UNIQUEID))
                .putString(SESSION_PC_UUID, intent.getStringExtra(Game.EXTRA_PC_UUID))
                .putString(SESSION_PC_NAME, intent.getStringExtra(Game.EXTRA_PC_NAME));

        byte[] certificate = intent.getByteArrayExtra(Game.EXTRA_SERVER_CERT);
        if (certificate != null) {
            editor.putString(SESSION_SERVER_CERT, Base64.encodeToString(certificate, Base64.NO_WRAP));
        }
        if (!editor.commit()) {
            LimeLog.warning("Failed to persist audio-only session recovery data");
        }
    }

    private Intent restoreStartIntent() {
        SharedPreferences preferences = getSharedPreferences(SESSION_PREFS, MODE_PRIVATE);
        try {
            if (!preferences.getBoolean(SESSION_ACTIVE, false)) {
                return null;
            }

            String host = preferences.getString(SESSION_HOST, null);
            int appId = preferences.getInt(SESSION_APP_ID, StreamConfiguration.INVALID_APP_ID);
            if (host == null || appId == StreamConfiguration.INVALID_APP_ID) {
                clearPersistedSession();
                return null;
            }

            Intent intent = new Intent(this, AudioOnlyStreamService.class).setAction(ACTION_START);
            intent.putExtra(Game.EXTRA_HOST, host);
            intent.putExtra(Game.EXTRA_PORT,
                    preferences.getInt(SESSION_PORT, NvHTTP.DEFAULT_HTTP_PORT));
            intent.putExtra(Game.EXTRA_HTTPS_PORT, preferences.getInt(SESSION_HTTPS_PORT, 0));
            intent.putExtra(Game.EXTRA_APP_NAME, preferences.getString(SESSION_APP_NAME, null));
            intent.putExtra(Game.EXTRA_APP_ID, appId);
            intent.putExtra(Game.EXTRA_APP_HDR, preferences.getBoolean(SESSION_APP_HDR, false));
            intent.putExtra(Game.EXTRA_UNIQUEID, preferences.getString(SESSION_UNIQUE_ID, null));
            intent.putExtra(Game.EXTRA_PC_UUID, preferences.getString(SESSION_PC_UUID, null));
            intent.putExtra(Game.EXTRA_PC_NAME, preferences.getString(SESSION_PC_NAME, null));

            String encodedCertificate = preferences.getString(SESSION_SERVER_CERT, null);
            if (encodedCertificate != null) {
                intent.putExtra(Game.EXTRA_SERVER_CERT,
                        Base64.decode(encodedCertificate, Base64.DEFAULT));
            }
            return intent;
        } catch (RuntimeException e) {
            LimeLog.warning("Discarding invalid saved audio-only session: " + e);
            clearPersistedSession();
            return null;
        }
    }

    private void clearPersistedSession() {
        if (!getSharedPreferences(SESSION_PREFS, MODE_PRIVATE).edit().clear().commit()) {
            LimeLog.warning("Failed to clear audio-only session recovery data");
        }
    }

    private boolean hasPersistedSession() {
        try {
            return getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)
                    .getBoolean(SESSION_ACTIVE, false);
        } catch (ClassCastException e) {
            clearPersistedSession();
            return false;
        }
    }

    private boolean isCurrentGeneration(long generation) {
        synchronized (connectionLock) {
            return generation == connectionGeneration;
        }
    }

    private void requestStop(final boolean stopServiceWhenComplete, boolean clearSession) {
        sessionRequested = false;
        cancelReconnect();
        stopTraceSampling();
        clearTraceHistory();
        if (clearSession) {
            clearPersistedSession();
        }

        final NvConnection connectionToStop;
        final long generation;
        synchronized (connectionLock) {
            generation = ++connectionGeneration;
            connectionToStop = connection;
            connection = null;
            audioRenderer = null;
            videoRenderer = null;
            performanceDisplayEnabled = false;
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

    private void handleConnectionFailure(final long generation, final String message) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!isCurrentGeneration(generation) || !sessionRequested) {
                    return;
                }

                final NvConnection connectionToStop;
                final long stoppedGeneration;
                synchronized (connectionLock) {
                    stoppedGeneration = ++connectionGeneration;
                    connectionToStop = connection;
                    connection = null;
                    audioRenderer = null;
                    videoRenderer = null;
                    performanceDisplayEnabled = false;
                }
                sessionActive = true;
                stopTraceSampling();
                clearTraceHistory();
                setState(STATE_RECONNECTING, message);

                connectionExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        if (connectionToStop != null) {
                            connectionToStop.stop();
                        }

                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (isCurrentGeneration(stoppedGeneration) && sessionRequested) {
                                    releaseSessionResources();
                                    scheduleReconnect();
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    private void handleTerminalFailure(final long generation, final String message) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!isCurrentGeneration(generation)) {
                    return;
                }

                sessionRequested = false;
                cancelReconnect();
                stopTraceSampling();
                clearTraceHistory();
                clearPersistedSession();

                final NvConnection connectionToStop;
                synchronized (connectionLock) {
                    ++connectionGeneration;
                    connectionToStop = connection;
                    connection = null;
                    audioRenderer = null;
                    videoRenderer = null;
                    performanceDisplayEnabled = false;
                }
                sessionKey = null;
                sessionActive = false;
                releaseSessionResources();
                setState(STATE_ERROR, message);

                if (connectionToStop != null) {
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

    private void scheduleReconnect() {
        if (!sessionRequested || lastStartIntent == null || reconnectScheduled) {
            return;
        }

        int delayIndex = Math.min(reconnectAttempt, RECONNECT_DELAYS_MS.length - 1);
        int delayMs = RECONNECT_DELAYS_MS[delayIndex];
        reconnectAttempt++;
        reconnectScheduled = true;
        LimeLog.warning("Audio-only session reconnect scheduled in " + delayMs + " ms");
        setState(STATE_RECONNECTING,
                getString(R.string.audio_only_reconnecting_delay, Math.max(1, delayMs / 1000)));
        mainHandler.postDelayed(reconnectRunnable, delayMs);
    }

    private void cancelReconnect() {
        mainHandler.removeCallbacks(reconnectRunnable);
        reconnectScheduled = false;
    }

    private boolean isRetryableStageFailure(String stage, int errorCode, String message) {
        if (errorCode == 400 || errorCode == 401 || errorCode == 403 || errorCode == 404 ||
                errorCode == 470 || errorCode == 525 || errorCode == 599) {
            return false;
        }

        String normalizedStage = stage == null ? "" : stage.toLowerCase(Locale.ROOT);
        if (normalizedStage.equals("platform initialization") ||
                normalizedStage.equals("audio stream initialization") ||
                normalizedStage.equals("control stream initialization") ||
                normalizedStage.equals("video stream initialization") ||
                normalizedStage.equals("input stream initialization")) {
            return false;
        }

        String normalizedMessage = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return !(normalizedMessage.contains("not paired") ||
                normalizedMessage.contains("certificate mismatch") ||
                normalizedMessage.contains("server version malformed") ||
                normalizedMessage.contains("does not support") ||
                normalizedMessage.contains("not in gfe app list") ||
                normalizedMessage.contains("wasn't started by this device") ||
                normalizedMessage.contains("application is minimized") ||
                normalizedMessage.contains("failed to quit previous session") ||
                normalizedMessage.contains("failed to launch application") ||
                normalizedMessage.contains("failed to resume existing session"));
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
                            requestStop(true, true);
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

    private PerformanceSnapshot capturePerformanceSnapshot() {
        final AndroidAudioRenderer currentAudioRenderer;
        final NoOpVideoRenderer currentVideoRenderer;
        synchronized (connectionLock) {
            if (state != STATE_PLAYING ||
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
        long sequence;
        synchronized (traceLock) {
            sequence = ++traceSequence;
        }
        return new PerformanceSnapshot(sequence, SystemClock.elapsedRealtime(),
                estimatedRttMs, rttVarianceMs,
                MoonBridge.getPendingAudioDuration(), audioSnapshot, videoSnapshot);
    }

    private PerformanceSnapshot[] getPerformanceSnapshotsAfter(long sequence) {
        synchronized (traceLock) {
            pruneTraceHistoryLocked(SystemClock.elapsedRealtime() - TRACE_HISTORY_DURATION_MS);
            if (traceCount == 0) {
                return new PerformanceSnapshot[0];
            }

            int oldestIndex = (traceNextIndex - traceCount + TRACE_HISTORY_SIZE) %
                    TRACE_HISTORY_SIZE;
            int matchingCount = 0;
            for (int i = 0; i < traceCount; i++) {
                PerformanceSnapshot snapshot =
                        traceHistory[(oldestIndex + i) % TRACE_HISTORY_SIZE];
                if (snapshot.sequence > sequence) {
                    matchingCount++;
                }
            }

            PerformanceSnapshot[] snapshots = new PerformanceSnapshot[matchingCount];
            int outputIndex = 0;
            for (int i = 0; i < traceCount; i++) {
                PerformanceSnapshot snapshot =
                        traceHistory[(oldestIndex + i) % TRACE_HISTORY_SIZE];
                if (snapshot.sequence > sequence) {
                    snapshots[outputIndex++] = snapshot;
                }
            }
            return snapshots;
        }
    }

    private PerformanceSnapshot[] getPerformanceHistory() {
        synchronized (traceLock) {
            pruneTraceHistoryLocked(SystemClock.elapsedRealtime() - TRACE_HISTORY_DURATION_MS);
            PerformanceSnapshot[] snapshots = new PerformanceSnapshot[traceCount];
            int oldestIndex = (traceNextIndex - traceCount + TRACE_HISTORY_SIZE) %
                    TRACE_HISTORY_SIZE;
            for (int i = 0; i < traceCount; i++) {
                snapshots[i] = traceHistory[(oldestIndex + i) % TRACE_HISTORY_SIZE];
            }
            return snapshots;
        }
    }

    private synchronized void startTraceSampling() {
        if (traceFuture != null && !traceFuture.isDone()) {
            return;
        }

        final long generation = ++traceSamplingGeneration;
        traceFuture = traceExecutor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    PerformanceSnapshot snapshot = capturePerformanceSnapshot();
                    if (snapshot == null) {
                        return;
                    }

                    synchronized (traceLock) {
                        if (generation != traceSamplingGeneration) {
                            return;
                        }
                        pruneTraceHistoryLocked(snapshot.sampledAtMs - TRACE_HISTORY_DURATION_MS);
                        traceHistory[traceNextIndex] = snapshot;
                        traceNextIndex = (traceNextIndex + 1) % TRACE_HISTORY_SIZE;
                        traceCount = Math.min(traceCount + 1, TRACE_HISTORY_SIZE);
                    }
                } catch (RuntimeException e) {
                    LimeLog.warning("Audio-only trace sample failed: " + e);
                }
            }
        }, 0, TRACE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void stopTraceSampling() {
        traceSamplingGeneration++;
        if (traceFuture != null) {
            traceFuture.cancel(false);
            traceFuture = null;
        }
    }

    private void clearTraceHistory() {
        synchronized (traceLock) {
            for (int i = 0; i < traceHistory.length; i++) {
                traceHistory[i] = null;
            }
            traceNextIndex = 0;
            traceCount = 0;
        }
    }

    private void pruneTraceHistoryLocked(long cutoffMs) {
        while (traceCount > 0) {
            int oldestIndex = (traceNextIndex - traceCount + TRACE_HISTORY_SIZE) %
                    TRACE_HISTORY_SIZE;
            PerformanceSnapshot oldest = traceHistory[oldestIndex];
            if (oldest != null && oldest.sampledAtMs >= cutoffMs) {
                break;
            }
            traceHistory[oldestIndex] = null;
            traceCount--;
        }
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
            case STATE_RECONNECTING:
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }

        return builder.build();
    }

    private String getNotificationText() {
        switch (state) {
            case STATE_CONNECTING:
                return getString(R.string.audio_only_notification_connecting,
                        pcName != null ? pcName : "");
            case STATE_RECONNECTING:
                return stateDetail != null ? stateDetail :
                        getString(R.string.audio_only_reconnecting_now);
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
        cancelReconnect();
        stopTraceSampling();
        final NvConnection connectionToStop;
        synchronized (connectionLock) {
            ++connectionGeneration;
            connectionToStop = connection;
            connection = null;
            audioRenderer = null;
            videoRenderer = null;
            performanceDisplayEnabled = false;
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
        traceExecutor.shutdownNow();
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
        private String failureMessage;

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
            String message = failureMessage != null ? failureMessage :
                    getString(R.string.conn_error_msg) + " " + stage +
                            " (error " + errorCode + ")";
            if (portFlags != 0) {
                message += "\n" + getString(R.string.check_ports_msg) + "\n" +
                        MoonBridge.stringifyPortFlags(portFlags, "\n");
            }
            if (isRetryableStageFailure(stage, errorCode, message)) {
                handleConnectionFailure(generation, message);
            }
            else {
                handleTerminalFailure(generation, message);
            }
        }

        @Override
        public void connectionStarted() {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (isCurrentGeneration(generation)) {
                        cancelReconnect();
                        reconnectAttempt = 0;
                        LimeLog.info("Audio-only session connected");
                        setState(STATE_PLAYING, getString(R.string.audio_only_playing));
                        startTraceSampling();
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
                            requestStop(true, true);
                        }
                    }
                });
                return;
            }

            String errorCodeString = Math.abs(errorCode) > 1000 ?
                    Integer.toHexString(errorCode) : Integer.toString(errorCode);
            String message = getString(R.string.conn_terminated_msg) + " " +
                    getString(R.string.error_code_prefix) + " " + errorCodeString;
            if (errorCode == MoonBridge.ML_ERROR_PROTECTED_CONTENT ||
                    errorCode == MoonBridge.ML_ERROR_FRAME_CONVERSION) {
                handleTerminalFailure(generation, message);
            }
            else {
                handleConnectionFailure(generation, message);
            }
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
            failureMessage = message;
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
