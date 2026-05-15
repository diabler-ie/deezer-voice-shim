package com.spotify.music;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

/**
 * Test runner for Deezer streaming.
 *
 *   adb shell am start -n com.spotify.music/.DeezerTestActivity \
 *       --es arl "$DEEZER_ARL" --es query "bohemian rhapsody queen" --es mode play
 *
 * modes: "save" (default, writes a .flac to external files dir)
 *        "play" (streams + decrypts on the fly, plays via MediaPlayer)
 */
public class DeezerTestActivity extends Activity {
    private static final String TAG = "DeezerShim";
    private static final byte[] MAGIC = "g4el58wc0zvf9na1".getBytes();

    private MediaPlayer player;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        final String arl = intent.getStringExtra("arl");
        final String query = intent.getStringExtra("query");
        final String mode = intent.getStringExtra("mode");
        if (arl == null) {
            Log.e(TAG, "Missing --es arl=<value>");
            finish();
            return;
        }
        // Persist ARL so ShimMediaBrowserService can use it later
        ShimMediaBrowserService.saveArl(this, arl);
        Log.i(TAG, "ARL saved to prefs");
        if (query == null) {
            // Just configuring the ARL; no streaming test
            finish();
            return;
        }
        new Thread(new TestRunnable(this, arl, query, mode)).start();
    }

    @Override
    protected void onDestroy() {
        if (player != null) {
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        super.onDestroy();
    }

    private static class TestRunnable implements Runnable {
        private final DeezerTestActivity activity;
        private final String arl;
        private final String query;
        private final String mode;

        TestRunnable(DeezerTestActivity activity, String arl, String query, String mode) {
            this.activity = activity;
            this.arl = arl;
            this.query = query;
            this.mode = mode == null ? "save" : mode;
        }

        @Override
        public void run() {
            try {
                Log.i(TAG, "===== Deezer test starting (mode=" + mode + ") =====");
                DeezerClient client = new DeezerClient(arl);

                Log.i(TAG, "[1/4] authenticate");
                client.authenticate();

                Log.i(TAG, "[2/4] search q=" + query);
                DeezerClient.Track track = client.searchTrack(query);
                Log.i(TAG, "      track_id=" + track.id
                        + " title='" + track.title + "' artist='" + track.artist + "'");

                Log.i(TAG, "[3/4] resolve stream URL");
                DeezerClient.StreamInfo info = client.getStreamInfo(track.id);

                if ("play".equals(mode)) {
                    Log.i(TAG, "[4/4] streaming + playing live");
                    activity.playStreaming(info, track.id);
                    // do not finish() — we want to keep playing
                } else {
                    File outDir = activity.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
                    outDir.mkdirs();
                    String ext = "FLAC".equals(info.format) ? "flac" : "mp3";
                    String safe = sanitize(track.artist + " - " + track.title);
                    File outFile = new File(outDir, safe + "." + ext);
                    Log.i(TAG, "[4/4] streaming + decrypting to " + outFile);
                    OutputStream out = new FileOutputStream(outFile);
                    try {
                        client.streamDecrypt(info, track.id, out);
                    } finally {
                        out.close();
                    }
                    Log.i(TAG, "===== done: " + outFile.length() + " bytes =====");
                    activity.finish();
                }
            } catch (Exception e) {
                Log.e(TAG, "test failed", e);
                activity.finish();
            }
        }
    }

    private void playStreaming(DeezerClient.StreamInfo info, String trackId)
            throws Exception {
        byte[] key = blowfishKey(trackId);
        StreamingMediaDataSource ds =
                new StreamingMediaDataSource(info.url, key, info.contentLength);
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        player.setDataSource(ds);
        player.setOnPreparedListener(new OnPreparedListener());
        player.setOnErrorListener(new OnErrorListener());
        player.setOnCompletionListener(new OnCompletionListener(this));
        Log.i(TAG, "prepareAsync");
        player.prepareAsync();
    }

    private static class OnPreparedListener implements MediaPlayer.OnPreparedListener {
        @Override
        public void onPrepared(MediaPlayer mp) {
            Log.i(TAG, "MediaPlayer prepared, starting");
            mp.start();
        }
    }

    private static class OnErrorListener implements MediaPlayer.OnErrorListener {
        @Override
        public boolean onError(MediaPlayer mp, int what, int extra) {
            Log.e(TAG, "MediaPlayer error what=" + what + " extra=" + extra);
            return true;
        }
    }

    private static class OnCompletionListener implements MediaPlayer.OnCompletionListener {
        private final DeezerTestActivity activity;
        OnCompletionListener(DeezerTestActivity a) { this.activity = a; }
        @Override
        public void onCompletion(MediaPlayer mp) {
            Log.i(TAG, "MediaPlayer completion");
            activity.finish();
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

    private static String sanitize(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }
}
