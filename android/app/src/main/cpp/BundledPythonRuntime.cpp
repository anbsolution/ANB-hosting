#include <jni.h>
#include <Python.h>

#include <mutex>

namespace {

std::mutex runtime_mutex;

void throw_runtime_exception(JNIEnv *environment, const char *message) {
    jclass exception_class = environment->FindClass("java/lang/RuntimeException");
    if (exception_class != nullptr) {
        environment->ThrowNew(exception_class, message != nullptr ? message : "CPython failed");
    }
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_anbhosting_app_BundledPythonRuntime_nativeSmokeTest(
    JNIEnv *environment,
    jobject /* receiver */,
    jstring runtime_home
) {
    std::lock_guard<std::mutex> lock(runtime_mutex);
    if (Py_IsInitialized()) {
        throw_runtime_exception(environment, "The bundled CPython process is already initialized");
        return nullptr;
    }

    const char *home = environment->GetStringUTFChars(runtime_home, nullptr);
    if (home == nullptr) {
        return nullptr;
    }

    PyConfig config;
    PyConfig_InitIsolatedConfig(&config);
    config.use_environment = 0;
    config.site_import = 0;
    config.write_bytecode = 0;
    config.safe_path = 1;

    PyStatus status = PyConfig_SetBytesString(&config, &config.home, home);
    environment->ReleaseStringUTFChars(runtime_home, home);
    if (PyStatus_Exception(status)) {
        throw_runtime_exception(environment, status.err_msg);
        PyConfig_Clear(&config);
        return nullptr;
    }

    status = Py_InitializeFromConfig(&config);
    PyConfig_Clear(&config);
    if (PyStatus_Exception(status)) {
        throw_runtime_exception(environment, status.err_msg);
        return nullptr;
    }

    // This build-time/device smoke check intentionally executes only fixed code.
    // Site code will require a separate worker process and a scoped IPC contract.
    if (PyRun_SimpleString(
            "import os, socket, sqlite3, ssl, sys\n"
            "assert sys.version_info[:2] == (3, 14)\n"
            "assert os.path.isdir(sys.prefix)\n"
            "assert socket.socket is not None\n"
            "assert sqlite3.sqlite_version\n"
            "assert ssl.OPENSSL_VERSION\n") != 0) {
        PyErr_Print();
        throw_runtime_exception(environment, "CPython started but a required runtime module failed to import");
        return nullptr;
    }

    return environment->NewStringUTF(Py_GetVersion());
}
