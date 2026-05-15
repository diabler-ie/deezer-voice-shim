package com.spotify.music;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Loads Deezer's login page in an embedded WebView. The user signs in there
 * (so captcha / MFA / device verification go through Deezer's own UI), and
 * once Deezer sets the `arl` session cookie we read it out of CookieManager
 * and persist it via the same path as the manual paste flow. The user's
 * password is never seen by this app — we only harvest the resulting cookie.
 */
public class DeezerLoginActivity extends Activity {
    static final String TAG = "DeezerLogin";
    private static final String LOGIN_URL = "https://www.deezer.com/login";
    static final String COOKIE_HOST = "https://www.deezer.com";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView banner = new TextView(this);
        int pad = (int) (8 * getResources().getDisplayMetrics().density);
        banner.setPadding(pad, pad, pad, pad);
        banner.setText("Sign in to Deezer — your ARL will be captured automatically.");
        root.addView(banner);

        // Force a fresh login flow so "Sign in" always means "sign in," not
        // "import whatever session this WebView happened to have." Users who
        // want to keep an existing browser session can still paste the ARL
        // manually on the config screen.
        CookieManager cookies = CookieManager.getInstance();
        cookies.removeAllCookies(null);
        cookies.flush();

        WebView webView = new WebView(this);
        webView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new ArlSniffer(this));
        webView.loadUrl(LOGIN_URL);
        root.addView(webView);
        setContentView(root);
    }

    static String parseArl(String cookieHeader) {
        for (String pair : cookieHeader.split(";")) {
            String p = pair.trim();
            if (p.startsWith("arl=")) {
                return p.substring(4);
            }
        }
        return null;
    }

    private static class ArlSniffer extends WebViewClient {
        private final DeezerLoginActivity host;
        private boolean captured = false;

        ArlSniffer(DeezerLoginActivity host) {
            this.host = host;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (captured) return;
            String header = CookieManager.getInstance().getCookie(COOKIE_HOST);
            if (header == null) return;
            String arl = parseArl(header);
            if (arl == null || arl.isEmpty()) return;
            captured = true;
            Log.i(TAG, "captured arl from cookie (" + arl.length() + " chars)");
            ShimMediaBrowserService.saveArl(host, arl);
            Toast.makeText(host, "Signed in. ARL saved.", Toast.LENGTH_LONG).show();
            host.finish();
        }
    }
}
