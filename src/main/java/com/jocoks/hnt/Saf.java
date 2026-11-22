package com.jocoks.hnt;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

/** SAF（文件选择器）读写：不需要任何存储权限，也不需要改系统设置。 */
public final class Saf {

    private final Context ctx;

    public Saf(Context c) {
        ctx = c;
    }

    private ContentResolver cr() {
        return ctx.getContentResolver();
    }

    /** 取文档 / 目录的显示名。 */
    public static String nameOf(Context c, Uri uri) {
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(uri,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                String s = cur.getString(0);
                if (s != null && s.length() > 0) return s;
            }
        } catch (Exception ignored) {
        } finally {
            if (cur != null) try { cur.close(); } catch (Exception ignored) { }
        }
        String s = uri.getLastPathSegment();
        if (s == null) return "未命名";
        int i = s.lastIndexOf('/');
        return i >= 0 ? s.substring(i + 1) : s;
    }


    /** 在某个目录文档下列出子项：[documentId, displayName, mimeType]。 */
    public static java.util.List<String[]> children(Context c, Uri treeUri, String parentDocId) {
        java.util.List<String[]> out = new java.util.ArrayList<String[]>();
        Cursor cur = null;
        try {
            Uri q = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
            cur = c.getContentResolver().query(q, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
            while (cur != null && cur.moveToNext()) {
                out.add(new String[]{cur.getString(0), cur.getString(1), cur.getString(2)});
            }
        } catch (Exception ignored) {
        } finally {
            if (cur != null) try { cur.close(); } catch (Exception ignored) { }
        }
        return out;
    }

    /** 按名字逐级找目录下的文件，找不到返回 null。 */
    public static Uri findFile(Context c, Uri treeUri, String dirName, String fileName) {
        try {
            String root = DocumentsContract.getTreeDocumentId(treeUri);
            java.util.List<String[]> lv1 = children(c, treeUri, root);
            for (int i = 0; i < lv1.size(); i++) {
                if (dirName.equals(lv1.get(i)[1])) {
                    java.util.List<String[]> lv2 = children(c, treeUri, lv1.get(i)[0]);
                    for (int j = 0; j < lv2.size(); j++) {
                        if (fileName.equals(lv2.get(j)[1])) {
                            return DocumentsContract.buildDocumentUriUsingTree(treeUri, lv2.get(j)[0]);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static Uri treeDoc(Uri treeUri) {
        return DocumentsContract.buildDocumentUriUsingTree(treeUri,
                DocumentsContract.getTreeDocumentId(treeUri));
    }

    /** 在选中的目录下新建一个子目录，返回它的 document uri。 */
    public Uri newSubDir(Uri treeUri, String name) throws Exception {
        Uri doc = DocumentsContract.createDocument(cr(), treeDoc(treeUri),
                DocumentsContract.Document.MIME_TYPE_DIR, name);
        if (doc == null) throw new Exception("无法在所选目录下创建 " + name);
        return doc;
    }

    public static String guessMime(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".xml")) return "text/xml";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    /** 解包输出端：在 SAF 目录树里逐级建目录、建文件。 */
    public final class TreeSink implements Zip.Sink {
        private final Uri tree;
        private final HashMap<String, Uri> dirs = new HashMap<String, Uri>();

        public TreeSink(Uri treeUri, Uri startDir) {
            tree = treeUri;
            dirs.put("", startDir);
        }

        private Uri dirDoc(String path) throws Exception {
            if (path == null || path.length() == 0) return dirs.get("");
            Uri cached = dirs.get(path);
            if (cached != null) return cached;
            int i = path.lastIndexOf('/');
            String parent = i < 0 ? "" : path.substring(0, i);
            String name = i < 0 ? path : path.substring(i + 1);
            Uri p = dirDoc(parent);
            Uri u = DocumentsContract.createDocument(cr(), p,
                    DocumentsContract.Document.MIME_TYPE_DIR, name);
            if (u == null) throw new Exception("无法创建目录：" + path);
            dirs.put(path, u);
            return u;
        }

        @Override
        public void dir(String path) throws Exception {
            dirDoc(path);
        }

        @Override
        public OutputStream file(String path, long size) throws Exception {
            int i = path.lastIndexOf('/');
            String parent = i < 0 ? "" : path.substring(0, i);
            String name = i < 0 ? path : path.substring(i + 1);
            Uri p = dirDoc(parent);
            Uri doc = DocumentsContract.createDocument(cr(), p, guessMime(name), name);
            if (doc == null) throw new Exception("无法创建文件：" + path);
            return cr().openOutputStream(doc, "w");
        }
    }

    /** 打包输入端：递归列出一个 SAF 目录树里的所有文件。 */
    public final class TreeSource implements Zip.Source {
        private final Uri tree;
        private final LinkedHashMap<String, Uri> docs = new LinkedHashMap<String, Uri>();

        public TreeSource(Uri treeUri) throws Exception {
            tree = treeUri;
            scan(treeDoc(treeUri), "");
        }

        private void scan(Uri parentDoc, String prefix) throws Exception {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                    DocumentsContract.getDocumentId(parentDoc));
            Cursor c = null;
            try {
                c = cr().query(children, new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
                if (c == null) return;
                while (c.moveToNext()) {
                    String id = c.getString(0);
                    String name = c.getString(1);
                    String mime = c.getString(2);
                    if (id == null || name == null) continue;
                    Uri child = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        scan(child, prefix + name + "/");
                    } else {
                        docs.put(prefix + name, child);
                    }
                }
            } finally {
                if (c != null) try { c.close(); } catch (Exception ignored) { }
            }
        }

        @Override
        public List<String> files() {
            return new ArrayList<String>(docs.keySet());
        }

        @Override
        public InputStream open(String path) throws Exception {
            Uri u = docs.get(path);
            if (u == null) throw new Exception("找不到文件：" + path);
            return cr().openInputStream(u);
        }
    }
}
