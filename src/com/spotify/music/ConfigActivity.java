package com.spotify.music;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Minimal in-app config for the Deezer ARL cookie. Launched from the
 * launcher; lets the user paste/replace their ARL without an adb dance.
 */
public class ConfigActivity extends Activity {
    private EditText arlField;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Deezer Voice Shim — config");
        title.setTextSize(20);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        TextView help = new TextView(this);
        help.setText(
                "Paste your Deezer ARL cookie below.\n\n"
                + "How to get it: log into deezer.com in a browser, "
                + "open DevTools → Application/Storage → Cookies "
                + "→ www.deezer.com → 'arl' value. Treat it like "
                + "a password.");
        help.setPadding(0, 0, 0, dp(12));
        root.addView(help);

        arlField = new EditText(this);
        arlField.setHint("arl=...");
        arlField.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        arlField.setSingleLine(false);
        arlField.setMaxLines(4);
        arlField.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(arlField);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(12), 0, dp(12));

        Button save = new Button(this);
        save.setText("Save");
        save.setOnClickListener(new SaveClick(this));
        buttons.addView(save);

        Button clear = new Button(this);
        clear.setText("Clear");
        clear.setOnClickListener(new ClearClick(this));
        buttons.addView(clear);

        root.addView(buttons);

        statusView = new TextView(this);
        statusView.setMovementMethod(new ScrollingMovementMethod());
        statusView.setGravity(Gravity.START);
        root.addView(statusView);

        setContentView(root);
        refreshStatus();
    }

    private void refreshStatus() {
        String arl = ShimMediaBrowserService.readArl(this);
        if (arl == null || arl.isEmpty()) {
            statusView.setText("Status: no ARL configured");
            arlField.setText("");
        } else {
            int len = arl.length();
            String masked = arl.substring(0, Math.min(6, len)) + "..."
                    + arl.substring(Math.max(0, len - 4));
            statusView.setText("Status: ARL set (" + len + " chars, " + masked + ")");
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private static class SaveClick implements View.OnClickListener {
        private final ConfigActivity a;
        SaveClick(ConfigActivity a) { this.a = a; }
        @Override
        public void onClick(View v) {
            String value = a.arlField.getText().toString().trim();
            if (value.isEmpty()) {
                Toast.makeText(a, "ARL is empty", Toast.LENGTH_SHORT).show();
                return;
            }
            ShimMediaBrowserService.saveArl(a, value);
            Toast.makeText(a, "ARL saved", Toast.LENGTH_SHORT).show();
            a.refreshStatus();
        }
    }

    private static class ClearClick implements View.OnClickListener {
        private final ConfigActivity a;
        ClearClick(ConfigActivity a) { this.a = a; }
        @Override
        public void onClick(View v) {
            ShimMediaBrowserService.saveArl(a, "");
            Toast.makeText(a, "ARL cleared", Toast.LENGTH_SHORT).show();
            a.refreshStatus();
        }
    }
}
