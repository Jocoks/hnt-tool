package com.jocoks.hnt;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Security;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 只做一件事：用调用方进程自己的 AndroidKeyStore 解出某个 hitopId 的内容密钥。
 *
 * 调用方必须是主题管理器自己（uid 匹配，Keystore 按 uid 隔离），否则 getKey 为空。
 * 不涉及 root、不 spawn 子进程。
 */
public final class KeyLib {

    public static final String THEME_PKG = "com.hihonor.android.thememanager";
    public static final String KEYDIR = "/storage/emulated/0/Download/dsh/.hntkeys";

    private KeyLib() {
    }

    /** 第一次用之前调一次：装好 keystore 的 provider 与 Cipher SPI。 */
    public static void installProvider() {
        String[] names = {"android.security.keystore2.AndroidKeyStoreProvider",
                "android.security.keystore.AndroidKeyStoreProvider"};
        for (int i = 0; i < names.length; i++) {
            try {
                Class<?> p = Class.forName(names[i]);
                try {
                    p.getMethod("install").invoke(null);
                } catch (Throwable ignored) {
                }
                try {
                    Security.addProvider((java.security.Provider) p.getConstructor().newInstance());
                } catch (Throwable ignored) {
                }
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    public static KeyStore keyStore() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        return ks;
    }

    public static List<String> aliases() {
        try {
            KeyStore ks = keyStore();
            Enumeration<String> e = ks.aliases();
            List<String> out = new ArrayList<String>();
            while (e.hasMoreElements()) out.add(e.nextElement());
            Collections.sort(out);
            return out;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    /** blob = 12 字节 IV + AES-GCM 密文；返回 16 字节内容密钥。 */
    public static byte[] contentKey(String alias, byte[] blob) throws Exception {
        KeyStore ks = keyStore();
        Key key = ks.getKey(alias, null);
        if (key == null) throw new Exception("no key for " + alias);
        byte[] iv = new byte[12];
        System.arraycopy(blob, 0, iv, 0, 12);
        Cipher c = cipher(key, iv);
        byte[] pt = c.doFinal(blob, 12, blob.length - 12);
        // 与 quiteContentKey 的实现一致：先按 UTF-8 解成字符串，再按 ISO-8859-1 取回字节
        return new String(pt, StandardCharsets.UTF_8).getBytes(StandardCharsets.ISO_8859_1);
    }

    private static Cipher cipher(Key key, byte[] iv) throws Exception {
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        Throwable last = null;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, spec);
            return c;
        } catch (Throwable t) {
            last = t;
        }
        java.security.Provider[] ps = Security.getProviders();
        for (int i = 0; i < ps.length; i++) {
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding", ps[i]);
                c.init(Cipher.DECRYPT_MODE, key, spec);
                return c;
            } catch (Throwable t) {
                last = t;
            }
        }
        throw new Exception("no provider can use keystore key: " + last);
    }


    /** 从主题管理器的 filesDir 里取某个 hitopId 的原始密钥密文并解出内容密钥；没有则 null。 */
    public static byte[] keyFor(android.content.Context ctx, String alias) {
        try {
            installProvider();
            java.io.File f = new java.io.File(ctx.getFilesDir(), sha256Hex(alias));
            if (!f.isFile()) return null;
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            byte[] b;
            try {
                b = new byte[(int) f.length()];
                int off = 0, r;
                while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r;
            } finally {
                in.close();
            }
            String hex = new String(b, "US-ASCII").trim();
            byte[] blob = Hnt.fromHex(hex);
            if (blob == null || blob.length <= 12) return null;
            return contentKey(alias, blob);
        } catch (Throwable t) {
            return null;
        }
    }

    public static String sha256Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return Hnt.toHex(md.digest(s.getBytes("UTF-8")));
    }
}
