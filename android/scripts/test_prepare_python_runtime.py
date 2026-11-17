import runpy
import unittest
from pathlib import Path


RUNTIME_SCRIPT = Path(__file__).with_name("prepare-python-runtime.py")
RUNTIME = runpy.run_path(str(RUNTIME_SCRIPT))


class PythonRuntimePackageFilterTest(unittest.TestCase):
    def test_excludes_only_development_extension_modules(self):
        is_development_extension = RUNTIME["is_development_extension"]

        for module in (
            "_testcapi",
            "_ctypes_test",
            "_xxtestfuzz",
            "xxlimited",
            "xxsubtype",
        ):
            with self.subTest(module=module):
                self.assertTrue(is_development_extension(module))

        for module in ("_socket", "_sqlite3", "_ssl", "array", "zlib"):
            with self.subTest(module=module):
                self.assertFalse(is_development_extension(module))

    def test_file_filter_is_limited_to_native_extensions_under_lib_dynload(self):
        ignore = RUNTIME["ignore_development_extensions"]

        self.assertEqual(
            ignore(
                "/package/lib-dynload",
                [
                    "_testcapi.cpython-314.so",
                    "_socket.cpython-314.so",
                    "_test.py",
                    "_ctypes_test.cpython-314.so",
                ],
            ),
            {"_testcapi.cpython-314.so", "_ctypes_test.cpython-314.so"},
        )
        self.assertEqual(
            ignore("/package/os", ["_testcapi.cpython-314.so"]),
            set(),
        )


if __name__ == "__main__":
    unittest.main()
