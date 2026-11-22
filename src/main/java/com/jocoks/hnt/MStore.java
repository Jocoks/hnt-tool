package com.jocoks.hnt;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.OutputStream;

/**
 * 默认输出位置：下载/HNT/。
 *
 * 用 MediaStore 往公共「下载」目录里写自己的文件，不需要任何存储权限，也不用系统弹窗。
 * 只动自己写过的文件（同名先删），不碰别的 App 的东西。
 */
public final class MStore {

    public static final String BASE = "Download/HNT/";

    private MStore() {
    }

    /** 清掉某个子目录下自己以前写的文件（打不开/删不掉就算了）。 */
    public static void resetDir(Context c, String relPath) {
        try {
            String sel = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?";
            c.getContentResolver().delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    sel, new String[]{relPath + "%"});
        } catch (Throwable ignored) {
        }
    }

    public static OutputStream create(Context c, String relPath, String name, String mime)
            throws Exception {
        try {
            String sel = MediaStore.MediaColumns.RELATIVE_PATH + "=?"
                    + " AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?";
            c.getContentResolver().delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    sel, new String[]{relPath, name});
        } catch (Throwable ignored) {
        }
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, relPath);
        Uri u = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (u == null) throw new Exception("无法在 " + relPath + " 下创建 " + name);
        OutputStream os = c.getContentResolver().openOutputStream(u, "w");
        if (os == null) throw new Exception("无法写入 " + relPath + name);
        return os;
    }

    public static Uri saveFile(Context c, String name, byte[] data) throws Exception {
        OutputStream os = create(c, BASE, name, Saf.guessMime(name));
        try {
            os.write(data);
            os.flush();
        } finally {
            os.close();
        }
        try {
            String sel = MediaStore.MediaColumns.RELATIVE_PATH + "=?"
                    + " AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?";
            android.database.Cursor cur = c.getContentResolver().query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.MediaColumns._ID}, sel, new String[]{BASE, name}, null);
            if (cur != null) {
                try {
                    if (cur.moveToFirst()) {
                        return android.content.ContentUris.withAppendedId(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI, cur.getLong(0));
                    }
                } finally {
                    cur.close();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
