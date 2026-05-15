package com.spotify.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.media.MediaBrowserService;
import android.util.Log;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * MediaBrowserService that hosts the actual Deezer playback. The MediaSession
 * is the source of truth for playback state; AA reads this state to render its
 * UI, and AA's voice commands arrive as {@link MediaSession.Callback}.onPlayFromSearch.
 */
public class ShimMediaBrowserService extends MediaBrowserService {
    private static final String TAG = "DeezerShim";
    private static final String MEDIA_ROOT_ID = "shim_root";
    private static final String PREFS = "deezer_shim";
    private static final String PREF_ARL = "arl";
    private static final String CHANNEL_ID = "deezer_shim_playback";
    private static final int NOTIF_ID = 1;
    private static final byte[] MAGIC = "g4el58wc0zvf9na1".getBytes();

    private MediaSession session;
    private MediaPlayer player;
    private StreamingMediaDataSource currentSource;
    private DeezerClient.Track currentTrack;
    private DeezerClient client;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final AudioAttributes AUDIO_ATTRS = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service onCreate");
        ensureChannel();
        session = new MediaSession(this, "DeezerShim");
        session.setCallback(new ShimCallback(this));
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        setIdleState();
        session.setActive(true);
        setSessionToken(session.getSessionToken());
        Log.i(TAG, "Session activated");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String query = intent.getStringExtra("query");
            String cmd = intent.getStringExtra("cmd");
            if (query != null) {
                playFromQuery(query);
            } else if ("pause".equals(cmd)) {
                pause();
            } else if ("resume".equals(cmd)) {
                resume();
            } else if ("stop".equals(cmd)) {
                stopPlayback();
            }
        }
        return START_STICKY;
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        Log.i(TAG, "onGetRoot from clientPackage=" + clientPackageName + " uid=" + clientUid);
        return new BrowserRoot(MEDIA_ROOT_ID, null);
    }

    @Override
    public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result) {
        Log.i(TAG, "onLoadChildren parentId=" + parentId);
        result.sendResult(new ArrayList<MediaBrowser.MediaItem>());
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Service onDestroy");
        teardownPlayer();
        if (session != null) session.release();
        super.onDestroy();
    }

    // ---- session callback ----

    private static class ShimCallback extends MediaSession.Callback {
        private final ShimMediaBrowserService svc;

        ShimCallback(ShimMediaBrowserService svc) { this.svc = svc; }

        @Override
        public void onPlayFromSearch(String query, Bundle extras) {
            Log.i(TAG, "onPlayFromSearch query=\"" + query + "\"");
            logExtras(extras);
            String enriched = svc.enrichQuery(query, extras);
            svc.playFromQuery(enriched);
        }

        @Override
        public void onPlayFromUri(android.net.Uri uri, Bundle extras) {
            Log.i(TAG, "onPlayFromUri uri=" + uri);
            logExtras(extras);
            // Robin sends Spotify URLs like https://open.spotify.com/track/<id>
            // with structured artist/title/album in extras. The Spotify ID is
            // useless to us; the structured extras are the source of truth.
            String query = svc.enrichQuery(null, extras);
            if (query.isEmpty() && uri != null) {
                // Fallback if extras are missing: just send the URL text
                query = uri.toString();
            }
            svc.playFromQuery(query);
        }

        @Override
        public void onPrepareFromSearch(String query, Bundle extras) {
            Log.i(TAG, "onPrepareFromSearch query=\"" + query + "\"");
            onPlayFromSearch(query, extras);
        }

        @Override
        public void onPrepareFromUri(android.net.Uri uri, Bundle extras) {
            Log.i(TAG, "onPrepareFromUri uri=" + uri);
            onPlayFromUri(uri, extras);
        }

        private static void logExtras(Bundle extras) {
            if (extras == null) {
                Log.i(TAG, "  (no extras)");
                return;
            }
            for (String key : extras.keySet()) {
                Log.i(TAG, "  extra " + key + " = " + extras.get(key));
            }
        }

        @Override
        public void onPlay() {
            Log.i(TAG, "onPlay");
            svc.resume();
        }

        @Override
        public void onPause() {
            Log.i(TAG, "onPause");
            svc.pause();
        }

        @Override
        public void onStop() {
            Log.i(TAG, "onStop");
            svc.stopPlayback();
        }

        @Override
        public void onSkipToNext() {
            Log.i(TAG, "onSkipToNext (no queue yet)");
        }

        @Override
        public void onSkipToPrevious() {
            Log.i(TAG, "onSkipToPrevious (no queue yet)");
        }

        @Override
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            Log.i(TAG, "onPlayFromMediaId mediaId=" + mediaId);
        }
    }

    // ---- player control (callable from any thread) ----

    String enrichQuery(String query, Bundle extras) {
        if (extras == null) return query == null ? "" : query;
        String artist = extras.getString("android.intent.extra.artist");
        String title = extras.getString("android.intent.extra.title");
        StringBuilder q = new StringBuilder();
        if (title != null) q.append(title);
        if (artist != null) {
            if (q.length() > 0) q.append(' ');
            q.append(artist);
        }
        if (q.length() == 0) {
            return query == null ? "" : query;
        }
        return q.toString();
    }

    /** Public entry point: search Deezer for the query and play the top match. */
    void playFromQuery(final String query) {
        Log.i(TAG, "playFromQuery: " + query);
        setBufferingState();
        new Thread(new PlayRunnable(this, query), "DeezerPlay").start();
    }

    private static class PlayRunnable implements Runnable {
        private final ShimMediaBrowserService svc;
        private final String query;

        PlayRunnable(ShimMediaBrowserService svc, String query) {
            this.svc = svc;
            this.query = query;
        }

        @Override
        public void run() {
            try {
                String arl = svc.getArl();
                if (arl == null) {
                    Log.e(TAG, "no ARL configured");
                    svc.setErrorState("Deezer ARL not set");
                    return;
                }
                if (svc.client == null) svc.client = new DeezerClient(arl);
                svc.client.authenticate();
                final DeezerClient.Track track = svc.client.searchTrack(query);
                Log.i(TAG, "resolved " + track.id + " " + track.title + " — " + track.artist);
                final DeezerClient.StreamInfo info = svc.client.getStreamInfo(track.id);
                svc.main.post(new StartPlaybackRunnable(svc, track, info));
            } catch (Exception e) {
                Log.e(TAG, "playFromQuery failed", e);
                svc.setErrorState(e.getMessage());
            }
        }
    }

    private static class StartPlaybackRunnable implements Runnable {
        private final ShimMediaBrowserService svc;
        private final DeezerClient.Track track;
        private final DeezerClient.StreamInfo info;

        StartPlaybackRunnable(ShimMediaBrowserService svc,
                              DeezerClient.Track track,
                              DeezerClient.StreamInfo info) {
            this.svc = svc;
            this.track = track;
            this.info = info;
        }

        @Override
        public void run() {
            svc.startPlayback(track, info);
        }
    }

    /** Must run on main thread. */
    private void startPlayback(DeezerClient.Track track, DeezerClient.StreamInfo info) {
        teardownPlayer();
        currentTrack = track;
        try {
            if (!requestFocus()) {
                Log.w(TAG, "audio focus denied; aborting playback");
                setErrorState("audio focus denied");
                return;
            }
            byte[] key = blowfishKey(track.id);
            currentSource = new StreamingMediaDataSource(info.url, key, info.contentLength);
            player = new MediaPlayer();
            player.setAudioAttributes(AUDIO_ATTRS);
            player.setDataSource(currentSource);
            player.setOnPreparedListener(new OnPreparedListenerImpl(this));
            player.setOnErrorListener(new OnErrorListenerImpl(this));
            player.setOnCompletionListener(new OnCompletionListenerImpl(this));
            updateMetadata(track);
            player.prepareAsync();
            startForegroundIfNeeded(track);
        } catch (Exception e) {
            Log.e(TAG, "startPlayback failed", e);
            setErrorState(e.getMessage());
        }
    }

    private static class OnPreparedListenerImpl implements MediaPlayer.OnPreparedListener {
        private final ShimMediaBrowserService svc;
        OnPreparedListenerImpl(ShimMediaBrowserService svc) { this.svc = svc; }
        @Override
        public void onPrepared(MediaPlayer mp) {
            Log.i(TAG, "MediaPlayer prepared");
            mp.start();
            svc.setPlayingState(mp.getCurrentPosition(), mp.getDuration());
        }
    }

    private static class OnErrorListenerImpl implements MediaPlayer.OnErrorListener {
        private final ShimMediaBrowserService svc;
        OnErrorListenerImpl(ShimMediaBrowserService svc) { this.svc = svc; }
        @Override
        public boolean onError(MediaPlayer mp, int what, int extra) {
            Log.e(TAG, "MediaPlayer error what=" + what + " extra=" + extra);
            svc.setErrorState("playback error " + what);
            return true;
        }
    }

    private static class OnCompletionListenerImpl implements MediaPlayer.OnCompletionListener {
        private final ShimMediaBrowserService svc;
        OnCompletionListenerImpl(ShimMediaBrowserService svc) { this.svc = svc; }
        @Override
        public void onCompletion(MediaPlayer mp) {
            Log.i(TAG, "MediaPlayer completion");
            svc.setIdleState();
            svc.stopForeground(STOP_FOREGROUND_REMOVE);
        }
    }

    private void resume() {
        if (player == null) return;
        player.start();
        setPlayingState(player.getCurrentPosition(), player.getDuration());
    }

    private void pause() {
        if (player == null) return;
        player.pause();
        setPausedState(player.getCurrentPosition(), player.getDuration());
    }

    private void stopPlayback() {
        teardownPlayer();
        setIdleState();
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private void teardownPlayer() {
        if (player != null) {
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        if (currentSource != null) {
            try { currentSource.close(); } catch (Exception ignored) {}
            currentSource = null;
        }
        abandonFocus();
    }

    private boolean requestFocus() {
        if (audioManager == null) audioManager = getSystemService(AudioManager.class);
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AUDIO_ATTRS)
                .setOnAudioFocusChangeListener(new FocusListener(this), main)
                .build();
        int res = audioManager.requestAudioFocus(focusRequest);
        return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        if (audioManager != null && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
        }
    }

    private static class FocusListener implements AudioManager.OnAudioFocusChangeListener {
        private final ShimMediaBrowserService svc;
        FocusListener(ShimMediaBrowserService svc) { this.svc = svc; }
        @Override
        public void onAudioFocusChange(int focusChange) {
            Log.i(TAG, "audio focus change: " + focusChange);
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_LOSS:
                    svc.stopPlayback();
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    svc.pause();
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    svc.resume();
                    break;
                default:
                    break;
            }
        }
    }

    // ---- state updates ----

    private void setIdleState() {
        // Use STATE_PAUSED + the full set of "play from X" actions so AA's
        // MediaController-side check treats the session as ready-to-be-driven.
        PlaybackState state = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_FROM_SEARCH
                        | PlaybackState.ACTION_PLAY_FROM_URI
                        | PlaybackState.ACTION_PLAY_FROM_MEDIA_ID
                        | PlaybackState.ACTION_PREPARE_FROM_SEARCH
                        | PlaybackState.ACTION_PREPARE_FROM_URI
                        | PlaybackState.ACTION_PREPARE_FROM_MEDIA_ID
                        | PlaybackState.ACTION_PLAY
                        | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_STOP)
                .setState(PlaybackState.STATE_PAUSED, 0, 1.0f)
                .build();
        session.setPlaybackState(state);
    }

    private void setBufferingState() {
        PlaybackState state = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_STOP
                        | PlaybackState.ACTION_PLAY_FROM_SEARCH)
                .setState(PlaybackState.STATE_BUFFERING, 0, 1.0f)
                .build();
        session.setPlaybackState(state);
    }

    private void setPlayingState(long position, long duration) {
        PlaybackState state = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_STOP
                        | PlaybackState.ACTION_PLAY_FROM_SEARCH
                        | PlaybackState.ACTION_SEEK_TO)
                .setState(PlaybackState.STATE_PLAYING, position, 1.0f)
                .build();
        session.setPlaybackState(state);
    }

    private void setPausedState(long position, long duration) {
        PlaybackState state = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY
                        | PlaybackState.ACTION_STOP
                        | PlaybackState.ACTION_PLAY_FROM_SEARCH
                        | PlaybackState.ACTION_SEEK_TO)
                .setState(PlaybackState.STATE_PAUSED, position, 0.0f)
                .build();
        session.setPlaybackState(state);
    }

    private void setErrorState(String msg) {
        Log.e(TAG, "error state: " + msg);
        PlaybackState state = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_FROM_SEARCH)
                .setState(PlaybackState.STATE_ERROR, 0, 1.0f)
                .build();
        session.setPlaybackState(state);
    }

    private void updateMetadata(DeezerClient.Track track) {
        MediaMetadata.Builder b = new MediaMetadata.Builder();
        b.putString(MediaMetadata.METADATA_KEY_TITLE, track.title);
        b.putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist);
        b.putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, track.title);
        b.putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, track.artist);
        b.putString(MediaMetadata.METADATA_KEY_MEDIA_ID, track.id);
        session.setMetadata(b.build());
    }

    // ---- helpers ----

    String getArl() {
        SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return p.getString(PREF_ARL, null);
    }

    static void saveArl(Context ctx, String arl) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(PREF_ARL, arl).apply();
    }

    private void ensureChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private void startForegroundIfNeeded(DeezerClient.Track track) {
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(track.title)
                .setContentText(track.artist)
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()))
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            Log.w(TAG, "startForeground failed", e);
        }
    }

    private static byte[] blowfishKey(String trackId) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(trackId.getBytes("ASCII"));
        char[] hex = new char[32];
        char[] alpha = "0123456789abcdef".toCharArray();
        for (int i = 0; i < 16; i++) {
            hex[i * 2]     = alpha[(digest[i] >> 4) & 0xf];
            hex[i * 2 + 1] = alpha[digest[i] & 0xf];
        }
        byte[] key = new byte[16];
        for (int i = 0; i < 16; i++) {
            key[i] = (byte) (hex[i] ^ hex[i + 16] ^ MAGIC[i]);
        }
        return key;
    }
}
