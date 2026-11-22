package com.jocoks.hnt;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * 让「我的下载」也能看到 下载/HNT/ 里的 .hnt。
 *
 * 主题管理器只在 Honor/Themes 目录里找本地主题包，这里在它列目录时把
 * /storage/emulated/0/Download/HNT/*.hnt 追加进去（对 App 来说就是同一个列表里的普通文件）。
 */
final class HookFs {

    private static final String HNT_DIR = "/storage/emulated/0/Download/HNT";
    /** 合成记录用路径前缀；主题App 侧看到的是 Honor/Themes 下的副本。 */
    private static final String MARK = "/Honor/Themes/hnt_";
    private static final String CACHE_DIR = "/storage/emulated/0/Honor/Themes/.cache";
    private static final String THEMES_DIR = "/storage/emulated/0/Honor/Themes";
    private static final String LOG = "/storage/emulated/0/Download/HNT/.hook.log";
    private static volatile boolean installed;
    private static volatile boolean stackLogged;
    private static int qLog;
    private static final java.util.Map<String, Long> lastTry = new java.util.HashMap<String, Long>();

    private HookFs() {
    }

    static void log(String s) {
        try {
            FileWriter w = new FileWriter(LOG, true);
            try {
                w.write(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " " + s + "\n");
            } finally {
                w.close();
            }
        } catch (Throwable ignored) {
        }
    }

    static android.content.Context app;

    /** 总开关：false = 不碰主题 App 的任何行为（列表彻底恢复原生）。 */
    static final boolean INJECT = false;

