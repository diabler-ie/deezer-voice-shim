package com.spotify.music;

import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.service.media.MediaBrowserService;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class ShimMediaBrowserService extends MediaBrowserService {
    private static final String TAG = "DeezerShim";
    private static final String MEDIA_ROOT_ID = "shim_root";
    private MediaSession session;

    public static class ShimCallback extends MediaSession.Callback {
        @Override
        public void onPlayFromSearch(String query, Bundle extras) {
            Log.i(TAG, "===== onPlayFromSearch =====");
            Log.i(TAG, "query=\"" + query + "\"");
            if (extras != null) {
                for (String key : extras.keySet()) {
                    Log.i(TAG, "  extra " + key + " = " + extras.get(key));
                }
            } else {
                Log.i(TAG, "  (no extras)");
            }
            Log.i(TAG, "===== end =====");
        }

        @Override
        public void onPlay() {
            Log.i(TAG, "onPlay");
        }

        @Override
        public void onPause() {
            Log.i(TAG, "onPause");
        }

        @Override
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            Log.i(TAG, "onPlayFromMediaId mediaId=" + mediaId);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service onCreate");
        session = new MediaSession(this, "DeezerShim");
        session.setCallback(new ShimCallback());
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        PlaybackState state = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_FROM_SEARCH
                        | PlaybackState.ACTION_PLAY
                        | PlaybackState.ACTION_PLAY_FROM_MEDIA_ID
                        | PlaybackState.ACTION_PAUSE)
                .setState(PlaybackState.STATE_NONE, 0, 1.0f)
                .build();
        session.setPlaybackState(state);
        session.setActive(true);
        setSessionToken(session.getSessionToken());
        Log.i(TAG, "Session activated");
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        Log.i(TAG, "onGetRoot from clientPackage=" + clientPackageName + " uid=" + clientUid);
        return new BrowserRoot(MEDIA_ROOT_ID, null);
    }

    @Override
    public void onLoadChildren(String parentId,
                               Result<List<MediaBrowser.MediaItem>> result) {
        Log.i(TAG, "onLoadChildren parentId=" + parentId);
        result.sendResult(new ArrayList<MediaBrowser.MediaItem>());
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Service onDestroy");
        if (session != null) session.release();
        super.onDestroy();
    }
}
