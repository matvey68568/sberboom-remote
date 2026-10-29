#!/usr/bin/env python3
"""Генератор эталонных кадров протокола SBoom.

Собирает кадры РЕАЛЬНОЙ реализацией из Home Assistant-интеграции
(custom_components/sboom_ha/_tlv.py + api.py + _deeplink.py) и печатает
Kotlin-файл с hex-эталонами. Android-тесты сравнивают свой кодек с этими
байтами байт-в-байт — так порт доказуемо совпадает с оригиналом.

Запуск:  python tools/gen_fixtures.py > app/src/test/java/ru/sberboom/remote/ProtocolFixtures.kt
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "sboom_ha" / "custom_components" / "sboom_ha"))

from _tlv import field  # noqa: E402

TOKEN = "d4f1c0de-0000-4000-8000-abcdefabcdef"
MSG_ID = "11111111-2222-3333-4444-555555555555"
CLIENT_ID = "99999999-8888-7777-6666-555555555555"
CLIENT_NAME = "SberBoom Remote"


def envelope(request_data: bytes, token: str | None = TOKEN) -> bytes:
    parts = [field(1, 0, 2), field(2, 2, MSG_ID.encode())]
    if token:
        parts.append(field(3, 2, token.encode()))
    parts += [
        field(5, 2, request_data),
        field(6, 0, 1),
        field(7, 2, CLIENT_NAME.encode()),
        field(10, 0, 1),
        field(11, 2, CLIENT_ID.encode()),
    ]
    return b"".join(parts)


def empty_op(op: int) -> bytes:
    return field(op, 2, field(1, 2, b""))


def paired_bt_frame() -> bytes:
    """Кадр-ответ op=19: envelope[5] → [19] → repeated {1:mac, 2:name, 3:connected}."""
    devices = b"".join(
        field(1, 2, field(1, 2, mac.encode()) + field(2, 2, name.encode()) + field(3, 0, connected))
        for mac, name, connected in (
            ("AA:BB:CC:DD:EE:01", "iPhone Matvey", 1),
            ("AA:BB:CC:DD:EE:02", "MacBook Pro", 0),
        )
    )
    return field(5, 2, field(19, 2, devices))


def queue_frame() -> bytes:
    """Кадр-ответ op=17 — очередь из 6 треков (реальный размер: длина JSON
    больше 127 байт, иначе varint длины попадает в printable-диапазон)."""
    import json as _json

    payload = _json.dumps(
        [{"explicit": False, "trackId": 74563601 + i} for i in range(6)],
        separators=(",", ":"),
    )
    return field(5, 2, field(17, 2, field(5, 2, payload.encode())))


def _reference_parse_check(frame: bytes) -> None:
    """Сверить кадры РЕАЛЬНЫМИ парсерами интеграции (parse_paired_bt, parse_queue)."""
    import types

    pkg_name = "sboom_ref"
    pkg = types.ModuleType(pkg_name)
    pkg.__path__ = [str(ROOT / "sboom_ha" / "custom_components" / "sboom_ha")]
    sys.modules[pkg_name] = pkg
    from importlib import import_module

    parsers = import_module(f"{pkg_name}._parsers")

    devices = parsers.parse_paired_bt(frame)
    summary = [(d.mac, d.name, d.connected) for d in devices]
    expected = [("AA:BB:CC:DD:EE:01", "iPhone Matvey", True), ("AA:BB:CC:DD:EE:02", "MacBook Pro", False)]
    assert summary == expected, f"reference parser mismatch: {summary}"
    print(f"# reference parse ok: {summary}", file=sys.stderr)

    queue = parsers.parse_queue(queue_frame())
    q_summary = [(q.track_id, q.explicit) for q in queue]
    expected_q = [(str(74563601 + i), False) for i in range(6)]
    assert q_summary == expected_q, f"queue mismatch: {q_summary}"
    print(f"# reference queue ok: {len(q_summary)} tracks", file=sys.stderr)


def main() -> None:
    cases: dict[str, bytes] = {
        "GET_STATE": empty_op(12),
        "GET_METADATA": empty_op(10),
        "SET_VOLUME_75": field(14, 2, field(1, 0, 75)),
        "MEDIA_PLAY": field(16, 2, field(1, 0, 4)),
        "MEDIA_PAUSE": field(16, 2, field(1, 0, 5)),
        "SEEK_120": field(15, 2, field(1, 0, 120)),
        "SPEED_1_5": field(23, 2, field(1, 5, 1.5)),
        "KEEP_ALIVE": empty_op(18),
        "PAIR_INIT": empty_op(4),
        "PAIRED_BT": paired_bt_frame(),
        "QUEUE": queue_frame(),
        "ENVELOPE_GET_STATE": envelope(empty_op(12)),
        "ENVELOPE_GET_STATE_NO_TOKEN": envelope(empty_op(12), token=None),
    }
    _reference_parse_check(cases["PAIRED_BT"])

    print("package ru.sberboom.remote")
    print()
    print("/**")
    print(" * Эталоны кадров, собранные Python-кодом интеграции sboom_ha")
    print(" * (custom_components/sboom_ha/_tlv.py, api.py, _deeplink.py).")
    print(" * Сгенерировано tools/gen_fixtures.py — не править руками.")
    print(" */")
    print("object ProtocolFixtures {")
    for name, payload in cases.items():
        print(f'    const val {name} = "{payload.hex()}"')
    print()
    print("    const val TOKEN = \"" + TOKEN + "\"")
    print("    const val MSG_ID = \"" + MSG_ID + "\"")
    print("    const val CLIENT_ID = \"" + CLIENT_ID + "\"")
    print("    const val CLIENT_NAME = \"" + CLIENT_NAME + "\"")
    print("}")


if __name__ == "__main__":
    main()
