#!/usr/bin/env python3
"""Bounded before/after login check against an explicitly selected local instance."""
import argparse
from datetime import datetime, timezone
import http.cookiejar
import json
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--expect", choices=("unlimited", "limited"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    parsed = urllib.parse.urlsplit(args.base_url)
    if parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "localhost", "::1"):
        parser.error("Проверка разрешена только для собственного локального HTTP-экземпляра")
    base = args.base_url.rstrip("/")
    env = Path(__file__).resolve().parents[1] / ".env"
    values = dict(line.split("=", 1) for line in env.read_text().splitlines()
                  if line and not line.startswith("#"))
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def request(method, path, data=None, headers=None):
        req = urllib.request.Request(base + path, data=data, headers=headers or {}, method=method)
        try:
            response = opener.open(req, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, dict(response.headers), response.read()

    code, _, body = request("GET", "/api/csrf")
    assert code == 200, f"CSRF endpoint: {code}"
    csrf = json.loads(body)["token"]

    def login(password, extra=None):
        headers = {"Content-Type": "application/x-www-form-urlencoded", "X-CSRF-TOKEN": csrf}
        headers.update(extra or {})
        form = urllib.parse.urlencode({"username": "alice", "password": password}).encode()
        return request("POST", "/api/login", form, headers)

    started = datetime.now(timezone.utc).isoformat()
    attempts = [login("Synthetic-wrong-password-for-EK2") for _ in range(12)]
    statuses = [result[0] for result in attempts]
    expected = [401] * 12 if args.expect == "unlimited" else [401] * 10 + [429] * 2
    assert statuses == expected, f"Ожидалось {expected}, получено {statuses}. Нужен свежий запуск приложения."
    spoof_status = None
    waited = 0
    if args.expect == "limited":
        spoof_status = login("Synthetic-wrong-password-for-EK2", {"X-Forwarded-For": "192.0.2.55"})[0]
        assert spoof_status == 429, f"Подмена X-Forwarded-For: {spoof_status}"
        remaining = int(attempts[-1][1]["Retry-After"])
        assert 1 <= remaining <= 60, f"Retry-After вне границ: {remaining}"
        waited = remaining + 0.1
        time.sleep(waited)
    recovered = login(values["DEMO_ALICE_PASSWORD"])[0]
    assert recovered == 204, f"Обычный вход после окна: {recovered}"
    result = {
        "started_at": started,
        "finished_at": datetime.now(timezone.utc).isoformat(),
        "scope": "Fresh local instance, one transport address, 12 failed logins, synthetic account",
        "expected_mode": args.expect,
        "failed_login_statuses": statuses,
        "spoofed_forwarded_for_status": spoof_status,
        "recovery_wait_seconds": waited,
        "valid_login_status": recovered,
        "passed": True,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(f"PASS: {args.expect}, statuses={statuses}, valid_login={recovered}")


if __name__ == "__main__":
    main()
