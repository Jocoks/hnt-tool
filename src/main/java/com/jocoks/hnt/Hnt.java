package com.jocoks.hnt;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 荣耀主题包 .hnt 容器格式。
 *
 * 布局：[u32 jsonLen][u32 encLen][u32 hashLen]
 *       + JSON {"hitopId":"...","version":"..."}  (jsonLen 字节)
 *       + IV (12 字节) + AES-128-GCM 密文(含 16 字节 tag)  (encLen = 12 + 密文长度)
 *       + sha256(JSON+IV+密文) 的小写 hex (hashLen = 64 字符)
 *
 * 密文解开后就是普通 ZIP（.hwt）。密钥为 16 字节，按 hitopId 由主题管理器的
 * AndroidKeyStore 保护，需要在本机以该 app 的 uid 解出（见 RootKey / Kh）。
 */
public final class Hnt {

    public static final int HEADER = 12;
    public static final int IV_LEN = 12;
    public static final int HASH_LEN = 64;

    private static final Pattern P_ID = Pattern.compile("\"hitopId\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern P_VER = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]*)\"");

    public static final class Info {
        public int jsonLen;
        public int encLen;
        public int hashLen;
        public String json = "";
        public String hitopId = "";
        public String version = "";
        public byte[] iv = new byte[0];
        public byte[] ct = new byte[0];
        public String tailHex = "";
        public boolean hashOk;
        public int size;
    }

    private Hnt() {
    }

    public static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    public static byte[] putLe32(int v) {
        return new byte[]{(byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24)};
    }

    public static Info parse(byte[] d) throws Exception {
        if (d == null || d.length < HEADER + IV_LEN + 16) {
            throw new Exception("文件太小，不是 .hnt");
        }
        Info in = new Info();
        in.size = d.length;
        in.jsonLen = le32(d, 0);
        in.encLen = le32(d, 4);
        in.hashLen = le32(d, 8);
        if (in.jsonLen <= 0 || in.jsonLen > 65536) {
            throw new Exception("头部 JSON 长度异常(" + in.jsonLen + ")，不是 .hnt 文件");
        }
        if (in.encLen < IV_LEN + 16) {
            throw new Exception("头部密文长度异常(" + in.encLen + ")");
        }
        if (in.hashLen < 0 || in.hashLen > 256) {
            throw new Exception("头部校验长度异常(" + in.hashLen + ")");
        }
        long need = (long) HEADER + in.jsonLen + in.encLen + in.hashLen;
        if (need > d.length) {
            throw new Exception("文件被截断：需要 " + need + " 字节，实际 " + d.length);
        }
        int js = HEADER;
        int vs = js + in.jsonLen;
        int cs = vs + IV_LEN;
        int ce = vs + in.encLen;
        int hs = ce;
        in.json = new String(d, js, in.jsonLen, "UTF-8");
        in.iv = new byte[IV_LEN];
        System.arraycopy(d, vs, in.iv, 0, IV_LEN);
        in.ct = new byte[in.encLen - IV_LEN];
        System.arraycopy(d, cs, in.ct, 0, in.ct.length);
        in.tailHex = new String(d, hs, in.hashLen, "UTF-8").trim();
        Matcher m = P_ID.matcher(in.json);
        if (m.find()) in.hitopId = m.group(1);
        m = P_VER.matcher(in.json);
        if (m.find()) in.version = m.group(1);
        // sha256(JSON + IV + 密文)
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(d, js, vs - js);
        md.update(d, vs, ce - vs);
        in.hashOk = toHex(md.digest()).equalsIgnoreCase(in.tailHex);
        return in;
    }

    public static byte[] decrypt(Info in, byte[] key) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, in.iv));
        return c.doFinal(in.ct);
    }

    /** 用给定 JSON 头和密钥把 ZIP 字节重新封装成 .hnt。 */
    public static byte[] repack(byte[] zip, String json, byte[] key) throws Exception {
        byte[] js = json.getBytes("UTF-8");
        byte[] iv = new byte[IV_LEN];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(zip);
        int encLen = IV_LEN + ct.length;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(js);
        md.update(iv);
        md.update(ct);
        byte[] hash = toHex(md.digest()).getBytes("US-ASCII");

        byte[] out = new byte[HEADER + js.length + encLen + hash.length];
        System.arraycopy(putLe32(js.length), 0, out, 0, 4);
        System.arraycopy(putLe32(encLen), 0, out, 4, 4);
        System.arraycopy(putLe32(hash.length), 0, out, 8, 4);
        System.arraycopy(js, 0, out, HEADER, js.length);
        System.arraycopy(iv, 0, out, HEADER + js.length, IV_LEN);
        System.arraycopy(ct, 0, out, HEADER + js.length + IV_LEN, ct.length);
        System.arraycopy(hash, 0, out, HEADER + js.length + encLen, hash.length);
        return out;
    }

    public static String makeJson(String hitopId, String version) {
        return "{\"hitopId\":\"" + hitopId + "\",\"version\":\"" + version + "\"}";
    }

    public static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xff;
            if (v < 16) sb.append('0');
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }

    public static byte[] fromHex(String s) {
        s = s.trim().replaceAll("[^0-9a-fA-F]", "");
        if ((s.length() & 1) != 0) throw new IllegalArgumentException("hex 长度不是偶数");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static boolean isHex(String s) {
        return s != null && s.trim().matches("[0-9a-fA-F]{32,64}");
    }
}
