#!/usr/bin/env python3
"""Cross-client sync check for the OpenRad monorepo.

Compares the canonical Rust sources of truth against the mirrored copies
in clients/android/ and clients/python/:

1. Fixtures: tests/fixtures/*.json vs the per-client copies (sha256).
2. Locales: locales/messages.json coverage vs Android res/values*/strings.xml,
   plus mutual consistency (keys + %N$s placeholders) across the four
   Android locales.

Exit 0 when everything matches, exit 1 on any divergence.
Run from the repository root:  python3 scripts/check-sync.py
"""

import hashlib
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
errors: list[str] = []
warnings: list[str] = []

# ---------------------------------------------------------------- fixtures

# (canonical relative path, list of client mirror relative paths)
# Python ships only the fixtures its test harness needs; missing mirrors
# are reported as warnings, never as errors.
FIXTURE_GROUPS = [
    (
        "tests/fixtures/reference.json",
        [
            "clients/android/app/src/test/resources/fixtures/reference.json",
            "clients/python/fixtures/reference.json",
        ],
    ),
    (
        "tests/fixtures/network-verifier.json",
        [
            "clients/android/app/src/test/resources/fixtures/network-verifier.json",
            "clients/python/fixtures/network-verifier.json",
        ],
    ),
    (
        "tests/fixtures/incoming-sh.json",
        [
            "clients/android/app/src/test/resources/fixtures/incoming-sh.json",
        ],
    ),
]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check_fixtures() -> None:
    print("== fixtures ==")
    for canonical_rel, mirrors in FIXTURE_GROUPS:
        canonical = ROOT / canonical_rel
        if not canonical.is_file():
            errors.append(f"missing canonical fixture: {canonical_rel}")
            continue
        want = sha256(canonical)
        print(f"{canonical_rel}: {want[:12]}...")
        for mirror_rel in mirrors:
            mirror = ROOT / mirror_rel
            if not mirror.is_file():
                warnings.append(f"mirror not present (skipped): {mirror_rel}")
                print(f"  WARN {mirror_rel}: absent")
                continue
            got = sha256(mirror)
            status = "OK  " if got == want else "FAIL"
            print(f"  {status} {mirror_rel}")
            if got != want:
                errors.append(
                    f"fixture divergence: {mirror_rel} "
                    f"(sha256 {got[:12]}... != canonical {want[:12]}...)"
                )


# ---------------------------------------------------------------- locales

ANDROID_LOCALES = {
    "en": "clients/android/app/src/main/res/values/strings.xml",
    "pt": "clients/android/app/src/main/res/values-pt/strings.xml",
    "ru": "clients/android/app/src/main/res/values-ru/strings.xml",
    "vi": "clients/android/app/src/main/res/values-vi/strings.xml",
}

PLACEHOLDER_RE = re.compile(r"%([0-9]+)\$s")
RUST_PLACEHOLDER_RE = re.compile(r"\{[a-zA-Z0-9_]+\}|\{[0-9]+\}")


def android_strings(path: Path) -> dict[str, str]:
    root = ET.parse(path).getroot()
    return {s.get("name", ""): (s.text or "") for s in root.iter("string")}


def placeholders(text: str) -> list[str]:
    return sorted(PLACEHOLDER_RE.findall(text))


def check_locales() -> None:
    print("== locales ==")
    catalog_path = ROOT / "locales/messages.json"
    try:
        catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        errors.append(f"cannot read locales/messages.json: {exc}")
        return
    rust_keys = len(catalog)
    rust_with_ph = sum(
        1 for k in catalog if RUST_PLACEHOLDER_RE.search(k)
    )
    print(f"locales/messages.json: {rust_keys} keys ({rust_with_ph} with placeholders)")

    loaded: dict[str, dict[str, str]] = {}
    for lang, rel in ANDROID_LOCALES.items():
        path = ROOT / rel
        if not path.is_file():
            errors.append(f"missing Android strings file: {rel}")
            continue
        loaded[lang] = android_strings(path)
        total_ph = sum(len(placeholders(v)) for v in loaded[lang].values())
        print(f"android {rel}: {len(loaded[lang])} strings, {total_ph} placeholders")

    if "en" not in loaded:
        return
    base = loaded["en"]
    base_ph_total = sum(len(placeholders(v)) for v in base.values())
    for lang, strings in loaded.items():
        if lang == "en":
            continue
        missing = [k for k in base if k not in strings]
        extra = [k for k in strings if k not in base]
        ph_mismatch = [
            k
            for k in base
            if k in strings and placeholders(base[k]) != placeholders(strings[k])
        ]
        ph_total = sum(len(placeholders(v)) for v in strings.values())
        if missing:
            errors.append(f"android {lang}: {len(missing)} keys missing vs en (e.g. {missing[:3]})")
        if extra:
            warnings.append(f"android {lang}: {len(extra)} extra keys vs en (e.g. {extra[:3]})")
        if ph_mismatch:
            errors.append(
                f"android {lang}: placeholder mismatch in {len(ph_mismatch)} keys "
                f"(e.g. {ph_mismatch[:3]})"
            )
        if ph_total != base_ph_total:
            errors.append(
                f"android {lang}: placeholder count {ph_total} != en {base_ph_total}"
            )

    # Coverage is informational: android (alpha) carries a subset of the
    # desktop catalog. A shrinking subset is worth noticing, not failing.
    print(
        f"coverage: android en covers {len(base)} strings "
        f"vs {rust_keys} rust catalog keys (subset expected while android is alpha)"
    )


def main() -> int:
    check_fixtures()
    check_locales()
    print()
    for w in warnings:
        print(f"WARNING: {w}")
    if errors:
        for e in errors:
            print(f"ERROR: {e}")
        print(f"\ncheck-sync: FAILED ({len(errors)} error(s))")
        return 1
    print("check-sync: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
