# Keep JNI native methods — called via reflection-like resolution.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
