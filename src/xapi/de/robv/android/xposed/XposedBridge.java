package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.util.HashSet;
import java.util.Set;

/** 编译期占位（真实实现由 LSPosed 运行时提供）。 */
public final class XposedBridge {

    private XposedBridge() {
    }

    public static void log(String text) {
    }

    public static void log(Throwable t) {
    }

    public static XC_MethodHook.Unhook hookMethod(Member m, XC_MethodHook cb) {
        return new XC_MethodHook.Unhook();
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> clazz, String name, XC_MethodHook cb) {
        return new HashSet<XC_MethodHook.Unhook>();
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> clazz, XC_MethodHook cb) {
        return new HashSet<XC_MethodHook.Unhook>();
    }
}
