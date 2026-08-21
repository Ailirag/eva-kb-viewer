"""Тесты для упаковщика .evakb (стандартная библиотека, unittest)."""

import json
import tempfile
import unittest
import unittest.mock
import zipfile
from pathlib import Path

import build_evakb


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def make_source(root: Path, *, index: bool = True, manifest: dict | None = ...) -> Path:
    src = root / "src"
    src.mkdir(parents=True, exist_ok=True)
    if index:
        write(src / "index.html", "<html><body>Отчёт</body></html>")
    write(src / "assets" / "app.js", "console.log('ok');")
    if manifest is ...:
        manifest = {
            "format": "eva.kb",
            "version": 1,
            "title": "Тест",
            "generatedAt": "2026-08-21T12:00:00Z",
        }
    if manifest is not None:
        write(src / "manifest.json", json.dumps(manifest, ensure_ascii=False))
    return src


class BuildPackageTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)

    def test_builds_valid_package(self) -> None:
        src = make_source(self.root)
        out = self.root / "out.evakb"

        build_evakb.build_package(src, out)

        self.assertTrue(out.is_file())
        with zipfile.ZipFile(out) as zf:
            names = sorted(zf.namelist())
            self.assertEqual(names, ["assets/app.js", "index.html", "manifest.json"])
            self.assertIn("Отчёт", zf.read("index.html").decode("utf-8"))

    def test_missing_index_is_rejected(self) -> None:
        src = make_source(self.root, index=False)
        out = self.root / "out.evakb"

        with self.assertRaises(build_evakb.BuildError) as ctx:
            build_evakb.build_package(src, out)

        self.assertIn("index.html", str(ctx.exception))
        self.assertFalse(out.exists())

    def test_missing_input_dir_is_rejected(self) -> None:
        with self.assertRaises(build_evakb.BuildError):
            build_evakb.build_package(self.root / "nope", self.root / "out.evakb")

    def test_refuses_to_overwrite_without_force(self) -> None:
        src = make_source(self.root)
        out = self.root / "out.evakb"
        out.write_bytes(b"old-bytes")

        with self.assertRaises(build_evakb.BuildError) as ctx:
            build_evakb.build_package(src, out)

        self.assertIn("--force", str(ctx.exception))
        self.assertEqual(out.read_bytes(), b"old-bytes")

    def test_force_overwrites(self) -> None:
        src = make_source(self.root)
        out = self.root / "out.evakb"
        out.write_bytes(b"old-bytes")

        build_evakb.build_package(src, out, force=True)

        self.assertTrue(zipfile.is_zipfile(out))

    def test_output_is_deterministic(self) -> None:
        src = make_source(self.root)
        first = self.root / "a.evakb"
        second = self.root / "b.evakb"

        build_evakb.build_package(src, first)
        build_evakb.build_package(src, second)

        self.assertEqual(first.read_bytes(), second.read_bytes())

    def test_entries_are_sorted_in_archive(self) -> None:
        src = make_source(self.root)
        write(src / "zzz.txt", "z")
        write(src / "aaa.txt", "a")
        out = self.root / "out.evakb"

        build_evakb.build_package(src, out)

        with zipfile.ZipFile(out) as zf:
            names = zf.namelist()
        self.assertEqual(names, sorted(names))

    def test_manifest_with_unsupported_version_is_rejected(self) -> None:
        src = make_source(self.root, manifest={"format": "eva.kb", "version": 2})
        out = self.root / "out.evakb"

        with self.assertRaises(build_evakb.BuildError) as ctx:
            build_evakb.build_package(src, out)

        self.assertIn("версия", str(ctx.exception).lower())

    def test_manifest_with_broken_json_is_rejected(self) -> None:
        src = make_source(self.root, manifest=None)
        write(src / "manifest.json", "{не json")
        out = self.root / "out.evakb"

        with self.assertRaises(build_evakb.BuildError):
            build_evakb.build_package(src, out)

    def test_manifest_is_generated_when_absent(self) -> None:
        src = make_source(self.root, manifest=None)
        out = self.root / "out.evakb"

        build_evakb.build_package(src, out, title="Без манифеста")

        with zipfile.ZipFile(out) as zf:
            manifest = json.loads(zf.read("manifest.json").decode("utf-8"))
        self.assertEqual(manifest["version"], 1)
        self.assertEqual(manifest["format"], build_evakb.FORMAT_ID)
        self.assertEqual(manifest["title"], "Без манифеста")
        self.assertTrue(manifest["generatedAt"])

    def test_entry_count_limit(self) -> None:
        src = make_source(self.root)
        for i in range(5):
            write(src / f"f{i}.txt", "x")
        out = self.root / "out.evakb"

        with unittest.mock.patch.object(build_evakb, "MAX_ENTRIES", 4):
            with self.assertRaises(build_evakb.BuildError) as ctx:
                build_evakb.build_package(src, out)

        self.assertIn("файлов", str(ctx.exception))
        self.assertFalse(out.exists())

    def test_uncompressed_size_limit(self) -> None:
        src = make_source(self.root)
        write(src / "big.txt", "x" * 4096)
        out = self.root / "out.evakb"

        with unittest.mock.patch.object(build_evakb, "MAX_UNCOMPRESSED_BYTES", 1024):
            with self.assertRaises(build_evakb.BuildError) as ctx:
                build_evakb.build_package(src, out)

        self.assertIn("размер", str(ctx.exception).lower())
        self.assertFalse(out.exists())

    def test_compressed_size_limit(self) -> None:
        src = make_source(self.root)
        write(src / "big.txt", "x" * 100000)
        out = self.root / "out.evakb"

        with unittest.mock.patch.object(build_evakb, "MAX_COMPRESSED_BYTES", 64):
            with self.assertRaises(build_evakb.BuildError):
                build_evakb.build_package(src, out)

        self.assertFalse(out.exists())


class MainTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)

    def test_main_success(self) -> None:
        src = make_source(self.root)
        out = self.root / "out.evakb"

        code = build_evakb.main([str(src), str(out)])

        self.assertEqual(code, 0)
        self.assertTrue(out.is_file())

    def test_main_reports_error_code(self) -> None:
        src = make_source(self.root, index=False)
        out = self.root / "out.evakb"

        code = build_evakb.main([str(src), str(out)])

        self.assertEqual(code, 2)

    def test_main_force_flag(self) -> None:
        src = make_source(self.root)
        out = self.root / "out.evakb"
        out.write_bytes(b"x")

        self.assertEqual(build_evakb.main([str(src), str(out)]), 2)
        self.assertEqual(build_evakb.main([str(src), str(out), "--force"]), 0)


if __name__ == "__main__":
    unittest.main()
