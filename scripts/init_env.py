#!/usr/bin/env python3
"""Create local synthetic credentials without printing or overwriting them."""
import os
import secrets
from pathlib import Path

target = Path(__file__).resolve().parents[1] / ".env"
keys = ["POSTGRES_PASSWORD", "DB_PASSWORD", "DEMO_ALICE_PASSWORD", "DEMO_BOB_PASSWORD",
        "DEMO_AGENT1_PASSWORD", "DEMO_AGENT2_PASSWORD"]
try:
    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
except FileExistsError:
    print(".env уже существует, оставлен без изменений.")
else:
    with os.fdopen(fd, "w") as stream:
        stream.write("# Только локальные учебные данные. Не добавлять в Git.\n")
        for key in keys:
            stream.write(f"{key}={secrets.token_urlsafe(24)}\n")
    print("Создан .env с правами 600. Пароли не выводятся в журнал.")