    static void install(ClassLoader appCl, android.content.Context ctx) {
        if (!INJECT) return;
        if (ctx != null) app = ctx;
        if (installed) return;
        installed = true;
        try {
            XposedBridge.hookAllMethods(File.class, "listFiles", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        handle(p);
                    } catch (Throwable t) {
                        log("listFiles err " + t);
                    }
                }

                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        before(p);
                    } catch (Throwable t) {
                        log("listFiles err " + t);
                    }
                }
            });
            XposedBridge.hookAllConstructors(java.io.FileInputStream.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        Object a0 = p.args != null && p.args.length > 0 ? p.args[0] : null;
                        String path = a0 instanceof File ? ((File) a0).getAbsolutePath()
                                : a0 instanceof String ? (String) a0 : null;
                        if (path != null && path.contains("/Download/HNT/")) {
                            log("open " + path);
                            if (!stackLogged) {
                                stackLogged = true;
                                StackTraceElement[] st = Thread.currentThread().getStackTrace();
                                StringBuilder sb = new StringBuilder("stack:");
                                for (int i = 0; i < st.length && i < 25; i++) {
                                    sb.append("\n  ").append(st[i].getClassName()).append('.').append(st[i].getMethodName())
                                            .append(':').append(st[i].getLineNumber());
                                }
                                log(sb.toString());
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.hookAllConstructors(java.io.RandomAccessFile.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        Object a0 = p.args != null && p.args.length > 0 ? p.args[0] : null;
                        String path = a0 instanceof File ? ((File) a0).getAbsolutePath()
                                : a0 instanceof String ? (String) a0 : null;
                        if (path != null && path.contains("/Download/HNT/")) log("raf " + path);
                    } catch (Throwable ignored) {
                    }
                }
            });
            try {
                Class<?> b26 = Class.forName("com.hihonor.android.thememanager.common.j", false, appCl);
                XposedBridge.hookAllMethods(b26, "getThemePackageInfoByPath", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            String path = null;
                            if (p.args != null) {
                                for (Object a : p.args) {
                                    if (a instanceof String && ((String) a).endsWith(".hnt")) { path = (String) a; break; }
                                }
                            }
                            log("pkgInfo " + path + " -> " + (p.getResult() == null ? "NULL" : p.getResult().getClass().getSimpleName())
                                    + (p.getThrowable() == null ? "" : " throw=" + p.getThrowable()));
                        } catch (Throwable ignored) {
                        }
                    }
                });
                XposedBridge.hookAllMethods(b26, "checkAllThemes", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            String r = String.valueOf(p.getResult());
                            if (r.contains("101113191")) log("checkAllThemes -> " + r.substring(0, Math.min(120, r.length())));
                            log("checkAllThemes done throw=" + p.getThrowable());
                        } catch (Throwable ignored) {
                        }
                    }
                });
            } catch (Throwable t) {
                log("hook theme classes fail " + t);
            }
            try {
                Class<?> j = Class.forName("com.hihonor.android.thememanager.common.j", false, appCl);
                XposedBridge.hookAllMethods(j, "handleThemes", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (p.args == null || p.args.length < 2 || !(p.args[1] instanceof java.util.List)) return;
                            java.util.List list = (java.util.List) p.args[1];
                            File dir = new File(THEMES_DIR);
                            File[] fs = dir.listFiles();
                            if (fs == null) return;
                            int added = 0;
                            for (File f : fs) {
                                String n = f.getName();
                                if (!f.isFile() || !n.endsWith(".hnt")) continue;
                                if (!n.startsWith("hnt") && !n.matches("^[0-9a-f]{32}\\.hnt$")) continue;
                                String path = f.getAbsolutePath();
                                if (list.contains(path)) continue;
                                list.add(path);
                                added++;
                            }
                            if (added > 0) log("handleThemes += " + added + " (size " + list.size() + ")");
                        } catch (Throwable t) {
                            log("handleThemes err " + t);
                        }
                    }
                });
            } catch (Throwable t) {
                log("hook handleThemes fail " + t);
            }
            try {
                final Class<?> themeInfoCls = Class.forName("com.hihonor.android.thememanager.common.ThemeInfo", false, appCl);
                final Object contentUri = themeInfoCls.getField("CONTENT_URI").get(null);
                XposedBridge.hookAllMethods(themeInfoCls, "getThemeInstallInfo", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            Object r = p.getResult();
                            if (!(r instanceof java.util.List)) { log("list r=null"); return; }
                            java.util.List<?> l = (java.util.List<?>) r;
                            StringBuilder sb = new StringBuilder("list size=" + l.size());
                            int k = 0;
                            for (Object o : l) {
                                if (k++ >= 6) break;
                                sb.append(" | ").append(String.valueOf(o));
                            }
                            log(sb.toString());
                        } catch (Throwable t) {
                            log("list hook err " + t);
                        }
                    }
                });
                log("CONTENT_URI=" + contentUri);
                try {
                    final Class<?> srcCls = Class.forName("com.hihonor.android.thememanager.mvvm.download.MyDownloadSource", false, appCl);
                    XposedBridge.hookAllMethods(srcCls, "loadLocalThemeData", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                Object r = p.getResult();
                                if (!(r instanceof java.util.List)) { log("src r=" + r); return; }
                                java.util.List<?> l = (java.util.List<?>) r;
                                StringBuilder sb = new StringBuilder("src size=" + l.size());
                                int k = 0;
                                for (Object o : l) {
                                    if (k++ >= 6) break;
                                    sb.append(" | ").append(String.valueOf(o));
                                }
                                log(sb.toString());
                            } catch (Throwable t) {
                                log("src hook err " + t);
                            }
                        }
                    });
                } catch (Throwable t) {
                    log("src hook fail " + t);
                }
                XposedBridge.hookAllMethods(android.content.ContentResolver.class, "query", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            Object uri = p.args != null && p.args.length > 0 ? p.args[0] : null;
                            Object cur = p.getResult();
                            if (uri == null || !(cur instanceof android.database.Cursor)) return;
                            if (!uri.toString().equals(String.valueOf(contentUri))) return;
                            if (qLog++ < 12) {
                                String sel = p.args.length > 2 ? String.valueOf(p.args[2]) : "null";
                                String[] a3 = p.args.length > 3 && p.args[3] instanceof String[] ? (String[]) p.args[3] : null;
                                String srt = p.args.length > 4 ? String.valueOf(p.args[4]) : "null";
                                log("Q sel=" + sel + " args=" + java.util.Arrays.toString(a3) + " sort=" + srt);
                            }
                            android.database.Cursor c = (android.database.Cursor) cur;
                            String[] cols = c.getColumnNames();
                            java.util.List<Object[]> rows = new java.util.ArrayList<Object[]>();
                            int ppIdx = -1;
                            for (int i = 0; i < cols.length; i++) if ("package_path".equals(cols[i])) ppIdx = i;
                            c.moveToPosition(-1);
                            while (c.moveToNext()) {
                                if (ppIdx >= 0) {
                                    String pp = c.getString(ppIdx);
                                    if (pp != null && pp.contains(MARK)) continue;
                                }
                                Object[] row = new Object[cols.length];
                                for (int i = 0; i < cols.length; i++) row[i] = readCell(c, i);
                                rows.add(row);
                            }
                            int added = 0;
                            Object[] tpl = null;
                            for (Object[] row : rows) {
                                String pp = null;
                                for (int i = 0; i < cols.length; i++) if ("package_path".equals(cols[i])) pp = (String) row[i];
                                if (pp != null && pp.endsWith(".hwt") && !pp.contains(MARK)) { tpl = row; break; }
                            }
                            android.content.Context qctx = app;
                            for (Object[] extra : synthRows(qctx, cols, tpl, rows)) {
                                rows.add(extra);
                                added++;
                            }
                            if (added == 0) return;
                            android.database.MatrixCursor mc = new android.database.MatrixCursor(cols);
                            for (Object[] row : rows) mc.addRow(row);
                            p.setResult(mc);
                            log("inject rows=" + added);
                        } catch (Throwable t) {
                            log("query hook err " + t);
                        }
                    }
                });
            } catch (Throwable t) {
                log("hook query fail " + t);
            }
            log("hook installed");
        } catch (Throwable t) {
            log("hook fail " + t);
        }
    }


    /** 自愈：缺 Honor/Themes 里的解锁副本就当场解一份出来（密钥在主题管理器自己的 keystore 里）。 */
    static void heal(android.content.Context ctx, String hitop) {
        if (ctx == null || hitop == null || hitop.length() == 0) return;
        File hwt = new File(THEMES_DIR, "hnt_" + hitop + ".hwt");
        File cache = new File(CACHE_DIR, "hnt_" + hitop + ".hwt");
        File title = new File(THEMES_DIR, "hnt_" + hitop + ".title");
        if (hwt.isFile() && hwt.length() > 0 && cache.isDirectory() && title.isFile()) return;
        long now = System.currentTimeMillis();
        Long prev = lastTry.get(hitop);
        if (prev != null && now - prev.longValue() < 15000L) return;
        lastTry.put(hitop, Long.valueOf(now));
        try {
            byte[] raw = readAll(new File(HNT_DIR, hitop + ".hnt"));
            if (raw == null) return;
            Hnt.Info info = Hnt.parse(raw);
            if (!info.hashOk) { log("heal " + hitop + " hash bad"); return; }
            byte[] key = KeyLib.keyFor(ctx, hitop);
            if (key == null) { log("heal " + hitop + " no key"); return; }
            byte[] zip = Hnt.decrypt(info, key);
            if (zip == null || zip.length < 4) { log("heal " + hitop + " decrypt fail"); return; }
            writeFile(hwt, zip);
            byte[] desc = extractCache(zip, cache);
            String[] meta = titleOf(desc, hitop);
            String js = "{\"title\":\"" + meta[0] + "\",\"designer\":\"" + meta[1] + "\"}";
            writeFile(title, js.getBytes("UTF-8"));
            log("heal " + hitop + " ok zip=" + zip.length);
        } catch (Throwable t) {
            log("heal " + hitop + " fail " + t);
        }
    }

    /** 把 description.xml 与 preview/* 抽到 .cache 目录，返回 description.xml 字节。 */
    static byte[] extractCache(byte[] zip, File cache) throws Exception {
        java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip));
        byte[] desc = null;
        try {
            java.util.zip.ZipEntry e;
            byte[] buf = new byte[1 << 16];
            while ((e = in.getNextEntry()) != null) {
                String n = e.getName();
                if (e.isDirectory()) continue;
                boolean want = "description.xml".equals(n) || n.startsWith("preview/");
                if (!want) continue;
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                int r;
                while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
                byte[] data = bo.toByteArray();
                if ("description.xml".equals(n)) desc = data;
                File out = new File(cache, n);
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                writeFile(out, data);
            }
        } finally {
            in.close();
        }
        return desc;
    }

    /** 从 description.xml 抠中文标题与设计者。 */
    static String[] titleOf(byte[] desc, String fallback) {
        String[] out = new String[]{fallback, ""};
        if (desc == null) return out;
        try {
            String xml = new String(desc, "UTF-8");
            String cn = tag(xml, "title-cn");
            String t = tag(xml, "title");
            if (cn != null && cn.length() > 0) out[0] = cn;
            else if (t != null && t.length() > 0) out[0] = t;
            String d = tag(xml, "designer");
            if (d != null) out[1] = d;
        } catch (Throwable ignored) {
        }
        return out;
    }

    static String tag(String xml, String name) {
        int i = xml.indexOf("<" + name);
        if (i < 0) return null;
        int s = xml.indexOf('>', i);
        if (s < 0) return null;
        int e = xml.indexOf("</" + name, s);
        if (e < 0) return null;
        return xml.substring(s + 1, e).trim();
    }

    static byte[] readAll(File f) throws Exception {
        if (!f.isFile()) return null;
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream((int) Math.min(f.length(), 1 << 20));
            byte[] b = new byte[1 << 16];
            int r;
            while ((r = in.read(b)) > 0) bo.write(b, 0, r);
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }

    static void writeFile(File f, byte[] data) throws Exception {
        File p = f.getParentFile();
        if (p != null) p.mkdirs();
        java.io.FileOutputStream o = new java.io.FileOutputStream(f);
        try {
            o.write(data);
        } finally {
            o.close();
        }
    }

    private static boolean isThemesDir(String path) {
        return path != null && (path.endsWith("/Honor/Themes") || path.endsWith("/Themes"));
    }

    private static void before(XC_MethodHook.MethodHookParam p) {
        Object o = p.thisObject;
        if (!(o instanceof File)) return;
        String path = ((File) o).getAbsolutePath();
        if (isThemesDir(path)) log("scan " + path);
    }

    private static void handle(XC_MethodHook.MethodHookParam p) {
        Object o = p.thisObject;
        if (!(o instanceof File)) return;
        File dir = (File) o;
        if (!isThemesDir(dir.getAbsolutePath())) return;

        Object res = p.getResult();
        File[] arr = res instanceof File[] ? (File[]) res : null;
        File[] more = new File(HNT_DIR).listFiles();
        if (more == null || more.length == 0) {
            log("themes list: no files in HNT");
            return;
        }
        List<File> out = new ArrayList<File>();
        HashSet<String> names = new HashSet<String>();
        if (arr != null) {
            for (File f : arr) {
                out.add(f);
                names.add(f.getName());
            }
        }
        int added = 0;
        for (File f : more) {
            String n = f.getName();
            if (f.isFile() && (n.endsWith(".hnt") || n.endsWith(".hwt")) && !names.contains(n)) {
                out.add(f);
                added++;
            }
        }
        log("themes list -> " + (arr == null ? 0 : arr.length) + " + " + added);
        if (added > 0) p.setResult(out.toArray(new File[0]));
    }

    /** 从一个 Cursor 当前行读出第 i 列，保留数字类型（MatrixCursor 需要）。 */
    static Object readCell(android.database.Cursor c, int i) {
        try {
            switch (c.getType(i)) {
                case android.database.Cursor.FIELD_TYPE_NULL:
                    return null;
                case android.database.Cursor.FIELD_TYPE_INTEGER:
                    return c.getLong(i);
                case android.database.Cursor.FIELD_TYPE_FLOAT:
                    return c.getDouble(i);
                case android.database.Cursor.FIELD_TYPE_BLOB:
                    return c.getBlob(i);
                default:
                    return c.getString(i);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** 把 下载/HNT 下的 .hnt 变成主题App 能吃的行（方案 A：伪造安装记录）。 */
    static List<Object[]> synthRows(android.content.Context ctx, String[] cols, Object[] tpl, List<Object[]> rows) {
        List<Object[]> out = new ArrayList<Object[]>();
        File dir = new File(HNT_DIR);
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        java.util.Arrays.sort(fs);
        int n = 0;
        for (File f : fs) {
            String fn = f.getName();
            if (!f.isFile() || !fn.toLowerCase(Locale.US).endsWith(".hnt")) continue;
            String hitop = fn.substring(0, fn.length() - 4);
            heal(ctx, hitop);
            File hwt = new File(THEMES_DIR, "hnt_" + hitop + ".hwt");
            if (!hwt.isFile() || hwt.length() == 0) continue;
            String[] meta = readMeta(hitop);
            Object[] row = tpl != null ? java.util.Arrays.copyOf(tpl, tpl.length) : new Object[cols.length];
            long now = System.currentTimeMillis() / 1000L;
            for (int i = 0; i < cols.length; i++) {
                String c = cols[i];
                if ("_id".equals(c)) row[i] = Long.valueOf(900100L + n);
                else if ("title".equals(c) || "cn_title".equals(c)) row[i] = meta[0];
                else if ("author".equals(c) || "designer".equals(c)) row[i] = meta[1];
                else if ("brief_info".equals(c)) row[i] = meta[2];
                else if ("type".equals(c)) row[i] = Long.valueOf(33);
                else if ("status".equals(c)) row[i] = Long.valueOf(0);
                else if ("package_type".equals(c)) row[i] = Long.valueOf(3);
                else if ("spare_one".equals(c)) row[i] = "internal";
                else if ("hitop_id".equals(c)) row[i] = hitop;
                else if ("package_path".equals(c)) row[i] = THEMES_DIR + "/hnt_" + hitop + ".hwt";
                else if ("package_name".equals(c)) row[i] = "hnt_" + hitop + ".hwt";
                else if ("version".equals(c)) row[i] = "8.0.1";
                else if ("modify_time".equals(c)) row[i] = Long.valueOf(now);
                else if ("order_key".equals(c) || "spare_two".equals(c) || "containWidget".equals(c)
                        || "aodType".equals(c)) row[i] = Long.valueOf(0);
                else if ("service_id".equals(c)) row[i] = Long.valueOf(-1);
                else if ("screen".equals(c)) row[i] = "FHD";
                else if ("font".equals(c)) row[i] = "Default";
                else if ("cn_font".equals(c)) row[i] = "默认";
                else row[i] = null;
            }
            out.add(row);
            n++;
        }
        return out;
    }

    /** 读 App 打包时写下的 title/designer（JSON 很小，直接抠）。 */
    static String[] readMeta(String hitop) {
        String[] out = new String[]{hitop, "", ""};
        try {
            File f = new File(THEMES_DIR, "hnt_" + hitop + ".title");
            if (!f.isFile()) return out;
            byte[] b = new byte[(int) Math.min(f.length(), 8192)];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                int off = 0, r;
                while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r;
            } finally {
                in.close();
            }
            String js = new String(b, "UTF-8");
            String t = json(js, "title");
            if (t != null && t.length() > 0) out[0] = t;
            String d = json(js, "designer");
            if (d != null) out[1] = d;
        } catch (Throwable ignored) {
        }
        return out;
    }

    static String json(String js, String key) {
        int i = js.indexOf("\"" + key + "\"");
        if (i < 0) return null;
        i = js.indexOf('"', i + key.length() + 2);
        if (i < 0) return null;
        int j = js.indexOf('"', i + 1);
        if (j < 0) return null;
        return js.substring(i + 1, j);
    }

    /** 把包复制到 Honor/Themes/hnt_<id>.hnt（主题App 只认这个目录）。 */
    static void ensureCopy(File src, String hitop) {
        try {
            File dst = new File(THEMES_DIR, "hnt_" + hitop + ".hnt");
            if (dst.exists() && dst.length() == src.length()) return;
            java.io.FileInputStream in = new java.io.FileInputStream(src);
            try {
                java.io.FileOutputStream o = new java.io.FileOutputStream(dst);
                try {
                    byte[] b = new byte[1 << 16];
                    int r;
                    while ((r = in.read(b)) > 0) o.write(b, 0, r);
                } finally {
                    o.close();
                }
            } finally {
                in.close();
            }
            log("copied " + dst.getAbsolutePath() + " " + dst.length());
        } catch (Throwable t) {
            log("copy fail " + t);
        }
    }
}
