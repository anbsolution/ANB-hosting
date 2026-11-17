package com.anbhosting.app;

import android.os.ParcelFileDescriptor;

/** Narrow diagnostics and file-descriptor probe used before enabling site code. */
interface IIsolatedRuntimeWorker {
    int getProcessUid();
    String readGrantedFile(in ParcelFileDescriptor file);
    boolean privateStorageProbeIsReadable();
    String startPythonRuntime(in ParcelFileDescriptor runtimeArchive);
    String executePythonHandler(String source, String requestJson);
}
