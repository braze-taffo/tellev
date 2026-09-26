#!/usr/bin/env python3
"""i18n consolidation: _i18n/*.tsv + _i18n/translations.tsv -> Android string resources + generated S.kt.

Inputs (relative to repo root):
  _i18n/<module>.tsv        lines: key<TAB>zh   (zh = default locale value, \n means newline)
  _i18n/translations.tsv    lines: key<TAB>en<TAB>ja<TAB>ko  (missing key/lang falls back to zh)

Outputs:
  app/src/main/res/values/strings.xml          (zh, default)
  app/src/main/res/values-{en,ja,ko}/strings.xml
  app/src/main/java/app/tellev/core/i18n/S.kt  (name constants + id map + JVM-test zh fallback)

Also validates: key name grammar, cross-module duplicates, code-references vs TSV entries.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
I18N_DIR = ROOT / "_i18n"
RES_DIR = ROOT / "app/src/main/res"
SRC_DIR = ROOT / "app/src/main/java"
GEN_KT = ROOT / "app/src/main/java/app/tellev/core/i18n/S.kt"

APP_NAME = "tellev"

def fail(msg: str) -> None:
    print(f"ERROR: {msg}")
    sys.exit(1)

def read_module_tsv(path: Path) -> dict[str, str]:
    entries: dict[str, str] = {}
    for lineno, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw.strip():
            continue
        parts = raw.split("\t")
        if len(parts) < 2:
            fail(f"{path.name}:{lineno}: expected key<TAB>zh, got {raw!r}")
        key, zh = parts[0].strip(), parts[1]
        if not re.fullmatch(r"[a-z][a-z0-9_]*", key):
            fail(f"{path.name}:{lineno}: bad key name {key!r}")
        if key in entries:
            fail(f"{path.name}:{lineno}: duplicate key {key}")
        if "\\t" in zh:
            print(f"WARN: {path.name}:{lineno}: literal \\t in value, keeping as-is: {key}")
        entries[key] = zh
    return entries

def read_translations(path: Path) -> dict[str, list[str]]:
    table: dict[str, list[str]] = {}
    if not path.exists():
        return table
    for lineno, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw.strip() or raw.startswith("#"):
            continue
        parts = raw.split("\t")
        if len(parts) < 4:
            fail(f"translations.tsv:{lineno}: expected key<TAB>en<TAB>ja<TAB>ko, got {raw!r}")
        table[parts[0].strip()] = [p.strip() for p in parts[1:4]]
    return table

def xml_escape(value: str) -> str:
    # Keep literal \n sequences untouched: aapt turns them into newlines.
    v = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    v = v.replace("'", "\\'").replace('"', '\\"')
    if v.startswith("@") or v.startswith("?"):
        v = "\\" + v
    # Quote-wrap to preserve leading/trailing whitespace and internal runs of spaces.
    if v != v.strip() or "  " in value:
        v = f'"{v}"'
    return v

def needs_formatted_false(value: str) -> bool:
    """True when the value contains % tokens that are not positional placeholders
    (e.g. '%model%', '<%= %>') — aapt would reject them in a formatted string."""
    without_positional = re.sub(r"%\d+\$[sd]", "", value)
    return "%" in without_positional.replace("%%", "")

def kotlin_escape(value: str) -> str:
    # TSV stores newline as the two characters \n; turn them into a real Kotlin escape.
    parts = [p.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") for p in value.split("\\n")]
    return "\\n".join(parts)

def referenced_keys() -> set[str]:
    refs: set[str] = set()
    pat_r = re.compile(r"R\.string\.([a-z][a-z0-9_]*)")
    pat_s = re.compile(r"\bS\.([a-z][a-z0-9_]*)\b")
    for kt in SRC_DIR.rglob("*.kt"):
        text = kt.read_text(encoding="utf-8")
        refs.update(pat_r.findall(text))
        # S constants live in app.tellev.core.i18n; skip S.kt itself (its map holds keys as strings).
        if kt.name != "S.kt":
            refs.update(m for m in pat_s.findall(text) if m not in ("idByName", "fallbackZh"))
    return refs

def main() -> None:
    entries: dict[str, str] = {}
    order: list[str] = []
    for tsv in sorted(I18N_DIR.glob("*.tsv")):
        if tsv.name == "translations.tsv" or tsv.name.startswith("_"):
            continue
        module = read_module_tsv(tsv)
        for key in module:
            if key in entries:
                fail(f"duplicate key {key!r} across module TSVs ({tsv.name})")
        entries.update(module)
        order.extend(module.keys())

    translations = read_translations(I18N_DIR / "translations.tsv")
    unknown = sorted(set(translations) - set(entries))
    if unknown:
        print(f"WARN: translations.tsv has keys not present in module TSVs: {unknown}")

    missing = [k for k in order if k not in translations or not any(translations[k])]
    if missing:
        print(f"WARN: {len(missing)} keys fall back to zh in en/ja/ko:")
        for k in missing:
            print(f"  - {k}: {entries[k]}")

    for key, zh in entries.items():
        if "%" in zh:
            has_positional = re.search(r"%\d+\$[sd]", zh) is not None
            has_double = "%%" in zh
            if has_double and not has_positional:
                fail(f"{key}: contains %% but no positional placeholder (would render literally)")
            bare = "%" in re.sub(r"%\d+\$[sd]|%%", "", zh)
            if has_positional and bare:
                fail(f"{key}: mixes positional placeholders with bare % tokens (fix manually)")

    used = referenced_keys()
    dangling = sorted(used - set(entries))
    if dangling:
        fail(f"code references keys missing from TSVs: {dangling}")
    unused = sorted(set(entries) - used)
    if unused:
        print(f"WARN: {len(unused)} TSV keys not referenced in code (dead entries): {unused}")

    locales = [("values", 0), ("values-en", 1), ("values-ja", 2), ("values-ko", 3)]
    for dirname, idx in locales:
        lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>", f'    <string name="app_name">{APP_NAME}</string>', ""]
        for key in order:
            if key == "app_name":
                continue
            value = entries[key] if idx == 0 else (translations.get(key, [None, None, None])[idx - 1] or entries[key])
            escaped = xml_escape(value)
            if needs_formatted_false(value):
                lines.append(f'    <string name="{key}" formatted="false">{escaped}</string>')
            else:
                lines.append(f'    <string name="{key}">{escaped}</string>')
        lines.append("</resources>")
        out = RES_DIR / dirname / "strings.xml"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"wrote {out.relative_to(ROOT)} ({len(order)} keys)")

    kt_lines = [
        "package app.tellev.core.i18n",
        "",
        "import app.tellev.R",
        "",
        "// GENERATED by tools/i18n_consolidate.py — do not edit by hand.",
        "// Sources: _i18n/*.tsv (zh) + _i18n/translations.tsv (en/ja/ko).",
        "object S {",
    ]
    for key in order:
        kt_lines.append(f'    const val {key} = "{key}"')
    kt_lines += ["", "    /** 资源名 → R.string.* id，供 UiStrings 在运行期解析。 */", "    val idByName: Map<String, Int> = mapOf("]
    for key in order:
        kt_lines.append(f'        "{key}" to R.string.{key},')
    kt_lines += ["    )", "", "    /** JVM 单测回退：无 Resources 时按 key 解析中文原文。 */", "    val fallbackZh: Map<String, String> = mapOf("]
    for key in order:
        kt_lines.append(f'        "{key}" to "{kotlin_escape(entries[key])}",')
    kt_lines += ["    )", "}"]
    GEN_KT.write_text("\n".join(kt_lines) + "\n", encoding="utf-8")
    print(f"wrote {GEN_KT.relative_to(ROOT)} ({len(order)} keys)")

    print(f"OK: {len(order)} keys, {len(translations)} translated")

if __name__ == "__main__":
    main()
