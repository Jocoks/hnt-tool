package com.jocoks.hnt;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.os.Build;
import android.os.Environment;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 荣耀主题包 .hnt 拆包 / 打包工具（外壳：单 WebView + Java 侧文件与加解密）。
 * 不申请任何存储权限：输入输出都走系统文件选择器（SAF）。
 */
public class MainActivity extends Activity {

    private static final int REQ_HNT = 11;

    /** 模块回传的密钥（hitopId -> hex），只在内存里过一下，不落盘。 */
    private final java.util.Map<String, String> liveKeys = new java.util.HashMap<String, String>();
    private BroadcastReceiver keyRx;
    private static final int REQ_OUT = 12;
    private static final int REQ_SRC = 13;
    private static final int REQ_SAVE = 14;

    /** 固定输出根目录：下载/HNT/（走 MANAGE_EXTERNAL_STORAGE，直接写文件）。 */
    static final String OUT_ROOT = "/storage/emulated/0/Download/HNT";

    private static final String PREFS = "hnt";
    private static final String K_KEY = "key_";

    private WebView web;
    private Saf saf;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private byte[] bytes;
    private String fileName = "";
    private Hnt.Info info;
    private Uri outTree;
    private Uri srcTree;
    private String srcName = "";
    private boolean restored;
    private String pendingId = "";
    private String pendingVer = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        keyRx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                try {
                    String id = i.getStringExtra("hitopId");
                    String hex = i.getStringExtra("key");
                    if (id != null && hex != null) {
                        synchronized (liveKeys) {
                            liveKeys.put(id, hex);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            registerReceiver(keyRx, new IntentFilter(ModBridge.ACTION_READY), 2 /* RECEIVER_EXPORTED */);
        } catch (Throwable ignored) {
            keyRx = null;
        }
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        saf = new Saf(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFFFFFFFF);

        web = new WebView(this);
        web.setBackgroundColor(0xFFFFFFFF);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        web.setWebChromeClient(new Chrome());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                restore();
            }
        });
        web.addJavascriptInterface(new Bridge(), "HNT");
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        applySystemBars(root);
        web.loadUrl("file:///android_asset/index.html");
    }

    private void applySystemBars(final View root) {
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(0xFFFFFFFF);
        w.setNavigationBarColor(0xFFFFFFFF);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(mask, mask);
            }
            w.setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    android.graphics.Insets bars = insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    int ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
                    int bottom = Math.max(bars.bottom, ime);
                    v.setPadding(bars.left, bars.top, bars.right, bottom);
                    return insets;
                }
            });
            root.setPadding(0, statusBarHeight(), 0, 0);
        } else if (Build.VERSION.SDK_INT >= 23) {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) return getResources().getDimensionPixelSize(id);
        return Math.round(24 * getResources().getDisplayMetrics().density);
    }

    /* ==================== 与页面通信 ==================== */

    private void emit(String type, JSONObject extra) {
        try {
            JSONObject o = extra == null ? new JSONObject() : extra;
            o.put("t", type);
            final String js = "window.HntUI&&HntUI.on(" + o.toString() + ");";
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (web != null) web.evaluateJavascript(js, null);
                }
            });
        } catch (Exception ignored) {
        }
    }

    private void log(String msg) {
        try {
            emit("log", new JSONObject().put("msg", msg));
        } catch (Exception ignored) {
        }
    }

    private void progress(String phase, int done, int total) {
        try {
            JSONObject o = new JSONObject();
            o.put("phase", phase);
            o.put("done", done);
            o.put("total", total);
            emit("progress", o);
        } catch (Exception ignored) {
        }
    }

    private void fail(String msg) {
        try {
            emit("err", new JSONObject().put("msg", msg));
        } catch (Exception ignored) {
        }
    }

    private JSONObject keysJson() throws Exception {
        JSONObject o = new JSONObject();
        Map<String, ?> all = prefs.getAll();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            String k = e.getKey();
            if (k.startsWith(K_KEY) && e.getValue() instanceof String) {
                o.put(k.substring(K_KEY.length()), (String) e.getValue());
            }
        }
        return o;
    }

    private byte[] storedKey(String hitopId) {
        String hex = prefs.getString(K_KEY + hitopId, "");
        if (hex == null || hex.length() < 32) return null;
        try {
            return Hnt.fromHex(hex);
        } catch (Exception e) {
            return null;
        }
    }

    /* ==================== 选择器回调 ==================== */

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        android.util.Log.i("HNT", "onActivityResult req=" + req + " res=" + res
                + " data=" + (data == null ? "null" : data.getData()));
        if (res != RESULT_OK || data == null || data.getData() == null) {
            emit("cancel", null);
            return;
        }
        final Uri u = data.getData();
        try {
            if (req == REQ_HNT) {
                getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                getContentResolver().takePersistableUriPermission(u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
        } catch (Exception ignored) {
        }

        if (req == REQ_HNT) {
            final String nm = Saf.nameOf(this, u);
            prefs.edit().putString("hnt_uri", u.toString()).apply();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        emitHntInfo(readAll(u), nm);
                    } catch (Exception e) {
                        prefs.edit().remove("hnt_uri").apply();
                        fail("读取 .hnt 失败：" + e.getMessage());
                    }
                }
            }).start();
        } else if (req == REQ_OUT) {
            outTree = u;
            prefs.edit().putString("out_tree", u.toString()).apply();
            try {
                emit("outdir", new JSONObject().put("name", Saf.nameOf(this, u)));
            } catch (Exception ignored) { }
        } else if (req == REQ_SRC) {
            srcTree = u;
            srcName = Saf.nameOf(this, u);
            prefs.edit().putString("src_tree", u.toString())
                    .putString("src_name", srcName).apply();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Saf.TreeSource ts = saf.new TreeSource(srcTree);
                        JSONObject o = new JSONObject();
                        o.put("name", srcName);
                        o.put("count", ts.files().size());
                        prefs.edit().putInt("src_count", ts.files().size()).apply();
                        emit("srcdir", o);
                    } catch (Exception e) {
                        fail("读取目录失败：" + e.getMessage());
                    }
                }
            }).start();
        } else if (req == REQ_SAVE) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    doPack(u, pendingId, pendingVer);
                }
            }).start();
        }
    }

    private JSONObject hntJson(Hnt.Info in, int size) throws Exception {
        JSONObject o = new JSONObject();
        o.put("name", fileName);
        o.put("size", size);
        o.put("hitopId", in.hitopId);
        o.put("version", in.version);
        o.put("jsonLen", in.jsonLen);
        o.put("encLen", in.encLen);
        o.put("hashOk", in.hashOk);
        o.put("hasKey", storedKey(in.hitopId) != null);
        return o;
    }

    private void emitHntInfo(byte[] b, String nm) throws Exception {
        Hnt.Info in = Hnt.parse(b);
        bytes = b;
        fileName = nm;
        info = in;
        emit("hnt", hntJson(in, b.length));
    }

    /** 重新打开 App 后，把上次的选择（.hnt / 输出目录 / 打包源）恢复回来。 */
    private void restore() {
        if (restored) return;
        restored = true;
        final String hntUri = prefs.getString("hnt_uri", "");
        if (hntUri != null && hntUri.length() > 0) {
            final Uri u = Uri.parse(hntUri);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        emitHntInfo(readAll(u), Saf.nameOf(MainActivity.this, u));
                    } catch (Exception e) {
                        prefs.edit().remove("hnt_uri").apply();
                    }
                }
            }).start();
        }
        String ot = prefs.getString("out_tree", "");
        if (ot != null && ot.length() > 0) {
            outTree = Uri.parse(ot);
            try {
                emit("outdir", new JSONObject().put("name", Saf.nameOf(this, outTree)));
            } catch (Exception ignored) { }
        }
        String st = prefs.getString("src_tree", "");
        if (st != null && st.length() > 0) {
            srcTree = Uri.parse(st);
            srcName = prefs.getString("src_name", "");
            try {
                JSONObject o = new JSONObject();
                o.put("name", srcName);
                o.put("count", prefs.getInt("src_count", 0));
                emit("srcdir", o);
            } catch (Exception ignored) { }
        }
    }

    /** 密钥变化后只更新「密钥」那一行的显示，不整块重绘。 */
    private void emitKeyInfo() {
        try {
            boolean has = info != null && storedKey(info.hitopId) != null;
            emit("keyinfo", new JSONObject().put("hasKey", has));
        } catch (Exception ignored) { }
    }

    private byte[] readAll(Uri u) throws Exception {
        InputStream in = getContentResolver().openInputStream(u);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20);
        try {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
        } finally {
            in.close();
        }
        return bos.toByteArray();
    }

    /* ==================== 拆包 / 打包 ==================== */

    private void doUnpack() {
        if (bytes == null || info == null) {
            fail("请先选择 .hnt 文件");
            return;
        }
        if (!fsReady()) {
            ensureFs();
            fail("请先允许「所有文件访问权限」，然后回到本页重试");
            return;
        }
        new File(OUT_ROOT).mkdirs();

        final byte[] key = storedKey(info.hitopId);
        if (key == null) {
            needKey(info.hitopId);
            return;
        }
        final String folder = folderName(fileName);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    log("正在解密 " + fileName + " …");
                    byte[] zip = Hnt.decrypt(info, key);
                    if (zip.length < 4 || zip[0] != 'P' || zip[1] != 'K') {
                        throw new Exception("解密结果不是 ZIP，密钥可能不对");
                    }
                    final File root = new File(OUT_ROOT, folder);
                    deleteTree(root);
                    if (!root.exists() && !root.mkdirs()) {
                        throw new Exception("无法创建 " + root.getPath());
                    }
                    log("解密成功，ZIP " + zip.length + " 字节，开始解包到 " + OUT_ROOT + "/" + folder + "/");
                    Zip.Sink sink = new Zip.Sink() {
                        @Override
                        public void dir(String path) {
                            new File(root, path).mkdirs();
                        }

                        @Override
                        public OutputStream file(String path, long size) throws Exception {
                            File f = new File(root, path);
                            File d = f.getParentFile();
                            if (d != null) d.mkdirs();
                            return new FileOutputStream(f);
                        }
                    };
                    final long t0 = System.currentTimeMillis();
                    int n = Zip.unzip(zip, sink, new Zip.Progress() {
                        @Override
                        public void on(int done, int total) {
                            progress("unzip", done, -1);
                        }
                    });
                    JSONObject o = new JSONObject();
                    o.put("count", n);
                    o.put("dir", OUT_ROOT + "/" + folder);
                    o.put("ms", System.currentTimeMillis() - t0);
                    emit("done", o);
                } catch (Exception e) {
                    fail("拆包失败：" + e.getMessage());
                }
            }
        }).start();
    }

    /** 直接写文件用的 Source：把 下载/HNT/<目录> 整棵读出来。 */
    private Zip.Source fileSource(final File root) {
        return new Zip.Source() {
            @Override
            public List<String> files() {
                List<String> out = new ArrayList<String>();
                walk(root, "", out);
                java.util.Collections.sort(out);
                return out;
            }

            private void walk(File dir, String prefix, List<String> out) {
                File[] fs = dir.listFiles();
                if (fs == null) return;
                for (File f : fs) {
                    String p = prefix.length() == 0 ? f.getName() : prefix + "/" + f.getName();
                    if (f.isDirectory()) walk(f, p, out);
                    else out.add(p);
                }
            }

            @Override
            public InputStream open(String path) throws Exception {
                return new FileInputStream(new File(root, path));
            }
        };
    }

    private static void deleteTree(File f) {
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) deleteTree(c);
        f.delete();
    }

    /** 把包里的 description.xml / preview 铺到主题管理器的缓存目录，列表才认得出标题和封面。 */
    private static void syncThemeCache(File srcDir, String hitopId) {
        File cache = new File("/storage/emulated/0/Honor/Themes/.cache/hnt_" + hitopId + ".hwt");
        try {
            deleteTree(cache);
            if (!cache.mkdirs()) return;
            File desc = new File(srcDir, "description.xml");
            if (desc.isFile()) copyFile(desc, new File(cache, "description.xml"));
            File pv = new File(srcDir, "preview");
            if (pv.isDirectory()) {
                File dst = new File(cache, "preview");
                dst.mkdirs();
                File[] fs = pv.listFiles();
                if (fs != null) for (File f : fs) if (f.isFile()) copyFile(f, new File(dst, f.getName()));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void copyFile(File src, File dst) {
        try {
            FileInputStream in = new FileInputStream(src);
            try {
                FileOutputStream out = new FileOutputStream(dst);
                try {
                    byte[] b = new byte[1 << 16];
                    int r;
                    while ((r = in.read(b)) > 0) out.write(b, 0, r);
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 从 description.xml 里取标题/作者，写一份给模块用的小 json。 */
    private static void writeThemeMeta(File srcDir, String hitopId) {
        try {
            String xml = "";
            File desc = new File(srcDir, "description.xml");
            if (desc.isFile()) {
                byte[] b = new byte[(int) Math.min(desc.length(), 64 * 1024)];
                FileInputStream in = new FileInputStream(desc);
                try {
                    int off = 0, r;
                    while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r;
                } finally {
                    in.close();
                }
                xml = new String(b, "UTF-8");
            }
            String title = tag(xml, "title");
            String designer = tag(xml, "designer");
            if (title == null || title.length() == 0) title = hitopId;
            JSONObject o = new JSONObject();
            o.put("title", title);
            o.put("designer", designer == null ? "" : designer);
            o.put("hitopId", hitopId);
            FileOutputStream out = new FileOutputStream(
                    new File("/storage/emulated/0/Honor/Themes/hnt_" + hitopId + ".title"));
            try {
                out.write(o.toString().getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String tag(String xml, String name) {
        int a = xml.indexOf("<" + name + ">");
        if (a < 0) return null;
        a += name.length() + 2;
        int b = xml.indexOf("</" + name + ">", a);
        if (b < 0) return null;
        return xml.substring(a, b).trim();
    }

    private static boolean fsReady() {
        return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
    }

    private void ensureFs() {
        if (fsReady()) return;
        try {
            android.content.Intent i = new android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception ignored) {
            }
        }
    }

    private void doPack(final Uri target, final String hitopId, final String version) {
        final byte[] key = storedKey(hitopId);
        if (key == null) {
            needKey(hitopId);
            return;
        }
        try {
            log("正在读取目录 " + srcName + " …");
            Zip.Source src = fileSource(new File(OUT_ROOT, srcName));
            log("共 " + src.files().size() + " 个文件，开始压缩 …");
            byte[] zip = Zip.zip(src, new Zip.Progress() {
                @Override
                public void on(int done, int total) {
                    progress("zip", done, total);
                }
            });
            log("压缩完成（" + zip.length + " 字节），正在加密 …");
            String json = Hnt.makeJson(hitopId, version);
            byte[] out = Hnt.repack(zip, json, key);
            log("写入 " + OUT_ROOT + "/" + hitopId + ".hnt（" + out.length + " 字节）…");
            File dst = new File(OUT_ROOT, hitopId + ".hnt");
            FileOutputStream fos = new FileOutputStream(dst);
            try {
                fos.write(out);
                fos.flush();
            } finally {
                fos.close();
            }
            // 顺手写一份「解锁版」（普通 zip）到主题目录，供模块伪造安装记录用
            try {
                File themes = new File("/storage/emulated/0/Honor/Themes");
                if (themes.isDirectory()) {
                    File old = new File(themes, "hnt_" + hitopId + ".hnt");
                    if (old.exists()) old.delete();
                    File hwt = new File(themes, "hnt_" + hitopId + ".hwt");
                    FileOutputStream f2 = new FileOutputStream(hwt);
                    try {
                        f2.write(zip);
                        f2.flush();
                    } finally {
                        f2.close();
                    }
                    File srcDir = new File(OUT_ROOT, srcName);
                    syncThemeCache(srcDir, hitopId);
                    writeThemeMeta(srcDir, hitopId);
                    log("已写入解锁版：" + hwt.getAbsolutePath());
                }
            } catch (Throwable t) {
                log("写解锁版失败：" + t);
            }
            // 自检：立刻按同样的规则解一遍
            Hnt.Info chk = Hnt.parse(out);
            boolean ok = chk.hashOk && chk.hitopId.equals(hitopId);
            JSONObject o = new JSONObject();
            o.put("size", out.length);
            o.put("files", src.files().size());
            o.put("hashOk", ok);
            o.put("hitopId", chk.hitopId);
            emit("packed", o);
        } catch (Exception e) {
            fail("打包失败：" + e.getMessage());
        }
    }

    private void needKey(String hitopId) {
        try {
            emit("needkey", new JSONObject().put("hitopId", hitopId));
        } catch (Exception ignored) {
        }
    }

    private static String folderName(String file) {
        String n = file == null ? "" : file;
        int i = n.lastIndexOf('.');
        if (i > 0) n = n.substring(0, i);
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (n.length() == 0) n = "unpacked";
        return n;
    }

    /* ==================== 页面桥 ==================== */

    /** 通知注入在主题管理器里的模块：顺手把这个 hitopId 的密钥算一遍。 */
    private void requestKey(String hitopId) {
        try {
            Intent req = new Intent(ModBridge.ACTION_REQ).setPackage(KeyLib.THEME_PKG);
            req.putExtra("hitopId", hitopId);
            sendBroadcast(req);
        } catch (Throwable ignored) {
        }
    }

    /** 从公共目录（优先用 SAF 里的输出目录，其次直接读）取模块写出的密钥。 */
    private byte[] readSharedKey(String hitopId) {
        synchronized (liveKeys) {
            String live = liveKeys.get(hitopId);
            if (live != null) {
                try {
                    byte[] kb = Hnt.fromHex(live);
                    if (kb.length == 16 || kb.length == 24 || kb.length == 32) return kb;
                } catch (Throwable ignored) {
                }
            }
        }
        String name = hitopId + ".hex";
        InputStream in = null;
        try {
            String treeS = prefs.getString("out_tree", null);
            if (treeS != null) {
                Uri tree = Uri.parse(treeS);
                Uri f = Saf.findFile(this, tree, ".hntkeys", name);
                if (f == null) {
                    try {
                        String docId = DocumentsContract.getTreeDocumentId(tree)
                                + "/.hntkeys/" + name;
                        f = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
                    } catch (Throwable ignored) {
                        f = null;
                    }
                }
                if (f != null) {
                    try {
                        in = getContentResolver().openInputStream(f);
                    } catch (Throwable ignored) {
                        in = null;
                    }
                }
            }
            if (in == null) {
                File raw = new File(KeyLib.KEYDIR, name);
                if (raw.canRead()) in = new FileInputStream(raw);
            }
            if (in == null) return null;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            byte[] kb = Hnt.fromHex(new String(bo.toByteArray(), "US-ASCII"));
            if (kb.length == 16 || kb.length == 24 || kb.length == 32) return kb;
            return null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { }
        }
    }

    private class Bridge {

        @JavascriptInterface
        public String env() {
            JSONObject o = new JSONObject();
            try {
                o.put("version", "1.0");
                o.put("pkg", getPackageName());
                o.put("sdk", Build.VERSION.SDK_INT);
                o.put("keys", keysJson());
                o.put("fs", fsReady());
                o.put("root", OUT_ROOT);
                o.put("themeInstalled", true);
                try {
                    o.put("themeUid", getPackageManager().getApplicationInfo(
                            KeyLib.THEME_PKG, 0).uid);
                } catch (Exception e) {
                    o.put("themeInstalled", false);
                }
            } catch (Exception ignored) {
            }
            return o.toString();
        }

        @JavascriptInterface
        public void pickHnt() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    try {
                        startActivityForResult(i, REQ_HNT);
                    } catch (Exception e) {
                        fail("没有可用的文件选择器");
                    }
                }
            });
        }

        @JavascriptInterface
        public String listDirs() {
            JSONArray a = new JSONArray();
            File[] fs = new File(OUT_ROOT).listFiles();
            if (fs != null) {
                java.util.List<String> names = new ArrayList<String>();
                for (File f : fs) if (f.isDirectory()) names.add(f.getName());
                java.util.Collections.sort(names);
                for (String n : names) a.put(n);
            }
            return a.toString();
        }

        @JavascriptInterface
        public void setSrc(final String name) {
            final String n = name == null ? "" : name.trim();
            if (n.length() == 0) return;
            srcName = n;
            prefs.edit().putString("src_name", srcName).apply();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Zip.Source src = fileSource(new File(OUT_ROOT, srcName));
                        int count = src.files().size();
                        prefs.edit().putInt("src_count", count).apply();
                        JSONObject o = new JSONObject();
                        o.put("name", srcName);
                        o.put("count", count);
                        emit("srcdir", o);
                    } catch (Exception e) {
                        fail("读取目录失败：" + e.getMessage());
                    }
                }
            }).start();
        }

        @JavascriptInterface
        public boolean fsOk() {
            return fsReady();
        }

        @JavascriptInterface
        public void requestFs() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    ensureFs();
                }
            });
        }

        @JavascriptInterface
        public void pickOut() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    try {
                        startActivityForResult(i, REQ_OUT);
                    } catch (Exception e) {
                        fail("没有可用的目录选择器");
                    }
                }
            });
        }

        @JavascriptInterface
        public void pickSrc() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    try {
                        startActivityForResult(i, REQ_SRC);
                    } catch (Exception e) {
                        fail("没有可用的目录选择器");
                    }
                }
            });
        }

        @JavascriptInterface
        public void unpack() {
            doUnpack();
        }

        @JavascriptInterface
        public void pack(String hitopId, String version) {
            final String id = hitopId == null ? "" : hitopId.trim();
            String ver = version == null ? "" : version.trim();
            if (id.length() == 0) {
                fail("请填写 hitopId");
                return;
            }
            if (ver.length() == 0) ver = "8.0.1";
            if (srcName == null || srcName.length() == 0) {
                fail("请先选一个文件夹");
                return;
            }
            doPack(null, id, ver);
        }

        @JavascriptInterface
        public String keyOf(String hitopId) {
            return prefs.getString(K_KEY + hitopId, "");
        }

        @JavascriptInterface
        public boolean setKey(String hitopId, String hex) {
            try {
                byte[] k = Hnt.fromHex(hex);
                if (k.length != 16 && k.length != 24 && k.length != 32) return false;
                prefs.edit().putString(K_KEY + hitopId, Hnt.toHex(k)).apply();
                try {
                    emit("keys", new JSONObject().put("keys", keysJson()));
                } catch (Exception ignored) { }
                emitKeyInfo();
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void delKey(String hitopId) {
            prefs.edit().remove(K_KEY + hitopId).apply();
            try {
                emit("keys", new JSONObject().put("keys", keysJson()));
            } catch (Exception ignored) { }
            emitKeyInfo();
        }

        @JavascriptInterface
        public void autoKey(final String hitopId) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        log("正在让主题管理器算密钥（免 root，走 LSPosed 模块）…");
                        requestKey(hitopId);
                        byte[] k = null;
                        long until = System.currentTimeMillis() + 8000;
                        while (System.currentTimeMillis() < until) {
                            k = readSharedKey(hitopId);
                            if (k != null) break;
                            try {
                                Thread.sleep(400);
                            } catch (Exception ignored) {
                            }
                        }
                        if (k == null) {
                            fail("还没拿到密钥。请确认：① LSPosed 里启用了本模块，作用域勾选 "
                                    + KeyLib.THEME_PKG + "；② 打开过一次「主题」App；"
                                    + "③ 输出目录选在 下载/dsh。也可以手动输入密钥。");
                            return;
                        }
                        prefs.edit().putString(K_KEY + hitopId, Hnt.toHex(k)).apply();
                        JSONObject o = new JSONObject();
                        o.put("hitopId", hitopId);
                        o.put("hex", Hnt.toHex(k));
                        o.put("keys", keysJson());
                        emit("key", o);
                        emitKeyInfo();
                    } catch (Exception e) {
                        fail("自动取密钥失败：" + e.getMessage());
                    }
                }
            }).start();
        }

        @JavascriptInterface
        public void selfTest() {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        byte[] key = Hnt.fromHex("000102030405060708090a0b0c0d0e0f");
                        String json = Hnt.makeJson("selftest", "1.0");
                        byte[] z = Zip.zip(new Zip.Source() {
                            @Override
                            public java.util.List<String> files() {
                                return java.util.Arrays.asList("a.txt", "d/b.txt");
                            }

                            @Override
                            public InputStream open(String path) {
                                try {
                                    return new java.io.ByteArrayInputStream(
                                            ("hello " + path).getBytes("UTF-8"));
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        }, null);
                        byte[] hnt = Hnt.repack(z, json, key);
                        Hnt.Info in = Hnt.parse(hnt);
                        byte[] back = Hnt.decrypt(in, key);
                        boolean ok = in.hashOk && java.util.Arrays.equals(z, back)
                                && in.hitopId.equals("selftest");
                        JSONObject o = new JSONObject();
                        o.put("ok", ok);
                        o.put("size", hnt.length);
                        emit("selftest", o);
                    } catch (Exception e) {
                        fail("自检失败：" + e.getMessage());
                    }
                }
            }).start();
        }
    }

    private class Chrome extends WebChromeClient {
        @Override
        public boolean onJsConfirm(WebView view, String url, String message, final JsResult r) {
            new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { r.confirm(); }
                    })
                    .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { r.cancel(); }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        public void onCancel(DialogInterface d) { r.cancel(); }
                    })
                    .show();
            return true;
        }

        @Override
        public boolean onJsAlert(WebView view, String url, String message, final JsResult r) {
            new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setPositiveButton("知道了", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { r.confirm(); }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        public void onCancel(DialogInterface d) { r.cancel(); }
                    })
                    .show();
            return true;
        }
    }

    @Override
    protected void onDestroy() {
        if (keyRx != null) {
            try {
                unregisterReceiver(keyRx);
            } catch (Throwable ignored) {
            }
            keyRx = null;
        }
        super.onDestroy();
    }
}
