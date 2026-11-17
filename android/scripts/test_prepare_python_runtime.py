import runpy
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile


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


class PythonStandardLibraryArchiveTest(unittest.TestCase):
    def test_archive_contains_python_sources_and_excludes_native_extensions(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "python3.14"
            (source / "encodings").mkdir(parents=True)
            (source / "lib-dynload").mkdir()
            (source / "os.py").write_text("# os", encoding="utf-8")
            (source / "encodings" / "__init__.py").write_text("# encodings", encoding="utf-8")
            (source / "lib-dynload" / "_socket.cpython-314.so").write_bytes(b"native")
            archive_path = root / "assets" / "python-stdlib.zip"

            RUNTIME["create_standard_library_archive"](source, archive_path)

            with ZipFile(archive_path) as archive:
                self.assertIn("os.py", archive.namelist())
                self.assertIn("encodings/__init__.py", archive.namelist())
                self.assertNotIn("lib-dynload/_socket.cpython-314.so", archive.namelist())


if __name__ == "__main__":
    unittest.main()
