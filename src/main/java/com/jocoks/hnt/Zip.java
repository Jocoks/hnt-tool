package com.jocoks.hnt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** 纯 java.util.zip 的封装：解到 Sink / 从 Source 打包，方便在 JVM 上直接测试。 */
public final class Zip {

    /** 输出端：目录和文件都按 zip 内的相对路径给出（文件路径不带尾斜杠）。 */
    public interface Sink {
        void dir(String path) throws Exception;

        OutputStream file(String path, long size) throws Exception;
    }

    /** 输入端：给出所有文件相对路径，并可按路径打开。 */
    public interface Source {
        List<String> files() throws Exception;

        InputStream open(String path) throws Exception;
    }

    /** 回调，便于汇报进度。 */
    public interface Progress {
        void on(int done, int total);
    }

    private Zip() {
    }

    public static int unzip(byte[] data, Sink sink, Progress p) throws Exception {
        ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(data));
        int n = 0;
        byte[] buf = new byte[65536];
        try {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                if (name == null || name.length() == 0) continue;
                name = name.replace('\\', '/');
                if (name.startsWith("/")) name = name.substring(1);
                if (name.indexOf("..") >= 0) continue;
                if (e.isDirectory() || name.endsWith("/")) {
                    String d = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
                    if (d.length() > 0) sink.dir(d);
                } else {
                    OutputStream out = sink.file(name, e.getSize());
                    try {
                        int r;
                        while ((r = zin.read(buf)) > 0) out.write(buf, 0, r);
                    } finally {
                        out.close();
                    }
                }
                zin.closeEntry();
                n++;
                if (p != null && (n % 20 == 0)) p.on(n, -1);
            }
        } finally {
            zin.close();
        }
        return n;
    }

    public static byte[] zip(Source src, Progress p) throws Exception {
        List<String> files = src.files();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20);
        ZipOutputStream zos = new ZipOutputStream(bos);
        zos.setMethod(ZipOutputStream.DEFLATED);
        zos.setLevel(6);
        byte[] buf = new byte[65536];
        int n = 0;
        try {
            for (int i = 0; i < files.size(); i++) {
                String name = files.get(i);
                ZipEntry e = new ZipEntry(name);
                zos.putNextEntry(e);
                InputStream in = src.open(name);
                try {
                    int r;
                    while ((r = in.read(buf)) > 0) zos.write(buf, 0, r);
                } finally {
                    in.close();
                }
                zos.closeEntry();
                n++;
                if (p != null) p.on(n, files.size());
            }
        } finally {
            zos.close();
        }
        return bos.toByteArray();
    }
}
