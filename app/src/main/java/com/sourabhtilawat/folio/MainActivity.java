package com.sourabhtilawat.folio;

/*
 * Folio Workbench for Android
 * Developed by Sourabh Tilawat
 *
 * Hosts the offline web app from assets/www in a WebView, served from a private https origin
 * so storage, workers and fetch behave like a normal site. Adds a native file picker, saving
 * to Downloads, "Open with" / "Share" for PDFs, and back-button navigation.
 */

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String BASE = "https://" + HOST + "/";
    private static final int PICK_FILES = 41;

    private WebView web;
    private ProBilling billing;
    private FolioAds ads;
    private FrameLayout adSlot;
    private ValueCallback<Uri[]> fileCallback;
    private boolean pageReady = false;
    private final List<Uri> incoming = new ArrayList<>();
    private final List<String[]> incomingMeta = new ArrayList<>();
    private final Map<String, OutputStream> saveStreams = new HashMap<>();
    private final Map<String, String[]> saveMeta = new HashMap<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        final int bg = night ? 0xFF090E18 : 0xFFEEF1F6;
        web = new WebView(this);
        web.setBackgroundColor(bg);
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.addView(web, new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        adSlot = new FrameLayout(this);
        root.addView(adSlot, new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        edgeToEdge(root, night);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setTextZoom(100);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                String path = u.getPath();
                if (path == null || path.equals("/")) path = "/index.html";
                try {
                    InputStream in;
                    String mime;
                    if (path.startsWith("/__incoming/")) {
                        int i = Integer.parseInt(path.substring(12));
                        in = getContentResolver().openInputStream(incoming.get(i));
                        mime = incomingMeta.get(i)[1];
                    } else {
                        in = getAssets().open("www" + path);
                        mime = mimeFor(path);
                    }
                    WebResourceResponse r = new WebResourceResponse(mime, mime.startsWith("text/") || mime.endsWith("javascript") ? "utf-8" : null, in);
                    Map<String, String> h = new HashMap<>();
                    h.put("Access-Control-Allow-Origin", "*");
                    h.put("Cache-Control", "no-cache");
                    r.setResponseHeaders(h);
                    return r;
                } catch (Exception e) {
                    return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, new ByteArrayInputStream(new byte[0]));
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                deliverIncoming();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                String[] mimes = mimesFor(params.getAcceptTypes());
                if (mimes.length > 0) i.putExtra(Intent.EXTRA_MIME_TYPES, mimes);
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(i, "Choose files"), PICK_FILES);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "FolioAndroid");
        billing = new ProBilling(this, json -> runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript("window.folioPro&&window.folioPro(" + json + ")", null);
            if (ads != null && billing != null) ads.setPro(billing.isOwned());
        }));
        web.addJavascriptInterface(billing.bridge, "FolioBilling");
        ads = new FolioAds(this, adSlot, billing.isOwned());
        web.addJavascriptInterface(ads.bridge, "FolioAds");

        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 7);
        }

        takeIncoming(getIntent());
        if (state != null) web.restoreState(state);
        else web.loadUrl(BASE + "index.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (billing != null) billing.onResume();
        if (ads != null) ads.onResume();
    }

    @Override
    protected void onPause() {
        if (ads != null) ads.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (billing != null) billing.destroy();
        if (ads != null) ads.destroy();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        takeIncoming(intent);
        if (pageReady) deliverIncoming();
    }

    /** Files sent to the app with "Open with" or "Share". */
    private void takeIncoming(Intent intent) {
        if (intent == null) return;
        List<Uri> uris = new ArrayList<>();
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) uris.add(intent.getData());
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        }
        if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        }
        for (Uri u : uris) {
            incoming.add(u);
            String type = getContentResolver().getType(u);
            incomingMeta.add(new String[]{displayName(u), type == null ? "application/pdf" : type, "new"});
        }
    }

    private void deliverIncoming() {
        StringBuilder js = new StringBuilder("[");
        for (int i = 0; i < incoming.size(); i++) {
            String[] m = incomingMeta.get(i);
            if (!"new".equals(m[2])) continue;
            m[2] = "sent";
            if (js.length() > 1) js.append(',');
            js.append("{url:'/__incoming/").append(i).append("',name:").append(jsString(m[0])).append(",type:").append(jsString(m[1])).append('}');
        }
        js.append(']');
        if (js.length() > 2) web.evaluateJavascript("window.folioIncoming&&window.folioIncoming(" + js + ")", null);
    }

    private String displayName(Uri u) {
        String name = null;
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) { }
        if (name == null) name = u.getLastPathSegment();
        if (name == null || name.isEmpty()) name = "document.pdf";
        return name;
    }

    private static String jsString(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ") + "'";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FILES || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                result = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) result[i] = clip.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(function(){if(location.hash&&location.hash.length>1){history.back();return 1}return 0})()", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String v) {
                if ("0".equals(v)) MainActivity.super.onBackPressed();
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    /** Saving results: the page streams a file in base64 chunks, then we write it to Downloads. */
    private class Bridge {
        @JavascriptInterface
        public String begin(String name, String mime) {
            try {
                String id = UUID.randomUUID().toString();
                File tmp = new File(getCacheDir(), "save_" + id);
                saveStreams.put(id, new FileOutputStream(tmp));
                saveMeta.put(id, new String[]{safeName(name), mime, tmp.getPath()});
                return id;
            } catch (IOException e) {
                throw new RuntimeException("Couldn't start saving: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public void append(String id, String b64) {
            try {
                saveStreams.get(id).write(Base64.decode(b64, Base64.DEFAULT));
            } catch (Exception e) {
                throw new RuntimeException("Couldn't write the file: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public String finish(String id) {
            String[] m = saveMeta.remove(id);
            File tmp = new File(m[2]);
            try {
                saveStreams.remove(id).close();
                String where = writeToDownloads(tmp, m[0], m[1]);
                return "Saved " + m[0] + " to " + where;
            } catch (Exception e) {
                return "Couldn't save " + m[0] + ": " + e.getMessage();
            } finally {
                tmp.delete();
            }
        }
    }

    private String writeToDownloads(File src, String name, String mime) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Folio Workbench");
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IOException("Downloads isn't available");
            try (OutputStream out = getContentResolver().openOutputStream(uri); InputStream in = new FileInputStream(src)) {
                copy(in, out);
            }
            return "Downloads/Folio Workbench";
        }
        File dir;
        String label;
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Folio Workbench");
            label = "Downloads/Folio Workbench";
        } else {
            dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            label = dir.getPath();
        }
        dir.mkdirs();
        File dst = new File(dir, name);
        for (int k = 2; dst.exists(); k++) dst = new File(dir, name.replaceFirst("(\\.[^.]+)?$", "_" + k + "$1"));
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            copy(in, out);
        }
        return label;
    }

    /** Draws behind the system bars on every Android version and pads the content so nothing hides under them. */
    @SuppressWarnings("deprecation")
    private void edgeToEdge(View root, boolean night) {
        Window w = getWindow();
        w.setStatusBarColor(0x00000000);
        w.setNavigationBarColor(0x00000000);
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(night ? 0 : light, light);
            }
        } else {
            int f = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (!night) {
                f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 26) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            w.getDecorView().setSystemUiVisibility(f);
        }
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                    v.setPadding(i.left, i.top, i.right, i.bottom);
                    return WindowInsets.CONSUMED;
                }
                v.setPadding(in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(), in.getSystemWindowInsetRight(), in.getSystemWindowInsetBottom());
                return in.consumeSystemWindowInsets();
            }
        });
        root.requestApplyInsets();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static String safeName(String n) {
        String s = n == null ? "file" : n.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return s.isEmpty() ? "file" : s;
    }

    private static String mimeFor(String p) {
        String l = p.toLowerCase();
        if (l.endsWith(".html")) return "text/html";
        if (l.endsWith(".js")) return "application/javascript";
        if (l.endsWith(".css")) return "text/css";
        if (l.endsWith(".woff2")) return "font/woff2";
        if (l.endsWith(".png")) return "image/png";
        if (l.endsWith(".wasm")) return "application/wasm";
        if (l.endsWith(".gz")) return "application/gzip";
        if (l.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    private static String[] mimesFor(String[] accept) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        boolean any = false;
        if (accept != null) for (String raw : accept) {
            for (String a : raw.split(",")) {
                a = a.trim().toLowerCase();
                if (a.isEmpty()) continue;
                switch (a) {
                    case ".pdf": out.add("application/pdf"); break;
                    case ".docx": out.add("application/vnd.openxmlformats-officedocument.wordprocessingml.document"); break;
                    case ".xlsx": case ".xlsm": out.add("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"); break;
                    case ".xls": out.add("application/vnd.ms-excel"); break;
                    case ".ods": out.add("application/vnd.oasis.opendocument.spreadsheet"); break;
                    case ".csv": case ".txt": case ".md": case ".log": case ".json": case ".html": case ".htm":
                        out.add("text/*"); out.add("application/octet-stream"); out.add("application/json"); break;
                    default:
                        if (a.contains("/")) out.add(a); else any = true;
                }
            }
        }
        if (any || out.isEmpty()) return new String[0];
        if (out.contains("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") || out.contains("application/vnd.openxmlformats-officedocument.wordprocessingml.document")) out.add("application/octet-stream");
        return out.toArray(new String[0]);
    }
}
