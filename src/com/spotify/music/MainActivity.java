package com.spotify.music;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    private static final String TAG = "DeezerShim";
    private static final String DEEZER_PKG = "deezer.android.app";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        Bundle extras = intent.getExtras();

        Log.i(TAG, "MainActivity launched action=" + intent.getAction()
                + " data=" + intent.getData());

        if (!Intent.ACTION_VIEW.equals(intent.getAction()) || extras == null) {
            finish();
            return;
        }

        final String artist = extras.getString("android.intent.extra.artist");
        final String title = extras.getString("android.intent.extra.title");
        final String album = extras.getString("android.intent.extra.album");
        final String focus = extras.getString("android.intent.extra.focus");

        if (artist != null && title != null) {
            resolveAndPlayTrack(artist, title);
            // finish() called from resolver on main thread
        } else {
            // No specific track — fall back to opening a search deep link
            launchSearchDeepLink(artist, title, album, focus);
            finish();
        }
    }

    private void resolveAndPlayTrack(final String artist, final String title) {
        new Thread(new ResolveRunnable(this, artist, title)).start();
    }

    private static class ResolveRunnable implements Runnable {
        private final MainActivity activity;
        private final String artist;
        private final String title;

        ResolveRunnable(MainActivity activity, String artist, String title) {
            this.activity = activity;
            this.artist = artist;
            this.title = title;
        }

        @Override
        public void run() {
            Long trackId = null;
            try {
                Uri u = new Uri.Builder()
                        .scheme("https")
                        .authority("api.deezer.com")
                        .appendPath("search")
                        .appendQueryParameter("q",
                                "artist:\"" + artist + "\" track:\"" + title + "\"")
                        .appendQueryParameter("limit", "1")
                        .build();
                Log.i(TAG, "Deezer API: " + u);
                HttpURLConnection conn = (HttpURLConnection) new URL(u.toString())
                        .openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                int code = conn.getResponseCode();
                Log.i(TAG, "API status=" + code);
                if (code == 200) {
                    InputStream in = conn.getInputStream();
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(in, "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    JSONObject root = new JSONObject(sb.toString());
                    JSONArray data = root.optJSONArray("data");
                    if (data != null && data.length() > 0) {
                        trackId = data.getJSONObject(0).getLong("id");
                        String resolvedTitle = data.getJSONObject(0)
                                .optString("title", "?");
                        JSONObject ar = data.getJSONObject(0).optJSONObject("artist");
                        String resolvedArtist = ar == null ? "?" : ar.optString("name", "?");
                        Log.i(TAG, "resolved trackId=" + trackId
                                + " title=" + resolvedTitle
                                + " artist=" + resolvedArtist);
                    } else {
                        Log.w(TAG, "no API results");
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "resolve failed", e);
            }

            final Long finalTrackId = trackId;
            new Handler(Looper.getMainLooper())
                    .post(new PostResolveRunnable(activity, artist, title, finalTrackId));
        }
    }

    private static class PostResolveRunnable implements Runnable {
        private final MainActivity activity;
        private final String artist;
        private final String title;
        private final Long trackId;

        PostResolveRunnable(MainActivity activity, String artist, String title, Long trackId) {
            this.activity = activity;
            this.artist = artist;
            this.title = title;
            this.trackId = trackId;
        }

        @Override
        public void run() {
            if (trackId != null) {
                activity.playResolvedTrack(trackId);
            } else {
                activity.launchSearchDeepLink(artist, title, null, null);
            }
            activity.finish();
        }
    }

    private void playResolvedTrack(long trackId) {
        // Try Deezer's standard track URL — should open + play
        Uri uri = Uri.parse("deezer://www.deezer.com/track/" + trackId
                + "?autoplay=true");
        Log.i(TAG, "playResolvedTrack: " + uri);
        Intent fwd = new Intent(Intent.ACTION_VIEW, uri);
        fwd.setPackage(DEEZER_PKG);
        fwd.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(fwd);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "Deezer can't handle " + uri, e);
        }
    }

    private void launchSearchDeepLink(String artist, String title, String album,
                                      String focus) {
        StringBuilder q = new StringBuilder();
        if (title != null) q.append(title);
        if (artist != null) {
            if (q.length() > 0) q.append(' ');
            q.append(artist);
        }
        if (q.length() == 0 && album != null) q.append(album);
        String query = q.toString().trim();
        if (query.isEmpty()) {
            Log.w(TAG, "no search material");
            return;
        }
        String type = "track";
        if ("vnd.android.cursor.item/artist".equals(focus)) type = "artist";
        else if ("vnd.android.cursor.item/album".equals(focus)) type = "album";
        else if ("vnd.android.cursor.item/playlist".equals(focus)) type = "playlist";

        Uri uri = new Uri.Builder()
                .scheme("deezer-query")
                .authority("www.deezer.com")
                .appendPath(type)
                .appendQueryParameter("query", query)
                .appendQueryParameter("referrer", "shim")
                .build();
        Log.i(TAG, "launchSearchDeepLink: " + uri);

        Intent fwd = new Intent(Intent.ACTION_VIEW, uri);
        fwd.setPackage(DEEZER_PKG);
        fwd.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(fwd);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "Deezer can't handle " + uri, e);
        }
    }
}
