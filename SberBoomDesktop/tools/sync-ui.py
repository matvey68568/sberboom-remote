#!/usr/bin/env python3
"""Синхронизация UI: Android-исходники → Windows-сборка.

Экранный слой общий, отличаются только загрузчик картинок и способ подписки
на состояние. Скрипт копирует ui/*.kt из Android-проекта и делает две замены:

  coil.compose.AsyncImage            → ru.sberboom.desktop.compose.NetImage
  collectAsStateWithLifecycle()      → collectAsState()

Запуск: python tools/sync-ui.py
"""
from __future__ import annotations

import pathlib
import shutil
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / "SberBoomRemote" / "app" / "src" / "main" / "java" / "ru" / "sberboom" / "remote" / "ui"
DST = ROOT / "SberBoomDesktop" / "src" / "main" / "kotlin" / "ru" / "sberboom" / "remote" / "ui"

REPLACEMENTS = [
    ("import coil.compose.AsyncImage", "import ru.sberboom.desktop.compose.NetImage"),
    ("AsyncImage(", "NetImage("),
    (
        "import androidx.lifecycle.compose.collectAsStateWithLifecycle",
        "import androidx.compose.runtime.collectAsState",
    ),
    ("collectAsStateWithLifecycle()", "collectAsState()"),
]


def main() -> int:
    if not SRC.is_dir():
        print(f"нет каталога Android UI: {SRC}", file=sys.stderr)
        return 1

    DST.mkdir(parents=True, exist_ok=True)
    copied = 0
    for src in sorted(SRC.glob("*.kt")):
        text = src.read_text(encoding="utf-8")
        for old, new in REPLACEMENTS:
            text = text.replace(old, new)
        (DST / src.name).write_text(text, encoding="utf-8")
        copied += 1

    # файлы, удалённые в Android-версии, не должны оставаться в Windows-сборке
    existing = {p.name for p in SRC.glob("*.kt")}
    for dst in sorted(DST.glob("*.kt")):
        if dst.name not in existing:
            dst.unlink()

    print(f"синхронизировано экранов: {copied}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
