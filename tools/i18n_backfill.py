#!/usr/bin/env python3
"""One-off i18n backfill: reconcile hand-added keys (strings.xml only) into _i18n TSVs.

History: several earlier batches hand-edited S.kt + strings.xml x4 without adding
TSV rows, so tools/i18n_consolidate.py refuses to generate ("code references keys
missing from TSVs"). This script reads the four locale strings.xml files, finds
keys missing from the module TSVs, and appends them to the right module TSV
(zh) plus translations.tsv (en/ja/ko) per prefix mapping.

Run once from repo root:  python tools/i18n_backfill.py
Idempotent: only missing keys are written; TSV rows are never overwritten.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
I18N = ROOT / "_i18n"
RES = ROOT / "app/src/main/res"

LOCALES = {"zh": "values", "en": "values-en", "ja": "values-ja", "ko": "values-ko"}

# Key prefix -> module TSV. Checked longest-prefix first; default a11_core_misc.
PREFIX_MODULES = [
    ("creng_", "a2_creation_engine.tsv"),
    ("crs_", "a1_creation_screen.tsv"),
    ("chatimgco_", "a10_chat_core.tsv"),
    ("chatvm_", "a10_chat_core.tsv"),
    ("chat_script_", "a10_chat_core.tsv"),
    ("chat_", "a10_chat_core.tsv"),
    ("updvm_", "a10_chat_core.tsv"),
    ("wblist_", "a6_world.tsv"),
    ("wbedit_", "a6_world.tsv"),
    ("setnet_", "a3_settings_sections.tsv"),
    ("setprov_", "a3_settings_sections.tsv"),
    ("setimg_", "a3_settings_sections.tsv"),
    ("setbkp_", "a4_settings_sections2.tsv"),
    ("extmem_", "a9_extensions.tsv"),
    ("extcompat_", "a9_extensions.tsv"),
    ("memsvc_", "a11_core_misc.tsv"),
    ("memmod_", "a11_core_misc.tsv"),
    ("cregex_", "a11_core_misc.tsv"),
    ("net_", "a11_core_misc.tsv"),
    ("ui_", "chat_ui.tsv"),
    ("main_", "a11_core_misc.tsv"),
]
DEFAULT_MODULE = "a11_core_misc.tsv"

STRING_RE = re.compile(r'<string name="([^"]+)"[^>]*>(.*?)</string>', re.DOTALL)


def unescape(value: str) -> str:
    """Invert the escaping xml_escape applies, so re-generation round-trips."""
    v = value.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    v = v.replace('\\"', '"').replace("\\'", "'")
    return v.replace("\\n", "\\n")  # literal \n stays (TSV newline convention)


def read_locale_dir(dir_path: Path) -> tuple[dict[str, str], list[Path]]:
    """All <string> entries in a locale dir. Returns (entries, non-strings files
    that contributed entries — those must be pruned after backfill because the
    consolidate generator writes every key into strings.xml)."""
    entries: dict[str, str] = {}
    extras: list[Path] = []
    for xml in sorted(dir_path.glob("*.xml")):
        found = False
        for m in STRING_RE.finditer(xml.read_text(encoding="utf-8")):
            entries[m.group(1)] = unescape(m.group(2))
            found = True
        if found and xml.name != "strings.xml":
            extras.append(xml)
    return entries, extras


def tsv_keys() -> set[str]:
    keys: set[str] = set()
    for tsv in I18N.glob("*.tsv"):
        if tsv.name == "translations.tsv":
            continue
        for line in tsv.read_text(encoding="utf-8").splitlines():
            if line.strip() and not line.startswith("#"):
                keys.add(line.split("\t", 1)[0].strip())
    return keys


def translation_keys() -> set[str]:
    keys: set[str] = set()
    tsv = I18N / "translations.tsv"
    for line in tsv.read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.startswith("#"):
            keys.add(line.split("\t", 1)[0].strip())
    return keys


def module_for(key: str) -> str:
    for prefix, module in PREFIX_MODULES:
        if key.startswith(prefix):
            return module
    return DEFAULT_MODULE


def prune_extra_files(extra_by_locale: dict[str, list[Path]], backfilled: set[str]) -> None:
    """Remove <string> entries the generator now owns (it writes them into
    strings.xml; leaving them elsewhere would duplicate the resource)."""
    for _, files in extra_by_locale.items():
        for xml in files:
            text = xml.read_text(encoding="utf-8")
            pruned = STRING_RE.sub("", text)
            # Only entries this run backfilled are now generator-owned.
            for key in list(backfilled):
                pruned = re.sub(rf'<string name="{re.escape(key)}"[^>]*>.*?</string>\s*', "", pruned, flags=re.DOTALL)
            if re.search(r"<string\s", pruned):
                xml.write_text(pruned, encoding="utf-8")
                print(f"pruned backfilled keys from {xml.name}")
            else:
                xml.unlink()
                print(f"deleted {xml} (no strings left outside strings.xml)")


def main() -> None:
    by_locale: dict[str, dict[str, str]] = {}
    extra_by_locale: dict[str, list[Path]] = {}
    for loc, dir_name in LOCALES.items():
        by_locale[loc], extras = read_locale_dir(RES / dir_name)
        extra_by_locale[loc] = extras
    zh = by_locale["zh"]
    known = tsv_keys()
    missing = sorted(set(zh) - known)
    if not missing:
        print("nothing to backfill")
        return

    have_translations = translation_keys()
    module_rows: dict[str, list[str]] = {}
    translation_rows: list[str] = []
    for key in missing:
        module_rows.setdefault(module_for(key), []).append(f"{key}\t{zh[key]}")
        if key not in have_translations:
            cols = [by_locale[loc].get(key) for loc in ("en", "ja", "ko")]
            if all(cols):
                translation_rows.append(f"{key}\t" + "\t".join(cols))

    for module, rows in module_rows.items():
        path = I18N / module
        text = path.read_text(encoding="utf-8")
        if not text.endswith("\n"):
            text += "\n"
        path.write_text(text + "\n".join(rows) + "\n", encoding="utf-8")
        print(f"{module}: +{len(rows)}")

    if translation_rows:
        path = I18N / "translations.tsv"
        text = path.read_text(encoding="utf-8")
        if not text.endswith("\n"):
            text += "\n"
        path.write_text(text + "\n".join(translation_rows) + "\n", encoding="utf-8")
        print(f"translations.tsv: +{len(translation_rows)}")

    still_missing = sorted(set(zh) - tsv_keys())
    print(f"backfilled {len(missing)} module keys; remaining missing: {len(still_missing)}")
    if still_missing:
        print("\n".join(still_missing))
        sys.exit(1)

    # Keys that lived in non-strings.xml resource files (e.g. ui_atmosphere.xml):
    # the generator now owns them and writes them into strings.xml, so prune the
    # originals to avoid duplicate-resource build failures.
    extra_keys: set[str] = set()
    for xml in extra_by_locale["zh"]:
        extra_keys.update(m.group(1) for m in STRING_RE.finditer(xml.read_text(encoding="utf-8")))
    if extra_keys:
        prune_extra_files(extra_by_locale, extra_keys)


if __name__ == "__main__":
    main()
