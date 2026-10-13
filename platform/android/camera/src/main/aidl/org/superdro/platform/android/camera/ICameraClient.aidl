// The Binder interface from the camera process to the UI process. The error kind crosses
// the process boundary as the name of CameraErrorKind, not as an
// ordinal.
package org.superdro.platform.android.camera;

oneway interface ICameraClient {
    // Sent at register: the native libraries loaded, or the load error.
    void onNativeStatus(boolean loaded, String error);

    // The reply of listModes: the status, then 5 values for each mode (UvcModeList).
    void onModes(int token, in int[] modes);

    // The camera accepted the start at negotiatedInterval (100 ns units).
    void onStartAccepted(int token, int negotiatedInterval);

    // The camera refused the start. The message ends with "(code)" (CameraCodes).
    void onStartRefused(int token, String message);

    void onFirstFrame(int run, int width, int height);

    // errorKind is null for a stop by the user.
    void onStreamEnded(int run, String errorKind, String detail);

    void onStats(int run, int frames, int width, int height, double fps);

    void onDiagnostic(int run, String text);
}
