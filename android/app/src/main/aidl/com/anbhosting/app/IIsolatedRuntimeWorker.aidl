package com.anbhosting.app;

import android.os.ParcelFileDescriptor;

/** Isolated CPython worker contract for bounded local HTTP handlers. */
interface IIsolatedRuntimeWorker {
    int getProcessUid();
    String readGrantedFile(in ParcelFileDescriptor file);
    boolean privateStorageProbeIsReadable();
    String startPythonRuntime(String standardLibraryPath);
    String executePythonHandler(String source, String requestJson);
}
