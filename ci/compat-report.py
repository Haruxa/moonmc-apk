#!/usr/bin/env python3
"""MoonMC-CI: матрица совместимости нативных модов с пином движка.

Читает `results.tsv` (id, версия, стадия, примечание), подпись Nightly и пишет
`ci/compat/<pin7>.json` — его читает лаунчер, чтобы показать у мода «собирается
на этом движке» или «нет». Плюс печатает таблицу в summary GitHub Actions.

Стадии: ok (применилось и скомпилировалось), apply (миксины не легли),
check (не компилируется), download (не скачался).
"""
import datetime
import json
import os
import pathlib
import sys

OUT = pathlib.Path("ci/compat")


def main() -> int:
    tsv = pathlib.Path("results.tsv")
    if not tsv.is_file():
        print("compat-report: нет results.tsv", file=sys.stderr)
        return 1
    pin = os.environ.get("PIN", "")
    mixins = os.environ.get("MIXINS", "")
    engine = os.environ.get("ENGINE_LABEL", "")
    rows = []
    for line in tsv.read_text().splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        while len(parts) < 4:
            parts.append("")
        mod, version, stage, note = parts[:4]
        rows.append(
            {
                "id": mod,
                "version": version,
                "ok": stage == "ok",
                "stage": stage,
                "note": note.strip()[:300],
            }
        )
    short = pin[:7] if pin else "unknown"
    OUT.mkdir(parents=True, exist_ok=True)
    target = OUT / f"{short}.json"
    data = {
        "pin": pin,
        "engine": engine,
        "mixins": mixins,
        "generated": datetime.date.today().isoformat(),
        "checked": len(rows),
        "passed": sum(1 for r in rows if r["ok"]),
        "mods": rows,
    }
    target.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")

    lines = [
        f"## Совместимость с {engine or short} (`{short}`)",
        "",
        f"Проверено: **{len(rows)}**, работает: **{data['passed']}**",
        "",
        "| мод | версия | итог | примечание |",
        "| --- | --- | --- | --- |",
    ]
    for row in rows:
        mark = "✅ ok" if row["ok"] else {"apply": "❌ не легли миксины", "check": "❌ не компилируется"}.get(
            row["stage"], "❌ " + row["stage"]
        )
        note = row["note"].replace("|", "/") if row["note"] else ""
        lines.append(f"| {row['id']} | {row['version']} | {mark} | {note} |")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as fh:
            fh.write("\n".join(lines) + "\n")
    print("\n".join(lines))
    print(f"\ncompat-report: {target} ({data['passed']}/{len(rows)} ok)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
