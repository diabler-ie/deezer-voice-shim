package com.spotify.music;

import android.media.MediaDataSource;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * MediaDataSource that streams a Blowfish-CBC-striped Deezer track and
 * decrypts chunks on the fly. Backed by an in-memory buffer sized to the
 * remote Content-Length; a producer thread fills the buffer while reads
 * block on insufficient progress.
 */
public class StreamingMediaDataSource extends MediaDataSource {
    private static final String TAG = "DeezerShim";
    private static final byte[] IV = new byte[]{0, 1, 2, 3, 4, 5, 6, 7};
    private static final int CHUNK = 2048;

    private final String url;
    private final byte[] key;
    private final long contentLength;
    private final byte[] buf;
    private final Object lock = new Object();
    private volatile long downloaded = 0;
    private volatile boolean done = false;
    private volatile IOException error = null;
    private volatile boolean closed = false;

    public StreamingMediaDataSource(String url, byte[] key, long contentLength) {
        this.url = url;
        this.key = key;
        this.contentLength = contentLength;
        if (contentLength <= 0 || contentLength > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("invalid content length: " + contentLength);
        }
        this.buf = new byte[(int) contentLength];
        new Thread(new ProducerRunnable(this), "DeezerStreamer").start();
    }

    @Override
    public int readAt(long position, byte[] dst, int offset, int size) throws IOException {
        if (closed) return -1;
        if (position >= contentLength) return -1;

        long need = Math.min(position + size, contentLength);
        synchronized (lock) {
            while (downloaded < need && !done && error == null && !closed) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
            if (error != null) throw error;
            if (closed) return -1;
        }
        int available = (int) Math.min(size, contentLength - position);
        System.arraycopy(buf, (int) position, dst, offset, available);
        return available;
    }

    @Override
    public long getSize() {
        return contentLength;
    }

    @Override
    public void close() {
        closed = true;
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    private static class ProducerRunnable implements Runnable {
        private final StreamingMediaDataSource ds;

        ProducerRunnable(StreamingMediaDataSource ds) {
            this.ds = ds;
        }

        @Override
        public void run() {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(ds.url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                InputStream in = c.getInputStream();
                byte[] chunk = new byte[CHUNK];
                int chunkIdx = 0;
                long pos = 0;
                while (!ds.closed) {
                    int filled = readFully(in, chunk, CHUNK);
                    if (filled == 0) break;
                    if (filled == CHUNK && chunkIdx % 3 == 0) {
                        Cipher cipher = Cipher.getInstance("Blowfish/CBC/NoPadding");
                        cipher.init(Cipher.DECRYPT_MODE,
                                new SecretKeySpec(ds.key, "Blowfish"),
                                new IvParameterSpec(IV));
                        byte[] decrypted = cipher.doFinal(chunk);
                        System.arraycopy(decrypted, 0, ds.buf, (int) pos, filled);
                    } else {
                        System.arraycopy(chunk, 0, ds.buf, (int) pos, filled);
                    }
                    pos += filled;
                    synchronized (ds.lock) {
                        ds.downloaded = pos;
                        ds.lock.notifyAll();
                    }
                    chunkIdx++;
                    if (filled < CHUNK) break;
                }
                synchronized (ds.lock) {
                    ds.done = true;
                    ds.lock.notifyAll();
                }
                Log.i(TAG, "stream complete: " + pos + " / " + ds.contentLength);
            } catch (Exception e) {
                Log.e(TAG, "stream producer failed", e);
                synchronized (ds.lock) {
                    ds.error = (e instanceof IOException)
                            ? (IOException) e
                            : new IOException(e);
                    ds.lock.notifyAll();
                }
            }
        }

        private static int readFully(InputStream in, byte[] buf, int len) throws IOException {
            int off = 0;
            while (off < len) {
                int n = in.read(buf, off, len - off);
                if (n < 0) break;
                off += n;
            }
            return off;
        }
    }
}
