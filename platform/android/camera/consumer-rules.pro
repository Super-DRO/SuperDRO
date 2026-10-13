# The JNI layer (libsuperdro_uvc.so) finds these methods by their names.
-keepclasseswithmembernames class org.superdro.platform.android.camera.UvcNative {
    native <methods>;
}
