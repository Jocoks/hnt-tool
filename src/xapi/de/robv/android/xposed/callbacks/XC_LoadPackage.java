package de.robv.android.xposed.callbacks;

import android.content.pm.ApplicationInfo;

/** 编译期占位：字段名/类型必须与真实 API 一致。 */
public class XC_LoadPackage {
    public static class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public ApplicationInfo appInfo;
        public boolean isFirstApplication;
    }
}
