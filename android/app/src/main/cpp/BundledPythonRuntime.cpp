#include <jni.h>
#include <Python.h>

#include <cstring>
#include <cstdio>
#include <mutex>

#include <fcntl.h>
#include <unistd.h>

namespace {

std::mutex runtime_mutex;
constexpr Py_ssize_t kMaximumSourceBytes = 256 * 1024;
constexpr Py_ssize_t kMaximumRequestBytes = 64 * 1024;
constexpr Py_ssize_t kMaximumResponseBytes = 256 * 1024;

void throw_runtime_exception(JNIEnv *environment, const char *message) {
    jclass exception_class = environment->FindClass("java/lang/RuntimeException");
    if (exception_class != nullptr) {
        environment->ThrowNew(exception_class, message != nullptr ? message : "CPython failed");
    }
}

PyObject *unicode_from_java(JNIEnv *environment, jstring value) {
    if (value == nullptr) {
        PyErr_SetString(PyExc_ValueError, "Python input is missing");
        return nullptr;
    }
    const jsize character_count = environment->GetStringLength(value);
    const jchar *characters = environment->GetStringChars(value, nullptr);
    if (characters == nullptr) {
        PyErr_NoMemory();
        return nullptr;
    }

    int byte_order = -1;  // Android ABIs supported by this runtime are little-endian.
    PyObject *result = PyUnicode_DecodeUTF16(
        reinterpret_cast<const char *>(characters),
        static_cast<Py_ssize_t>(character_count) * static_cast<Py_ssize_t>(sizeof(jchar)),
        "strict",
        &byte_order
    );
    environment->ReleaseStringChars(value, characters);
    return result;
}

PyObject *encode_json(PyObject *json_dumps, PyObject *value) {
    PyObject *arguments = PyTuple_Pack(1, value);
    PyObject *options = PyDict_New();
    if (arguments == nullptr || options == nullptr ||
        PyDict_SetItemString(options, "allow_nan", Py_False) < 0) {
        Py_XDECREF(arguments);
        Py_XDECREF(options);
        return nullptr;
    }
    PyObject *encoded = PyObject_Call(json_dumps, arguments, options);
    Py_DECREF(arguments);
    Py_DECREF(options);
    return encoded;
}

PyObject *make_error_response(PyObject *json_dumps) {
    PyErr_Clear();
    PyObject *response = PyDict_New();
    PyObject *message = PyUnicode_FromString("Python handler execution failed");
    if (response == nullptr || message == nullptr ||
        PyDict_SetItemString(response, "error", message) < 0) {
        Py_XDECREF(response);
        Py_XDECREF(message);
        PyErr_Clear();
        return nullptr;
    }
    Py_DECREF(message);
    PyObject *encoded = encode_json(json_dumps, response);
    Py_DECREF(response);
    if (encoded == nullptr) PyErr_Clear();
    return encoded;
}

jstring java_ascii_string(JNIEnv *environment, PyObject *value) {
    if (value == nullptr || !PyUnicode_Check(value)) return nullptr;
    Py_ssize_t length = 0;
    const char *utf8 = PyUnicode_AsUTF8AndSize(value, &length);
    if (utf8 == nullptr || length > kMaximumResponseBytes) return nullptr;
    for (Py_ssize_t index = 0; index < length; ++index) {
        if (static_cast<unsigned char>(utf8[index]) > 0x7f) return nullptr;
    }
    return environment->NewStringUTF(utf8);
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

    // This app-process smoke check intentionally executes only fixed code. Site
    // handlers use nativeRunIsolatedHandler from the dedicated isolated process.
    if (PyRun_SimpleString(
            "import os, socket, sqlite3, ssl, sys\n"
            "assert sys.version_info[:2] == (3, 14)\n"
            "assert os.path.isdir(sys.prefix)\n"
            "assert socket.socket is not None\n"
            "assert sqlite3.sqlite_version\n"
            "assert ssl.OPENSSL_VERSION\n"
            "import importlib.machinery\n"
            "native_dir = os.path.join(sys.prefix, 'lib', 'python3.14', 'lib-dynload')\n"
            "suffixes = importlib.machinery.EXTENSION_SUFFIXES\n"
            "native_modules = set()\n"
            "for filename in os.listdir(native_dir):\n"
            "    for suffix in suffixes:\n"
            "        if filename.endswith(suffix):\n"
            "            native_modules.add(filename[:-len(suffix)])\n"
            "            break\n"
            "assert len(native_modules) >= 50, f'Only {len(native_modules)} production extensions were packaged'\n"
            "native_failures = []\n"
            "for module_name in sorted(native_modules):\n"
            "    try:\n"
            "        __import__(module_name)\n"
            "    except BaseException as error:\n"
            "        native_failures.append(f'{module_name}: {error!r}')\n"
            "assert not native_failures, 'Native extension import failures: ' + '; '.join(native_failures)\n") != 0) {
        PyErr_Print();
        throw_runtime_exception(environment, "CPython started but a required runtime module failed to import");
        return nullptr;
    }

    return environment->NewStringUTF(Py_GetVersion());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_anbhosting_app_BundledPythonRuntime_nativeInitializeIsolatedRuntime(
    JNIEnv *environment,
    jobject /* receiver */,
    jint runtime_prefix_fd
) {
    std::lock_guard<std::mutex> lock(runtime_mutex);
    if (Py_IsInitialized()) {
        throw_runtime_exception(environment, "The isolated CPython worker is already initialized");
        return nullptr;
    }
    if (runtime_prefix_fd < 0 || fcntl(runtime_prefix_fd, F_GETFD) == -1) {
        throw_runtime_exception(environment, "The isolated CPython prefix file descriptor is invalid");
        return nullptr;
    }

    char prefix_path[64];
    char standard_library_path[128];
    char native_library_path[160];
    const int path_length = std::snprintf(
        prefix_path,
        sizeof(prefix_path),
        "/proc/self/fd/%d",
        runtime_prefix_fd
    );
    if (path_length <= 0 || static_cast<size_t>(path_length) >= sizeof(prefix_path)) {
        throw_runtime_exception(environment, "The isolated CPython prefix path is invalid");
        return nullptr;
    }
    const int standard_library_length = std::snprintf(
        standard_library_path,
        sizeof(standard_library_path),
        "%s/lib/python3.14",
        prefix_path
    );
    const int native_library_length = std::snprintf(
        native_library_path,
        sizeof(native_library_path),
        "%s/lib/python3.14/lib-dynload",
        prefix_path
    );
    if (standard_library_length <= 0 ||
        static_cast<size_t>(standard_library_length) >= sizeof(standard_library_path) ||
        native_library_length <= 0 ||
        static_cast<size_t>(native_library_length) >= sizeof(native_library_path)) {
        throw_runtime_exception(environment, "The isolated CPython module paths are invalid");
        return nullptr;
    }

    wchar_t *prefix_wide = Py_DecodeLocale(prefix_path, nullptr);
    wchar_t *standard_library_wide = Py_DecodeLocale(standard_library_path, nullptr);
    wchar_t *native_library_wide = Py_DecodeLocale(native_library_path, nullptr);
    if (prefix_wide == nullptr || standard_library_wide == nullptr || native_library_wide == nullptr) {
        PyMem_RawFree(prefix_wide);
        PyMem_RawFree(standard_library_wide);
        PyMem_RawFree(native_library_wide);
        throw_runtime_exception(environment, "The isolated CPython module paths could not be decoded");
        return nullptr;
    }

    PyConfig config;
    PyConfig_InitIsolatedConfig(&config);
    config.use_environment = 0;
    config.site_import = 0;
    config.write_bytecode = 0;
    config.safe_path = 1;
    config.module_search_paths_set = 1;

    PyStatus status = PyConfig_SetString(&config, &config.prefix, prefix_wide);
    if (!PyStatus_Exception(status)) {
        status = PyConfig_SetString(&config, &config.base_prefix, prefix_wide);
    }
    if (!PyStatus_Exception(status)) {
        status = PyConfig_SetString(&config, &config.exec_prefix, prefix_wide);
    }
    if (!PyStatus_Exception(status)) {
        status = PyConfig_SetString(&config, &config.base_exec_prefix, prefix_wide);
    }
    if (!PyStatus_Exception(status)) {
        status = PyWideStringList_Append(&config.module_search_paths, standard_library_wide);
    }
    if (!PyStatus_Exception(status)) {
        status = PyWideStringList_Append(&config.module_search_paths, native_library_wide);
    }
    PyMem_RawFree(prefix_wide);
    PyMem_RawFree(standard_library_wide);
    PyMem_RawFree(native_library_wide);
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

    const int smoke_status = PyRun_SimpleString(
        "import json, sys\n"
        "assert sys.version_info[:2] == (3, 14)\n"
        "assert len(sys.path) == 2\n"
        "assert json.loads('{\"ready\":true}')['ready'] is True\n"
    );
    if (smoke_status != 0) {
        PyErr_Print();
        PyEval_SaveThread();
        throw_runtime_exception(environment, "CPython could not load its standard library in the isolated worker");
        return nullptr;
    }

    const char *version = Py_GetVersion();
    jstring result = environment->NewStringUTF(version);
    PyEval_SaveThread();
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_anbhosting_app_BundledPythonRuntime_nativeRunIsolatedHandler(
    JNIEnv *environment,
    jobject /* receiver */,
    jstring source_value,
    jstring request_value
) {
    std::lock_guard<std::mutex> lock(runtime_mutex);
    if (!Py_IsInitialized()) {
        throw_runtime_exception(environment, "The isolated CPython worker has not been started");
        return nullptr;
    }

    const PyGILState_STATE gil_state = PyGILState_Ensure();
    PyObject *source = unicode_from_java(environment, source_value);
    PyObject *request_json = unicode_from_java(environment, request_value);
    if (source == nullptr || request_json == nullptr) {
        Py_XDECREF(source);
        Py_XDECREF(request_json);
        PyErr_Clear();
        PyGILState_Release(gil_state);
        throw_runtime_exception(environment, "Python source and request must be valid Unicode strings");
        return nullptr;
    }

    Py_ssize_t source_length = 0;
    const char *source_utf8 = PyUnicode_AsUTF8AndSize(source, &source_length);
    Py_ssize_t request_length = 0;
    const char *request_utf8 = PyUnicode_AsUTF8AndSize(request_json, &request_length);
    if (source_utf8 == nullptr || request_utf8 == nullptr ||
        source_length > kMaximumSourceBytes || request_length > kMaximumRequestBytes ||
        std::memchr(source_utf8, '\0', static_cast<size_t>(source_length)) != nullptr ||
        std::memchr(request_utf8, '\0', static_cast<size_t>(request_length)) != nullptr) {
        Py_XDECREF(source);
        Py_XDECREF(request_json);
        PyErr_Clear();
        PyGILState_Release(gil_state);
        throw_runtime_exception(environment, "Python input exceeds its size limit or contains a null character");
        return nullptr;
    }

    PyObject *json_module = PyImport_ImportModule("json");
    PyObject *json_loads = json_module != nullptr ? PyObject_GetAttrString(json_module, "loads") : nullptr;
    PyObject *json_dumps = json_module != nullptr ? PyObject_GetAttrString(json_module, "dumps") : nullptr;
    PyObject *request = json_loads != nullptr ? PyObject_CallOneArg(json_loads, request_json) : nullptr;
    PyObject *globals = PyDict_New();
    PyObject *module_name = PyUnicode_FromString("__anb_site__");
    if (globals != nullptr && module_name != nullptr) {
        PyDict_SetItemString(globals, "__builtins__", PyEval_GetBuiltins());
        PyDict_SetItemString(globals, "__name__", module_name);
    }

    PyObject *compiled = request != nullptr && globals != nullptr
        ? Py_CompileString(source_utf8, "main.py", Py_file_input)
        : nullptr;
    PyObject *evaluated = compiled != nullptr
        ? PyEval_EvalCode(compiled, globals, globals)
        : nullptr;
    PyObject *handler = evaluated != nullptr ? PyDict_GetItemString(globals, "handle_request") : nullptr;
    PyObject *response = handler != nullptr && PyCallable_Check(handler)
        ? PyObject_CallOneArg(handler, request)
        : nullptr;

    PyObject *encoded = nullptr;
    if (response != nullptr && PyDict_Check(response) && json_dumps != nullptr) {
        encoded = encode_json(json_dumps, response);
    }
    if (encoded == nullptr || !PyUnicode_Check(encoded)) {
        Py_XDECREF(encoded);
        encoded = json_dumps != nullptr ? make_error_response(json_dumps) : nullptr;
    }

    jstring result = java_ascii_string(environment, encoded);
    Py_XDECREF(encoded);
    Py_XDECREF(response);
    Py_XDECREF(evaluated);
    Py_XDECREF(compiled);
    Py_XDECREF(module_name);
    Py_XDECREF(globals);
    Py_XDECREF(request);
    Py_XDECREF(json_dumps);
    Py_XDECREF(json_loads);
    Py_XDECREF(json_module);
    Py_DECREF(request_json);
    Py_DECREF(source);
    PyErr_Clear();
    PyGILState_Release(gil_state);

    if (result == nullptr) {
        throw_runtime_exception(environment, "Python handler did not return a bounded JSON object");
    }
    return result;
}
