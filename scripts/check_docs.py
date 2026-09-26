#!/usr/bin/env python3
"""Check local Markdown targets and the presence of EK1 sections; not a semantic review."""
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[1]
errors = []
files = [root / "README.md", root / "project.md", *root.glob("docs/*.md"),
         *root.glob("individual/*.md"), *root.glob("evidence/*.md")]
for path in files:
    if not path.exists():
        errors.append(f"Нет файла: {path.relative_to(root)}")
        continue
    text = path.read_text()
    for target in re.findall(r"\]\(([^)]+)\)", text):
        if target.startswith(("https://", "http://", "#", "mailto:")):
            continue
        target = target.split("#", 1)[0]
        if not (path.parent / target).exists():
            errors.append(f"{path.relative_to(root)}: нет цели {target}")
project = root / "project.md"
if project.exists():
    content = project.read_text()
    for section in ["1. Паспорт проекта", "2. Методологическая основа", "3. Текущее состояние",
                    "4. Целевой Secure SDLC", "5. Технологический профиль", "12. Использование генеративного ИИ"]:
        if f"## {section}" not in content:
            errors.append(f"Нет раздела {section}")
if errors:
    print("\n".join(errors))
    sys.exit(1)
print(f"PASS: {len(files)} документов, локальные ссылки и разделы ЭК1")
