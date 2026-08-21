#!/usr/bin/env python3
"""Сборка пакета .evakb из каталога.

.evakb — это обычный ZIP-архив с обязательным index.html в корне,
файлом manifest.json и необязательными assets/** и data.json.

Использование:
    python tools/build_evakb.py sample dist/sample-dashboard.evakb
    python tools/build_evakb.py sample dist/sample-dashboard.evakb --force
"""

from __future__ import annotations

import argparse
import io
import json
import sys
import zipfile
from datetime import datetime, timezone
from pathlib import Path

FORMAT_ID = "eva.kb"
FORMAT_VERSION = 1

# Те же ограничения, что и в приложении (ai.eva.kbviewer.EvaLimits).
MAX_ENTRIES = 300
MAX_COMPRESSED_BYTES = 25 * 1024 * 1024
MAX_UNCOMPRESSED_BYTES = 75 * 1024 * 1024

INDEX_NAME = "index.html"
MANIFEST_NAME = "manifest.json"

# Фиксированная дата в ZIP, чтобы сборка была детерминированной.
_ZIP_DATE = (1980, 1, 1, 0, 0, 0)


class BuildError(Exception):
    """Ошибка сборки пакета с понятным человеку сообщением."""


def _collect_files(src: Path) -> list[tuple[str, Path]]:
    files: list[tuple[str, Path]] = []
    for path in src.rglob("*"):
        if not path.is_file():
            continue
        rel = path.relative_to(src).as_posix()
        files.append((rel, path))
    files.sort(key=lambda item: item[0])
    return files


def _load_or_create_manifest(src: Path, title: str | None) -> bytes:
    manifest_path = src / MANIFEST_NAME
    if manifest_path.is_file():
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            raise BuildError(f"{MANIFEST_NAME}: некорректный JSON ({exc})") from exc
        if not isinstance(manifest, dict):
            raise BuildError(f"{MANIFEST_NAME}: ожидался JSON-объект")
        version = manifest.get("version")
        if version != FORMAT_VERSION:
            raise BuildError(
                f"{MANIFEST_NAME}: неподдерживаемая версия формата {version!r}, "
                f"ожидается {FORMAT_VERSION}"
            )
        fmt = manifest.get("format")
        if fmt is not None and fmt != FORMAT_ID:
            raise BuildError(f"{MANIFEST_NAME}: неизвестный формат {fmt!r}")
        return manifest_path.read_bytes()

    generated = {
        "format": FORMAT_ID,
        "version": FORMAT_VERSION,
        "title": title or src.name,
        "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    return json.dumps(generated, ensure_ascii=False, indent=2).encode("utf-8")


def build_package(
    src: Path,
    out: Path,
    *,
    force: bool = False,
    title: str | None = None,
) -> Path:
    """Собрать пакет .evakb. Бросает BuildError при любой проблеме."""
    src = Path(src)
    out = Path(out)

    if not src.is_dir():
        raise BuildError(f"каталог с источником не найден: {src}")
    if out.exists() and not force:
        raise BuildError(f"файл {out} уже существует; используйте --force для замены")

    if not (src / INDEX_NAME).is_file():
        raise BuildError(f"в корне каталога отсутствует обязательный {INDEX_NAME}")

    manifest_bytes = _load_or_create_manifest(src, title)
    entries = [(name, path) for name, path in _collect_files(src) if name != MANIFEST_NAME]

    total_entries = len(entries) + 1  # +1 — manifest.json
    if total_entries > MAX_ENTRIES:
        raise BuildError(
            f"слишком много файлов в пакете: {total_entries}, максимум {MAX_ENTRIES}"
        )

    total_uncompressed = len(manifest_bytes) + sum(p.stat().st_size for _, p in entries)
    if total_uncompressed > MAX_UNCOMPRESSED_BYTES:
        raise BuildError(
            f"суммарный размер содержимого {total_uncompressed} Б превышает "
            f"лимит {MAX_UNCOMPRESSED_BYTES} Б"
        )

    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for name, data in _archive_payload(manifest_bytes, entries):
            info = zipfile.ZipInfo(name, date_time=_ZIP_DATE)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            info.create_system = 0  # всегда FAT, независимо от ОС сборки
            zf.writestr(info, data)

    payload = buffer.getvalue()
    if len(payload) > MAX_COMPRESSED_BYTES:
        raise BuildError(
            f"размер архива {len(payload)} Б превышает лимит {MAX_COMPRESSED_BYTES} Б"
        )

    if out.parent and not out.parent.exists():
        out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(payload)
    return out


def _archive_payload(
    manifest_bytes: bytes, entries: list[tuple[str, Path]]
) -> list[tuple[str, bytes]]:
    payload = [(MANIFEST_NAME, manifest_bytes)]
    payload.extend((name, path.read_bytes()) for name, path in entries)
    payload.sort(key=lambda item: item[0])
    return payload


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Сборка пакета .evakb из каталога")
    parser.add_argument("input_dir", help="каталог с index.html и остальными файлами")
    parser.add_argument("output", help="путь к результирующему файлу .evakb")
    parser.add_argument("--force", action="store_true", help="перезаписать существующий файл")
    parser.add_argument("--title", default=None, help="заголовок для генерируемого манифеста")
    args = parser.parse_args(argv)

    try:
        out = build_package(
            Path(args.input_dir), Path(args.output), force=args.force, title=args.title
        )
    except BuildError as exc:
        print(f"ошибка: {exc}", file=sys.stderr)
        return 2

    size = out.stat().st_size
    with zipfile.ZipFile(out) as zf:
        count = len(zf.namelist())
    print(f"готово: {out} ({size} Б, файлов: {count})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
