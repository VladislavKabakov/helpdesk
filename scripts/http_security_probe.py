#!/usr/bin/env python3
"""Small, bounded real-HTTP checks; the report contains no cookies or credentials."""
import argparse
from datetime import datetime, timezone
import http.client
import http.cookiejar
import json
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    url = urllib.parse.urlsplit(args.base_url)
    if url.scheme != "http" or url.hostname not in ("127.0.0.1", "localhost", "::1"):
        parser.error("Нужен собственный локальный HTTP-экземпляр")
    env = Path(__file__).resolve().parents[1] / ".env"
    values = dict(line.split("=", 1) for line in env.read_text().splitlines() if line and not line.startswith("#"))
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    results = []

    def check(name, condition):
        if not condition:
            raise AssertionError(name)
        results.append({"check": name, "passed": True})

    def request(method, path, data=None, headers=None, anonymous=False):
        req = urllib.request.Request(args.base_url.rstrip("/") + path, data=data, headers=headers or {}, method=method)
        try:
            response = (urllib.request.urlopen(req, timeout=15) if anonymous else opener.open(req, timeout=15))
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, response.headers, response.read()

    status, headers, body = request("GET", "/api/csrf")
    cookie = headers.get("Set-Cookie", "").lower()
    check("local_cookie_httpOnly_sameSiteStrict_without_secure", status == 200 and "httponly" in cookie and "samesite=strict" in cookie and "; secure" not in cookie)
    old_session = next(c.value for c in jar if c.name == "JSESSIONID")
    token = json.loads(body)["token"]
    form = urllib.parse.urlencode({"username": "alice", "password": values["DEMO_ALICE_PASSWORD"]}).encode()
    check("login_requires_csrf", request("POST", "/api/login", form, {"Content-Type": "application/x-www-form-urlencoded"})[0] == 403)
    check("valid_login", request("POST", "/api/login", form, {"Content-Type": "application/x-www-form-urlencoded", "X-CSRF-TOKEN": token})[0] == 204)
    current_session = next(c.value for c in jar if c.name == "JSESSIONID")
    check("session_id_rotated", current_session != old_session)
    check("old_session_rejected", request("GET", "/api/me", headers={"Cookie": "JSESSIONID=" + old_session}, anonymous=True)[0] == 401)
    status, headers, _ = request("GET", "/api/me", headers={"Origin": "https://untrusted.invalid"})
    check("cors_does_not_grant_foreign_origin", status == 200 and headers.get("Access-Control-Allow-Origin") is None)
    check("nosniff_header", headers.get("X-Content-Type-Options") == "nosniff")
    check("mutation_requires_csrf", request("POST", "/api/tickets", b'{"title":"X","description":"Y"}', {"Content-Type": "application/json"})[0] == 403)
    _, _, body = request("GET", "/api/csrf")
    token = json.loads(body)["token"]
    status, _, body = request("POST", "/api/tickets", b'{"title":', {"Content-Type": "application/json", "X-CSRF-TOKEN": token})
    check("malformed_json_generic_400", status == 400 and all(x not in body for x in (b"Exception", b"stackTrace", b"password", b"org.springframework")))
    connection = http.client.HTTPConnection(url.hostname, url.port or 80, timeout=15)
    try:
        connection.request("POST", "/api/tickets", body=iter([b"x" * 4096] * 9),
                           headers={"Content-Type": "application/json"}, encode_chunked=True)
        response = connection.getresponse()
        check("chunked_json_above_32KiB_rejected", response.status == 413)
        response.read()
    finally:
        connection.close()
    check("logout", request("POST", "/api/logout", headers={"X-CSRF-TOKEN": token})[0] == 204)
    check("logged_out_cookie_rejected", request("GET", "/api/me", headers={"Cookie": "JSESSIONID=" + current_session}, anonymous=True)[0] == 401)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"checked_at": datetime.now(timezone.utc).isoformat(), "transport": "HTTP over loopback", "checks": results, "passed": True}, ensure_ascii=False, indent=2) + "\n")
    print(f"PASS: {len(results)} real HTTP security checks")


if __name__ == "__main__":
    main()
