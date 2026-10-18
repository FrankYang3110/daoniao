package com.frank.daoniao;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 导尿记录 —— 完全离线运行，网页资源打包在 APK 内部。
 * 不申请网络权限，不联网，数据只保存在本机 WebView 的 localStorage。
 */
public class MainActivity extends Activity {

    private static final int REQ_STORAGE = 1001;
    private static final int REQ_FILE = 1002;

    private WebView web;
    /** 待保存的数据，等权限授予后再写 */
    private String pendingName = null;
    private String pendingMime = null;
    private String pendingBase64 = null;
    /** 网页 <input type=file> 的回调 */
    private ValueCallback<Uri[]> filePathCallback = null;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window w = getWindow();
        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int chrome = night ? 0xFF0B1220 : 0xFFEEF1F5;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            w.setStatusBarColor(chrome);
            w.setNavigationBarColor(chrome);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // 深色背景用浅色图标，浅色背景用深色图标
            int flag = night ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            w.getDecorView().setSystemUiVisibility(flag);
        }

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);

        // 文件访问：Android 11+ 收紧，仅 assets 内部页面
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            s.setAllowFileAccessFromFileURLs(false);
            s.setAllowUniversalAccessFromFileURLs(false);
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return true; // 全程不跳出应用
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(android.webkit.PermissionRequest request) {
                request.deny();
            }

            /** 恢复备份：允许网页打开系统文件选择器（SAF，不需要存储权限） */
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = cb;
                try {
                    Intent i = params.createIntent();
                    startActivityForResult(i, REQ_FILE);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
            }
        });

        web.setBackgroundColor(night ? 0xFF0B1220 : 0xFFEEF1F5);
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        web.loadUrl("file:///android_asset/www/index.html");
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /* ------------- 网页里的 <input type=file> ------------- */

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE) {
            if (filePathCallback != null) {
                Uri[] out = null;
                if (res == RESULT_OK && data != null) {
                    if (data.getData() != null) {
                        out = new Uri[]{ data.getData() };
                    } else if (data.getClipData() != null) {
                        int n = data.getClipData().getItemCount();
                        out = new Uri[n];
                        for (int i = 0; i < n; i++) {
                            out[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    }
                }
                filePathCallback.onReceiveValue(out);
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(req, res, data);
    }

    /* ---------------- 权限 ---------------- */

    private boolean needStoragePermission() {
        // Android 10 (Q) 起使用 MediaStore，无需权限
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                   != PackageManager.PERMISSION_GRANTED;
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_STORAGE) {
            boolean granted = results.length > 0
                    && results[0] == PackageManager.PERMISSION_GRANTED;
            if (granted && pendingBase64 != null) {
                doSave(pendingName, pendingBase64, pendingMime);
            } else if (!granted) {
                toastLong("没有存储权限，无法保存到「下载」。可在系统设置里开启后重试。");
            }
            pendingName = null;
            pendingMime = null;
            pendingBase64 = null;
        }
    }

    /* ---------------- 原生桥接 ---------------- */

    private class Bridge {

        @JavascriptInterface
        public boolean hasNative() {
            return true;
        }

        /** 网页调用：导出 CSV（保留旧接口） */
        @JavascriptInterface
        public boolean saveCsv(String fileName, String base64) {
            return saveFile(fileName, "text/csv", base64);
        }

        /* ===== 备份清单：App 内部目录里保存过的备份 ===== */

        private File backupDir() {
            File d = new File(getFilesDir(), "backup");
            if (!d.exists()) d.mkdirs();
            return d;
        }

        /**
         * 写入内部备份（不靠存储权限、不会被系统清理）。
         * 同时再往「下载/导尿记录」写一份，方便用户取走。
         */
        @JavascriptInterface
        public boolean saveBackup(String fileName, String base64) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                File f = new File(backupDir(), fileName);
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(bytes);
                fos.flush();
                fos.close();

                /* 只留最近 20 份，避免占空间 */
                File[] all = backupDir().listFiles();
                if (all != null && all.length > 20) {
                    java.util.Arrays.sort(all, new java.util.Comparator<File>() {
                        @Override
                        public int compare(File a, File b) {
                            return Long.compare(a.lastModified(), b.lastModified());
                        }
                    });
                    for (int i = 0; i < all.length - 20; i++) all[i].delete();
                }

                /* 请用户可见的一份（Android 10+ 无权限可写） */
                try {
                    if (!needStoragePermission()) {
                        doSaveQuiet(fileName, base64, "application/json");
                    }
                } catch (Exception ignored) {
                }

                toastLong("已备份：" + fileName);
                return true;
            } catch (Exception e) {
                toastLong("备份失败：" + e.getMessage());
                return false;
            }
        }

        /** 列出内部备份，JSON 数组：[{name,size,time}]，新的在前 */
        @JavascriptInterface
        public String listBackups() {
            StringBuilder sb = new StringBuilder("[");
            File[] all = backupDir().listFiles();
            if (all != null) {
                java.util.Arrays.sort(all, new java.util.Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        return Long.compare(b.lastModified(), a.lastModified());
                    }
                });
                boolean first = true;
                for (File f : all) {
                    if (!f.getName().toLowerCase().endsWith(".json")) continue;
                    if (!first) sb.append(',');
                    first = false;
                    sb.append("{\"name\":\"")
                      .append(f.getName().replace("\\", "\\\\").replace("\"", "\\\""))
                      .append("\",\"size\":").append(f.length())
                      .append(",\"time\":").append(f.lastModified())
                      .append('}');
                }
            }
            sb.append(']');
            return sb.toString();
        }

        /** 读取内部备份内容，失败返回空字符串 */
        @JavascriptInterface
        public String readBackup(String fileName) {
            try {
                File f = new File(backupDir(), fileName);
                if (!f.exists() || !f.isFile()) return "";
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                byte[] buf = new byte[(int) f.length()];
                int off = 0, n;
                while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
                in.close();
                return new String(buf, 0, off, "UTF-8");
            } catch (Exception e) {
                return "";
            }
        }

        /** 删除内部备份 */
        @JavascriptInterface
        public boolean deleteBackup(String fileName) {
            try {
                return new File(backupDir(), fileName).delete();
            } catch (Exception e) {
                return false;
            }
        }

        /** 网页调用：写文件到「下载/导尿记录/」。mime：text/csv 或 application/json */
        @JavascriptInterface
        public boolean saveFile(String fileName, String mime, String base64) {
            if (mime == null || mime.length() == 0) mime = "text/csv";
            if (needStoragePermission()) {
                pendingName = fileName;
                pendingMime = mime;
                pendingBase64 = base64;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("需要存储权限")
                                .setMessage("导出文件需要写入「下载」目录，请允许存储权限。")
                                .setPositiveButton("去允许", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int i) {
                                        requestStoragePermission();
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                });
                return true;
            }
            return doSave(fileName, base64, mime);
        }
    }

    /** 真正写入文件 */
    private boolean doSave(String fileName, String base64, String mime) {
        try {
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/导尿记录");
                Uri uri = getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) {
                    toastLong("保存失败：无法创建文件");
                    return false;
                }
                OutputStream os = getContentResolver().openOutputStream(uri);
                if (os == null) {
                    toastLong("保存失败：无法写入");
                    return false;
                }
                os.write(bytes);
                os.flush();
                os.close();
            } else {
                File dir = new File(
                        Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS), "导尿记录");
                if (!dir.exists() && !dir.mkdirs()) {
                    toastLong("保存失败：无法创建目录");
                    return false;
                }
                File f = new File(dir, fileName);
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(bytes);
                fos.flush();
                fos.close();
            }

            toastLong("已保存到：下载/导尿记录/" + fileName);
            return true;
        } catch (Exception e) {
            toastLong("保存失败：" + e.getMessage());
            return false;
        }
    }

    /** 静默写入「下载/导尿记录/」，失败不弹 Toast（用于备份时附带一份可见副本） */
    private boolean doSaveQuiet(String fileName, String base64, String mime) {
        try {
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                cv.put(MediaStore.MediaColumns.MIME_TYPE,
                        mime == null ? "application/octet-stream" : mime);
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/导尿记录");
                Uri uri = getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return false;
                OutputStream os = getContentResolver().openOutputStream(uri);
                if (os == null) return false;
                os.write(bytes);
                os.flush();
                os.close();
                return true;
            }
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "导尿记录");
            if (!dir.exists() && !dir.mkdirs()) return false;
            FileOutputStream fos = new FileOutputStream(new File(dir, fileName));
            fos.write(bytes);
            fos.flush();
            fos.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void toastLong(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }
}
