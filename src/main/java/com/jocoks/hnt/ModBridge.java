package com.jocoks.hnt;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Process;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 跑在主题管理器进程里（由 LSPosed 注入）。只做两件事：
 *  1. 进程起来时枚举自己的 Keystore alias，把能解的内容密钥写到公共目录；
 *  2. 监听 com.jocoks.hnt.KEYREQ，收到就补算一次。
 *
 * 全程不用 root、不 fork、不执行 shell，也不往 logcat 输出内容。
 */
public final class ModBridge {

    public static final String ACTION_REQ = "com.jocoks.hnt.KEYREQ";
    public static final String ACTION_READY = "com.jocoks.hnt.KEYREADY";

    private static boolean started;

    private ModBridge() {
    }

    public static synchronized void start(final ClassLoader cl) {
        if (started) return;
        started = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runInner(cl);
                } catch (Throwable ignored) {
                }
            }
        }, "hnt-bridge");
        t.setDaemon(true);
        t.start();
    }

    private static void runInner(ClassLoader cl) throws Exception {
        Application app = awaitApplication();
        if (app == null) return;
        KeyLib.installProvider();
        HookFs.install(cl, app);
        scan(app);
        try {
            final ClassLoader cl2 = cl;
            final Application ctx = app;
            new Thread(new Runnable() {
                public void run() {
                    try {
                        Thread.sleep(4000);
                        Class<?> j = Class.forName("com.hihonor.android.thememanager.common.j", false, cl2);
                        for (java.lang.reflect.Method m : j.getDeclaredMethods()) {
                            if (!m.getName().equals("scanAllTheme")) continue;
                            if (m.getParameterTypes().length != 1) continue;
                            m.setAccessible(true);
                            try {
                                Object r = m.invoke(null, ctx);
                                HookFs.log("scanAllTheme(" + m.getParameterTypes()[0].getSimpleName() + ") -> " + r);
                            } catch (Throwable t2) {
                                HookFs.log("scanAllTheme invoke err " + t2);
                            }
                        }
                    } catch (Throwable t) {
                        HookFs.log("scanAllTheme err " + t);
                    }
                }
            }, "hnt-scan").start();
        } catch (Throwable ignored) {
        }

        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (c != null) scan(c.getApplicationContext());
            }
        };
        IntentFilter f = new IntentFilter(ACTION_REQ);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(r, f, 2 /* Context.RECEIVER_EXPORTED */);
            } else {
                app.registerReceiver(r, f);
            }
        } catch (Throwable ignored) {
        }
    }

    private static Application awaitApplication() {
        for (int i = 0; i < 200; i++) {
            try {
                Application a = de.robv.android.xposed.AndroidAppHelper.currentApplication();
                if (a != null) return a;
            } catch (Throwable ignored) {
            }
            try {
                Class<?> at = Class.forName("android.app.ActivityThread");
                Object o = at.getMethod("currentApplication").invoke(null);
                if (o instanceof Application) return (Application) o;
            } catch (Throwable ignored) {
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return null;
            }
        }
        return null;
    }

    /** 把 files/<sha256(alias)> 的密文解成密钥，写到公共目录。 */
    private static void scan(Context ctx) {
        File dir = new File(KeyLib.KEYDIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            note(null, "cannot create " + KeyLib.KEYDIR);
            return;
        }
        List<String> aliases = KeyLib.aliases();
        if (aliases.isEmpty()) {
            note(null, "no alias (uid=" + Process.myUid() + ")");
            return;
        }
        int ok = 0;
        String lastErr = null;
        for (int i = 0; i < aliases.size(); i++) {
            String alias = aliases.get(i);
            try {
                File blob = new File(ctx.getFilesDir(), KeyLib.sha256Hex(alias));
                if (!blob.isFile() || !blob.canRead()) continue;
                byte[] raw = readAll(blob);
                byte[] bytes = Hnt.fromHex(new String(raw, StandardCharsets.US_ASCII));
                byte[] key = KeyLib.contentKey(alias, bytes);
                if (key != null && (key.length == 16 || key.length == 24 || key.length == 32)) {
                    writeKey(dir, alias, Hnt.toHex(key));
                    reply(ctx, alias, Hnt.toHex(key));
                    ok++;
                }
            } catch (Throwable t) {
                lastErr = alias + ": " + t;
            }
        }
        note(null, ok > 0 ? null : lastErr);
        try {
            Intent done = new Intent(ACTION_READY).setPackage("com.jocoks.hnt");
            done.putExtra("count", ok);
            ctx.sendBroadcast(done);
        } catch (Throwable ignored) {
        }
    }

    /** 直接把密钥回传给「HNT 工具」（它在前台时立即生效，不依赖 /sdcard 可写）。 */
    private static void reply(Context ctx, String alias, String hex) {
        try {
            Intent i = new Intent(ACTION_READY).setPackage("com.jocoks.hnt");
            i.putExtra("hitopId", alias);
            i.putExtra("key", hex);
            ctx.sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }

    private static void writeKey(File dir, String alias, String hex) throws IOException {
        File tmp = new File(dir, alias + ".hex.tmp");
        FileOutputStream fo = new FileOutputStream(tmp);
        try {
            fo.write((hex + "\n").getBytes(StandardCharsets.US_ASCII));
            fo.flush();
            fo.getFD().sync();
        } finally {
            fo.close();
        }
        File dst = new File(dir, alias + ".hex");
        if (!tmp.renameTo(dst)) {
            tmp.delete();
        }
    }

    private static byte[] readAll(File f) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            byte[] b = new byte[(int) raf.length()];
            raf.readFully(b);
            return b;
        } finally {
            raf.close();
        }
    }

    /** 只在出错时留一行字，正常情况不产生任何额外文件。 */
    private static void note(String tag, String msg) {
        if (msg == null) return;
        try {
            File dir = new File(KeyLib.KEYDIR);
            File f = new File(dir, "_err.txt");
            FileOutputStream fo = new FileOutputStream(f, false);
            try {
                fo.write(("uid=" + Process.myUid() + " " + msg + "\n").getBytes(StandardCharsets.UTF_8));
            } finally {
                fo.close();
            }
        } catch (Throwable ignored) {
        }
    }
}
