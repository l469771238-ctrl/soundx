package com.soundpad.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/index.html";
    private static final int REQ_FILES = 1001;

    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;

    private final Map<Integer, OutputStream> outs = new ConcurrentHashMap<>();
    private final Map<Integer, Uri> pending = new ConcurrentHashMap<>();
    private final Map<Integer, String> names = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        web.setBackgroundColor(0xFF020504);
        setContentView(web);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) {
                    return false;
                }
                String scheme = u.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    openExternal(u);
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try {
                    startActivityForResult(Intent.createChooser(i, "اختر الملفات"), REQ_FILES);
                } catch (ActivityNotFoundException e) {
                    filePathCallback = null;
                    callback.onReceiveValue(null);
                    return false;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "AndroidSave");

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(START_URL);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) {
            web.saveState(outState);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILES && filePathCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    result = new Uri[n];
                    for (int k = 0; k < n; k++) {
                        result[k] = data.getClipData().getItemAt(k).getUri();
                    }
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(result);
            filePathCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private void openExternal(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "مفيش تطبيق يفتح الرابط ده", Toast.LENGTH_SHORT).show();
        }
    }

    /** بيستقبل الملفات اللي الصفحة بتحفظها (تصدير المخزون / حفظ صوت) ويكتبها في مجلد التنزيلات. */
    private class Bridge {

        @JavascriptInterface
        public int begin(String name, String mime) {
            try {
                String safe = (name == null ? "sound" : name).replaceAll("[\\\\/:*?\"<>|]", "_");
                if (safe.trim().isEmpty()) {
                    safe = "sound";
                }
                int id = nextId.getAndIncrement();
                OutputStream os;
                Uri uri = null;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                    v.put(MediaStore.Downloads.MIME_TYPE,
                            (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime);
                    v.put(MediaStore.Downloads.IS_PENDING, 1);
                    uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) {
                        return -1;
                    }
                    os = getContentResolver().openOutputStream(uri);
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) {
                        return -1;
                    }
                    if (!dir.exists()) {
                        dir.mkdirs();
                    }
                    os = new FileOutputStream(new File(dir, safe));
                }
                if (os == null) {
                    return -1;
                }
                outs.put(id, os);
                names.put(id, safe);
                if (uri != null) {
                    pending.put(id, uri);
                }
                return id;
            } catch (Exception e) {
                return -1;
            }
        }

        @JavascriptInterface
        public boolean chunk(int id, String b64) {
            try {
                OutputStream os = outs.get(id);
                if (os == null) {
                    return false;
                }
                os.write(Base64.decode(b64, Base64.DEFAULT));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public String end(int id) {
            OutputStream os = outs.remove(id);
            Uri uri = pending.remove(id);
            String nm = names.remove(id);
            try {
                if (os != null) {
                    os.flush();
                    os.close();
                }
                if (uri != null) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.IS_PENDING, 0);
                    getContentResolver().update(uri, v, null, null);
                    return "اتحفظ «" + nm + "» في مجلد التنزيلات (Downloads)";
                }
                return "اتحفظ «" + nm + "» في: Android/data/" + getPackageName() + "/files/Download";
            } catch (Exception e) {
                return "تعذّر إنهاء الحفظ: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public void abort(int id) {
            OutputStream os = outs.remove(id);
            Uri uri = pending.remove(id);
            names.remove(id);
            try {
                if (os != null) {
                    os.close();
                }
            } catch (Exception ignored) {
            }
            if (uri != null) {
                try {
                    getContentResolver().delete(uri, null, null);
                } catch (Exception ignored) {
                }
            }
        }
    }
}
