# 这里没有用反射，也没有需要保留的 JNI 入口；
# 可执行二进制（lib*.so）是当普通文件执行的，不参与 R8。
-keep class com.asmr.mobile.** { *; }
