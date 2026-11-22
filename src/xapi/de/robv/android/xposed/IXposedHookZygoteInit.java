package de.robv.android.xposed;

/** 编译期占位（真实实现由 LSPosed 在运行时提供，绝不会打进 APK 的 dex）。 */
public interface IXposedHookZygoteInit {
    void initZygote(StartupParam startupParam) throws Throwable;

    final class StartupParam {
        public String modulePath;
        public boolean startsSystemServer;
    }
}
