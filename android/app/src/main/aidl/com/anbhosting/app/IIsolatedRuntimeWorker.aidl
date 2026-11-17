package com.anbhosting.app;

import android.os.ParcelFileDescriptor;

/** Narrow diagnostics and file-descriptor probe used before enabling site code. */
interface IIsolatedRuntimeWorker {
    int getProcessUid();
    String[] listGrantedDirectory(in ParcelFileDescriptor directory);
    boolean privateStorageProbeIsReadable();
}
