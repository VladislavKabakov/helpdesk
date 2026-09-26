#!/usr/bin/env python3
"""Exercise the running application using synthetic accounts; never print secrets."""
import argparse
import http.cookiejar
import json
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]


class Client:
    def __init__(self, base, username, password):
        self.base = base
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        token = self.request("GET", "/api/csrf")[1]["token"]
        form = urllib.parse.urlencode({"username": username, "password": password}).encode()
        self.request("POST", "/api/login", form, 204,
                     {"Content-Type": "application/x-www-form-urlencoded", "X-CSRF-TOKEN": token})

    def request(self, method, path, body=None, expected=200, headers=None):
        headers = dict(headers or {})
        if isinstance(body, dict):
            body = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        if method == "POST" and "X-CSRF-TOKEN" not in headers:
            headers["X-CSRF-TOKEN"] = self.request("GET", "/api/csrf")[1]["token"]
        req = urllib.request.Request(self.base + path, data=body, headers=headers, method=method)
        try:
            response = self.opener.open(req, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            content = response.read()
            if response.status != expected:
                raise RuntimeError(f"{method} {path}: expected {expected}, got {response.status}")
            return response.status, json.loads(content) if content else None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    args = parser.parse_args()
    values = dict(line.split("=", 1) for line in (ROOT / ".env").read_text().splitlines()
                  if line and not line.startswith("#"))
    clients = {name: Client(args.base_url, name, values[f"DEMO_{name.upper()}_PASSWORD"])
               for name in ("alice", "bob", "agent1", "agent2")}
    alice, bob, agent, other = (clients[n] for n in ("alice", "bob", "agent1", "agent2"))
    _, ticket = alice.request("POST", "/api/tickets", {"title": "Проверка запуска", "description": "Искусственные данные"}, 201)
    path = "/api/tickets/" + ticket["id"]
    bob.request("GET", path, expected=404)
    agent.request("POST", path + "/claim")
    marker = "internal-" + uuid.uuid4().hex
    agent.request("POST", path + "/internal-notes", {"body": marker}, 204)
    assert marker not in json.dumps(alice.request("GET", path)[1])
    assert marker in json.dumps(agent.request("GET", path)[1])
    other.request("GET", path, expected=404)
    agent.request("POST", path + "/resolve", {"body": "Тест завершён"})
    alice.request("POST", path + "/reopen")
    agent.request("GET", path, expected=404)
    alice.request("POST", "/api/logout", expected=204)
    alice.request("GET", "/api/me", expected=401)
    print("PASS: вход, создание, изоляция, назначение, внутренние заметки, решение, повторное открытие, выход")


if __name__ == "__main__":
    main()
