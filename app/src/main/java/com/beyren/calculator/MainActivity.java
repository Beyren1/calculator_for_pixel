package com.beyren.calculator;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.view.View;
import android.view.Window;
import android.net.Uri;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import android.provider.MediaStore;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONObject;

import java.io.File;

public class MainActivity extends Activity {
    private static final String START_URL = "https://appassets.androidplatform.net/assets/index.html";
    private WebView web;
    private android.content.SharedPreferences prefs;
    private static final int REQ_CAMERA = 1, REQ_PICK = 2;
    private Uri cameraUri;
    private boolean pageReady = false;
    private String pendingJs = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Serve the bundled page from a https origin so localStorage and the page's APIs work as on the web.
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        // Paint the window and the WebView in the page's own background right away,
        // and show the WebView only once the page has drawn, so there is no white flash.
        prefs = getSharedPreferences("calc", MODE_PRIVATE);
        int bg = applyBars(prefs.getString("theme", "auto"));
        web = new WebView(this);
        web.setBackgroundColor(bg);
        web.setVisibility(View.INVISIBLE);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                view.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.setVisibility(View.VISIBLE);
                pageReady = true;
                if (pendingJs != null) { view.evaluateJavascript(pendingJs, null); pendingJs = null; }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("appassets.androidplatform.net".equals(uri.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) { }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        if (savedInstanceState != null && savedInstanceState.getString("cameraUri") != null)
            cameraUri = Uri.parse(savedInstanceState.getString("cameraUri"));
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START_URL);
        if (savedInstanceState == null) handleShare(getIntent());
    }

    /** Back closes an open drawer (history or settings) first, then leaves the app. */
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.calcBack ? calcBack() : false", result -> {
            if (!"true".equals(result)) MainActivity.super.onBackPressed();
        });
    }

    /** Match the system bars to the theme chosen inside the calculator. */
    private int applyBars(String theme) {
        boolean dark;
        if ("dark".equals(theme)) dark = true;
        else if ("light".equals(theme)) dark = false;
        else dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int bg = dark ? Color.parseColor("#101318") : Color.parseColor("#F8F9FF");
        Window w = getWindow();
        w.setStatusBarColor(bg);
        w.setNavigationBarColor(bg);
        w.getDecorView().setBackgroundColor(bg);
        int flags = w.getDecorView().getSystemUiVisibility();
        int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        flags = dark ? (flags & ~light) : (flags | light);
        w.getDecorView().setSystemUiVisibility(flags);
        if (web != null) web.setBackgroundColor(bg);
        return bg;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
        if (cameraUri != null) outState.putString("cameraUri", cameraUri.toString());
    }

    /** Photo of a problem: open the camera or the gallery, then read the text on the device. */
    private void startPhoto(String source) {
        try {
            if ("gallery".equals(source)) {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("image/*");
                startActivityForResult(Intent.createChooser(i, "Фото задачи"), REQ_PICK);
            } else {
                File f = new File(getCacheDir(), "problem.jpg");
                cameraUri = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
                Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                i.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
                i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(i, REQ_CAMERA);
            }
        } catch (Exception e) {
            sendPhotoText(null, "Не удалось открыть камеру");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAMERA && requestCode != REQ_PICK) return;
        if (resultCode != RESULT_OK) { sendPhotoText(null, "cancel"); return; }
        Uri uri = requestCode == REQ_CAMERA ? cameraUri : (data != null ? data.getData() : null);
        if (uri == null) { sendPhotoText(null, "Фото не получено"); return; }
        recognize(uri);
    }

    /** Read the text on a photo on the device (ML Kit, offline) and hand it to the page. */
    private void recognize(Uri uri) {
        try {
            InputImage img = InputImage.fromFilePath(this, uri);
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(img)
                    .addOnSuccessListener(t -> sendPhotoText(t.getText(), null))
                    .addOnFailureListener(e -> sendPhotoText(null, "Не получилось распознать текст"));
        } catch (Exception e) {
            sendPhotoText(null, "Не удалось открыть фото");
        }
    }

    private void sendPhotoText(String text, String error) {
        final String js = "window.onPhotoText&&onPhotoText(" + (text == null ? "null" : JSONObject.quote(text)) + ","
                + (error == null ? "null" : JSONObject.quote(error)) + ")";
        runOnUiThread(() -> {
            if (pageReady) web.evaluateJavascript(js, null);
            else pendingJs = js; // the page is still loading (photo shared into a closed app)
        });
    }

    /** A photo shared into the app from the gallery, a messenger or a screenshot. */
    private void handleShare(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        String type = intent.getType();
        if (type == null || !type.startsWith("image/")) return;
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri != null) recognize(uri);
        setIntent(new Intent()); // do not read the same photo again after rotation
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShare(intent);
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }

    /** Called from the page: haptics and clipboard, which the WebView does not provide itself. */
    private class Bridge {
        @JavascriptInterface
        public void vibrate(int ms) {
            final int type = ms >= 20 ? HapticFeedbackConstants.LONG_PRESS : HapticFeedbackConstants.KEYBOARD_TAP;
            web.post(() -> web.performHapticFeedback(type));
        }

        @JavascriptInterface
        public void setTheme(String theme) {
            prefs.edit().putString("theme", theme).apply();
            runOnUiThread(() -> applyBars(theme));
        }

        @JavascriptInterface
        public void scanPhoto(String source) {
            runOnUiThread(() -> startPhoto(source));
        }

        @JavascriptInterface
        public void copy(String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Результат", text));
            });
        }
    }
}
