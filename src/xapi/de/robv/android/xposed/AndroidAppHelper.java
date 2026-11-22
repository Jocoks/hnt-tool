package de.robv.android.xposed;

import android.app.Application;

/** 编译期占位：真实实现由 LSPosed 在运行时提供。 */
public final class AndroidAppHelper {
    public static Application currentApplication() {
        return null;
    }
}
