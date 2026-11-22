package com.jocoks.hnt;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** LSPosed 模块入口：只关心主题管理器。 */
public final class XposedInit implements IXposedHookLoadPackage {

    @Override
    public void initZygote(IXposedHookZygoteInit.StartupParam sp) {
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (lp == null) return;
        try {
            if (KeyLib.THEME_PKG.equals(lp.packageName)) {
                ModBridge.start(lp.classLoader);
            }
        } catch (Throwable ignored) {
        }
    }
}
