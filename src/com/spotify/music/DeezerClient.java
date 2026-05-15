package com.spotify.music;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Deezer streaming client.
 *
 * Mirrors the Python spike protocol. Authenticates with an ARL cookie,
 * resolves a search query to a track, fetches the encrypted stream URL,
 * and decrypts Blowfish-CBC-encrypted 2048-byte chunks (every 3rd chunk).
 */
public class DeezerClient {
    private static final String TAG = "DeezerShim";
    private static final String GW_LIGHT = "https://www.deezer.com/ajax/gw-light.php";
    private static final String MEDIA_URL = "https://media.deezer.com/v1/get_url";
    private static final String PUBLIC_API = "https://api.deezer.com";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/120.0.0.0 Safari/537.36";
    private static final byte[] MAGIC = "g4el58wc0zvf9na1".getBytes();
    private static final byte[] IV =
            new byte[]{0, 1, 2, 3, 4, 5, 6, 7};

    public static class Track {
        public final String id;
        public final String title;
        public final String artist;

        public Track(String id, String title, String artist) {
            this.id = id;
            this.title = title;
            this.artist = artist;
        }
    }

    public static class StreamInfo {
        public final String url;
        public final String format;
        public final long contentLength;

        public StreamInfo(String url, String format, long contentLength) {
            this.url = url;
            this.format = format;
            this.contentLength = contentLength;
        }
    }

    private final String arl;
    private String apiToken;
    private String licenseToken;
    private long userId;
    private boolean hifi;
    private boolean lossless;

    public DeezerClient(String arl) {
        this.arl = arl;
        // HttpURLConnection only consults the default CookieHandler — install one
        // so we accept and re-send session cookies set by Deezer between calls.
        CookieManager cm = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        CookieHandler.setDefault(cm);
        try {
            HttpCookie cookie = new HttpCookie("arl", arl);
            cookie.setDomain(".deezer.com");
            cookie.setPath("/");
            cookie.setVersion(0);
            cm.getCookieStore().add(URI.create("https://www.deezer.com/"), cookie);
        } catch (Exception e) {
            Log.w(TAG, "could not seed ARL cookie", e);
        }
    }

    public long getUserId() { return userId; }
    public boolean isHifi() { return hifi; }
    public boolean isLossless() { return lossless; }

    public void authenticate() throws IOException {
        JSONObject results = gwPost("deezer.getUserData", new JSONObject(), "");
        try {
            apiToken = results.getString("checkForm");
            JSONObject user = results.getJSONObject("USER");
            userId = user.getLong("USER_ID");
            JSONObject options = user.getJSONObject("OPTIONS");
            licenseToken = options.getString("license_token");
            hifi = options.optBoolean("web_hq", false);
            lossless = options.optBoolean("web_lossless", false);
            if (userId == 0) {
                throw new IOException("ARL invalid or expired (USER_ID=0)");
            }
            Log.i(TAG, "auth ok USER_ID=" + userId
                    + " hifi=" + hifi + " lossless=" + lossless);
        } catch (Exception e) {
            throw new IOException("auth parse failed: " + e.getMessage(), e);
        }
    }

    public Track searchTrack(String query) throws IOException {
        String url = PUBLIC_API + "/search?q="
                + URLEncoder.encode(query, "UTF-8") + "&limit=1";
        HttpURLConnection c = open(url);
        c.setRequestMethod("GET");
        String body = readAll(c);
        try {
            JSONObject root = new JSONObject(body);
            JSONArray data = root.getJSONArray("data");
            if (data.length() == 0) throw new IOException("no results");
            JSONObject t = data.getJSONObject(0);
            return new Track(
                    String.valueOf(t.getLong("id")),
                    t.getString("title"),
                    t.getJSONObject("artist").getString("name"));
        } catch (Exception e) {
            throw new IOException("search parse failed: " + e.getMessage(), e);
        }
    }

