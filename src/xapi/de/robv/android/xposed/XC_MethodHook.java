package de.robv.android.xposed;

import java.lang.reflect.Member;

/** 编译期占位（真实实现由 LSPosed 运行时提供）。 */
public abstract class XC_MethodHook {

    public static final class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        private Object result;
        private Throwable throwable;

        public Object getResult() {
            return result;
        }

        public void setResult(Object r) {
            result = r;
            throwable = null;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public void setThrowable(Throwable t) {
            throwable = t;
            result = null;
        }

        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) throw throwable;
            return result;
        }
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static final class Unhook {
        public void unhook() {
        }
    }
}
