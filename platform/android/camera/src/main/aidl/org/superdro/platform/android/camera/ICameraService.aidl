// The Binder interface from the UI process to the camera process ":camera". Each request
// has a token, and the reply comes on ICameraClient with
// the same token. The calls are one-way, so that a blocked camera process does not block the UI.
// Frames do not cross the process boundary: the camera process draws into the Surface.
package org.superdro.platform.android.camera;

import android.os.ParcelFileDescriptor;
import android.view.Surface;
import org.superdro.platform.android.camera.ICameraClient;

interface ICameraService {
    // The pid of the camera process. The UI process compares it with its own pid.
    int getPid();

    oneway void register(ICameraClient client);

    // The reply is ICameraClient.onModes with the same token.
    oneway void listModes(int token, in ParcelFileDescriptor fd, int connection);

    // The reply is onStartAccepted or onStartRefused with the same token. The token is also the
    // run of the stream events. frameInterval is the selected interval in 100 ns units.
    oneway void startStream(int token, in ParcelFileDescriptor fd, int connection, int codec, int width,
            int height, int frameInterval, in Surface surface);

    oneway void stopStream();

    oneway void closeCamera();
}