    public StreamInfo getStreamInfo(String trackId) throws IOException {
        // 1) song.getData -> TRACK_TOKEN
        JSONObject payload = new JSONObject();
        try {
            payload.put("sng_id", trackId);
        } catch (Exception e) {
            throw new IOException(e);
        }
        JSONObject song = gwPost("song.getData", payload, apiToken);
        String trackToken;
        try {
            trackToken = song.getString("TRACK_TOKEN");
        } catch (Exception e) {
            throw new IOException("song.getData: no TRACK_TOKEN", e);
        }

        // 2) media.getUrl -> stream URL
        JSONObject mediaBody;
        try {
            JSONArray formats = new JSONArray();
            formats.put(jsonFmt("BF_CBC_STRIPE", "FLAC"));
            formats.put(jsonFmt("BF_CBC_STRIPE", "MP3_320"));
            formats.put(jsonFmt("BF_CBC_STRIPE", "MP3_128"));
            JSONObject mediaSpec = new JSONObject();
            mediaSpec.put("type", "FULL");
            mediaSpec.put("formats", formats);
            JSONArray mediaArr = new JSONArray();
            mediaArr.put(mediaSpec);
            JSONArray tokens = new JSONArray();
            tokens.put(trackToken);
            mediaBody = new JSONObject();
            mediaBody.put("license_token", licenseToken);
            mediaBody.put("media", mediaArr);
            mediaBody.put("track_tokens", tokens);
        } catch (Exception e) {
            throw new IOException(e);
        }

        HttpURLConnection c = open(MEDIA_URL);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        OutputStream out = c.getOutputStream();
        out.write(mediaBody.toString().getBytes("UTF-8"));
        out.close();

        String body = readAll(c);
        try {
            JSONObject root = new JSONObject(body);
            JSONArray dataArr = root.getJSONArray("data");
            if (dataArr.length() == 0) throw new IOException("get_url: empty data");
            JSONObject data0 = dataArr.getJSONObject(0);
            if (data0.has("errors")) {
                throw new IOException("get_url errors: " + data0.getJSONArray("errors"));
            }
            JSONObject chosen = data0.getJSONArray("media").getJSONObject(0);
            String fmt = chosen.getString("format");
            String url = chosen.getJSONArray("sources")
                    .getJSONObject(0).getString("url");

            // HEAD to discover Content-Length (needed to pre-size the streaming buffer)
            HttpURLConnection head = (HttpURLConnection) new URL(url).openConnection();
            head.setRequestMethod("HEAD");
            head.setConnectTimeout(10000);
            head.setReadTimeout(10000);
            long len = head.getContentLengthLong();
            head.disconnect();
            Log.i(TAG, "stream format=" + fmt + " bytes=" + len);
            return new StreamInfo(url, fmt, len);
        } catch (Exception e) {
            throw new IOException("get_url parse failed: " + e.getMessage()
                    + " body=" + body, e);
        }
    }

    /**
     * Stream + decrypt a track to the given OutputStream.
     * Every 3rd 2048-byte chunk is Blowfish-CBC encrypted.
     */
    public void streamDecrypt(StreamInfo info, String trackId, OutputStream out)
            throws IOException {
        byte[] key = blowfishKey(trackId);
        HttpURLConnection c = open(info.url);
        c.setRequestMethod("GET");
        InputStream in = c.getInputStream();
        byte[] buf = new byte[2048];
        int chunkIdx = 0;
        int totalBytes = 0;
        try {
            while (true) {
                int filled = readFully(in, buf, 2048);
                if (filled == 0) break;
                if (filled == 2048 && chunkIdx % 3 == 0) {
                    Cipher cipher = Cipher.getInstance("Blowfish/CBC/NoPadding");
                    cipher.init(Cipher.DECRYPT_MODE,
                            new SecretKeySpec(key, "Blowfish"),
                            new IvParameterSpec(IV));
                    byte[] decrypted = cipher.doFinal(buf);
                    out.write(decrypted, 0, filled);
                } else {
                    out.write(buf, 0, filled);
                }
                totalBytes += filled;
                chunkIdx++;
                if (filled < 2048) break;
            }
        } catch (Exception e) {
            throw new IOException("decrypt/stream failed at chunk " + chunkIdx
                    + ": " + e.getMessage(), e);
        }
        Log.i(TAG, "wrote " + totalBytes + " bytes in " + chunkIdx + " chunks");
    }

    // ---- private helpers ----

    private static JSONObject jsonFmt(String cipher, String format) throws Exception {
        JSONObject o = new JSONObject();
        o.put("cipher", cipher);
        o.put("format", format);
        return o;
    }

    private JSONObject gwPost(String method, JSONObject body, String token)
            throws IOException {
        String url = GW_LIGHT
                + "?method=" + URLEncoder.encode(method, "UTF-8")
                + "&input=3&api_version=1.0&api_token=" + URLEncoder.encode(token, "UTF-8");
        HttpURLConnection c = open(url);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        OutputStream os = c.getOutputStream();
        os.write(body.toString().getBytes("UTF-8"));
        os.close();
        String resp = readAll(c);
        try {
            JSONObject root = new JSONObject(resp);
            Object err = root.opt("error");
            if (err != null && !(err instanceof JSONArray && ((JSONArray) err).length() == 0)
                    && !err.toString().equals("[]") && !err.toString().equals("{}")) {
                throw new IOException("gw error on " + method + ": " + err);
            }
            return root.getJSONObject("results");
        } catch (Exception e) {
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("gw parse failed on " + method + ": "
                    + e.getMessage() + " body=" + resp, e);
        }
    }

    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", USER_AGENT);
        // Cookies (including arl + any session cookies returned by Deezer) are
        // attached automatically by the installed CookieManager.
        return c;
    }

    private static String readAll(HttpURLConnection c) throws IOException {
        InputStream in = c.getResponseCode() >= 400
                ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "";
        BufferedReader br = new BufferedReader(
                new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    private static int readFully(InputStream in, byte[] buf, int len)
            throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) break;
            off += n;
        }
        return off;
    }

    private static byte[] blowfishKey(String trackId) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(trackId.getBytes("ASCII"));
            // hex string of the digest (32 chars)
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
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
