# Debug / CI 瘦身规则：仅做无用类/方法摇树裁剪（Shrinking），不做名称混淆，不做激进字节码内联，
# 确保所有单元测试（含反射读取私有字段）、堆栈行号、Xposed Hook 与 Compose 均 100% 原样保留。
-dontobfuscate
-dontoptimize
-keepattributes *

# 保留 Eta 自身全部业务类与成员，只让 R8 裁掉庞大的第三方未使用库（如 material-icons-extended 2万+ 图标）
-keep class io.github.mangi.eta.** { *; }
