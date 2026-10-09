#!/usr/bin/env python3
"""Проверка журнала описи «Осмотра» на компьютере или сервере.

    python3 verify_journal.py <папка описи> [--previous старый/Журнал.jsonl]

Журнал («Журнал.jsonl» в папке описи) — строка JSON на действие: когда, кто, что, файл и отпечаток
SHA-256 его содержимого. Строки сцеплены (в каждой — отпечаток предыдущей), отпечаток строки
подписан ключом телефона (ECDSA P-256 / SHA-256, DER; открытый ключ — точка без сжатия, в строке).

Проверяется: нумерация, цепочка, отпечаток и подпись каждой строки; сверка файлов — журнал
проигрывается от начала, и что должно лежать в описи, сравнивается с диском (изменённое,
подкинутое, пропавшее); --previous — журнал прошлой выгрузки: новый должен его продолжать
(обрезали, подменили — видно). Время, идущее назад, — предупреждение.

Нужен пакет cryptography (pip install cryptography). Код выхода: 0 — цел, 1 — нарушен.
"""
import argparse, hashlib, json, os, sys

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec

FILE = "Журнал.jsonl"
SEP = "\u001f"
FIELDS = ["n", "t", "who", "what", "subject", "file", "sha256", "key", "prev"]
BEFORE, RENAMED, MOVED, DELETED, PHOTO_MOVED = "До журнала", "Папка переименована", "Папка перенесена", "Папка удалена", "Снимок перенесён"
PHOTO_EXT = {"jpg", "jpeg", "heic", "heif", "m4a"}
PLAIN = {"Комментарий.txt", "Контакты.xlsx", "Инвентарники.txt", "Нерабочие.txt"}


def body(e):
    return SEP.join(str(e.get(k, "")) for k in FIELDS)


def check(entries):
    """(номер нарушенной записи или None, причина, предупреждения)."""
    prev, prev_t, warn = "", 0, []
    for i, e in enumerate(entries, 1):
        if e is None:
            return i, "строка не читается", warn
        if e.get("n") != i:
            return i, f"номер {e.get('n')} вместо {i} — строку удалили или вставили", warn
        if e.get("prev", "") != prev:
            return i, "не сходится с предыдущей записью", warn
        if hashlib.sha256(body(e).encode("utf-8")).hexdigest() != e.get("hash"):
            return i, "содержимое изменено", warn
        try:
            key = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), bytes.fromhex(e["key"]))
            key.verify(bytes.fromhex(e["sig"]), e["hash"].encode("utf-8"), ec.ECDSA(hashes.SHA256()))
        except (InvalidSignature, ValueError, KeyError):
            return i, "подпись не сходится", warn
        if e["t"] < prev_t - 10 * 60_000:
            warn.append(f"время записи № {i} раньше предыдущей — переводили часы?")
        prev, prev_t = e["hash"], e["t"]
    return None, "", warn


def expected(entries):
    """Что должно лежать по журналу: путь → отпечаток ('' — до журнала, содержимое не известно)."""
    state = {}

    def rename(a, b):
        for k in [k for k in state if k == a or k.startswith(a + "/")]:
            state[b + k[len(a):]] = state.pop(k)

    for e in entries:
        what, f, sha = e["what"], e.get("file", ""), e.get("sha256", "")
        if what == BEFORE:
            for p in e["subject"].splitlines():
                if p.strip():
                    state[p] = ""
        elif what in (RENAMED, MOVED):
            parts = e["subject"].split(" → ")
            if len(parts) == 2:
                rename(*parts)
        elif what == DELETED:
            for k in [k for k in state if k == f or k.startswith(f + "/")]:
                state.pop(k)
        elif what == PHOTO_MOVED:
            parts = e["subject"].split(" → ")
            if len(parts) == 2:
                state.pop(parts[0], None)
            if f:
                state[f] = sha
        elif f:
            if sha:
                state[f] = sha
            else:
                state.pop(f, None)
    return state


def inner(path):
    """Путь внутри описи — без имени её папки: архив могут распаковать под любым именем."""
    return path.split("/", 1)[1] if "/" in path else ""


def tracked(obj):
    """Опорные файлы описи — пути внутри неё."""
    root = os.path.abspath(obj.rstrip("/\\"))
    out = {}
    for d, dirs, files in os.walk(obj):
        dirs[:] = [x for x in dirs if not x.startswith(".")]
        for name in files:
            if name.startswith(".") or name == FILE or name.startswith("Осмотр — "):
                continue
            if name in PLAIN or name.startswith("Опись — ") or name.rsplit(".", 1)[-1].lower() in PHOTO_EXT:
                full = os.path.join(d, name)
                out[os.path.relpath(full, root).replace(os.sep, "/")] = full
    return out


def load(path):
    out = []
    for line in open(path, encoding="utf-8").read().splitlines():
        if line.strip():
            try:
                out.append(json.loads(line))
            except ValueError:
                out.append(None)
    return out


def main():
    ap = argparse.ArgumentParser(description="Проверка журнала описи «Осмотра»")
    ap.add_argument("obj", help="папка описи (в ней Журнал.jsonl)")
    ap.add_argument("--previous", help="Журнал.jsonl прошлой выгрузки — новый должен его продолжать")
    a = ap.parse_args()
    path = os.path.join(a.obj, FILE)
    if not os.path.isfile(path):
        print("НАРУШЕН: журнала нет"); return 1
    entries = load(path)
    at, why, warn = check(entries)
    if at:
        print(f"НАРУШЕН: запись № {at} — {why} (всего записей {len(entries)})"); return 1
    bad = 0
    if a.previous:
        old = load(a.previous)
        if len(old) > len(entries) or (old and entries[len(old) - 1]["hash"] != old[-1].get("hash")):
            print(f"НАРУШЕН: журнал не продолжает прошлую выгрузку ({len(old)} записей) — обрезали или подменили"); bad += 1
    want = {inner(p): v for p, v in expected(entries).items()}
    have = tracked(a.obj)
    for p, full in sorted(have.items()):
        sha = want.get(p)
        if sha is None:
            print(f"  появился без записи: {p}"); bad += 1
        elif sha and hashlib.sha256(open(full, "rb").read()).hexdigest() != sha:
            print(f"  изменён: {p}"); bad += 1
    for p in sorted(set(want) - set(have)):
        print(f"  пропал без записи: {p}"); bad += 1
    for w in warn:
        print(f"  предупреждение: {w}")
    phones = len({e["key"] for e in entries})
    print(("НАРУШЕН: файлов не так — " + str(bad)) if bad else f"Журнал цел: {len(entries)} записей, телефонов: {phones}, файлы сходятся")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
