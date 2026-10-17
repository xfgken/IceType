# JNI 入口类不可混淆：C++ 侧通过 RegisterNatives 按类名 + 方法签名绑定。
-keep class com.xfgken.icetype.rime.RimeNative { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
# 输入法服务由系统按类名实例化。
-keep class com.xfgken.icetype.ime.IceInputMethodService { *; }
-dontwarn org.jetbrains.annotations.**
