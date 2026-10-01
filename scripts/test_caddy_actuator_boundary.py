#!/usr/bin/env python3
"""Exercise the repository Caddyfile with isolated Caddy 2.11.4 containers.

Requires Python 3 and Docker. No Spring application, credentials, or production
resources are used. The pinned image is pulled if it is not present locally.
"""

import http.client
import json
import subprocess
import tempfile
import time
import uuid
from pathlib import Path


IMAGE = "caddy:2.11.4-alpine"
ROOT = Path(__file__).resolve().parents[1]
BLOCKED = (
    "/actuator", "/actuator/", "/actuator/prometheus", "/actuator/env",
    "/actuator/configprops", "/actuator/heapdump", "/actuator/metrics",
    "/actuator/health/", "/actuator/health/readiness",
    "/actuator/prometheus/", "/actuator/prometheus?probe=m1-1",
    "/actuator//prometheus", "//actuator/prometheus",
    "/x/../actuator/prometheus", "/actuator/health/../prometheus",
    "/actuator/health/%2e%2e/prometheus", "/%61ctuator/prometheus",
    "/actuator%2Fprometheus", "/actuator%2fprometheus",
    "/actuator/%70rometheus", "/ACTUATOR/PROMETHEUS",
    "/actuator/%68ealth", "/actuator//health", "/ACTUATOR/health",
    "/x/../actuator/health", "/actuator;v=1/prometheus",
    "/actuator%3Bv=1/prometheus",
)
ALLOWED = (
    "/actuator/health", "/actuator/health?probe=1", "/actuator/health?",
    "/api/v1/class-sessions?weekStart=2026-09-28",
    "/api/v1/reservations/42?note=a%20b", "/swagger-ui/index.html",
    "/v3/api-docs", "/actuator-other",
)
AUTH_HEADERS = {
    "Authorization": "Bearer m1-local-fixture-not-a-real-token",
    "Cookie": "session=m1-local-fixture",
    "X-Forwarded-For": "127.0.0.1",
    "X-Forwarded-Host": "localhost",
}


def docker(*args, check=True):
    result = subprocess.run(
        ["docker", *args], capture_output=True, text=True, timeout=180
    )
    if check and result.returncode:
        raise RuntimeError(f"docker {' '.join(args)}\n{result.stdout}{result.stderr}")
    return result


def eventually(check, description):
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(0.1)
    raise AssertionError(f"Timed out: {description}")


def main():
    template = (ROOT / "Caddyfile").read_text()
    assert template.count("__APP_UPSTREAM__") == 1
    if docker("image", "inspect", IMAGE, check=False).returncode:
        print(f"Pulling {IMAGE}", flush=True)
        docker("pull", IMAGE)

    prefix = f"m1-caddy-{uuid.uuid4().hex[:12]}"
    containers = []
    network_created = False
    expected = {"blue": set(), "green": set()}
    blocked_ids = set()
    request_count = 0

    with tempfile.TemporaryDirectory(prefix="pilaslot-m1-") as directory:
        work = Path(directory)
        config = work / "Caddyfile"

        def render(color):
            # Same placeholder substitution as deploy.yml; write in place so
            # the running container's single-file bind mount sees the change.
            config.write_text(template.replace("__APP_UPSTREAM__", f"app-{color}:8081"))

        def start(name, file, *options):
            containers.append(name)
            docker(
                "run", "--detach", "--name", name,
                "--network", prefix, "--mount",
                f"type=bind,source={file},target=/etc/caddy/Caddyfile,readonly",
                *options, IMAGE,
            )

        def reload_config(check=True):
            return docker(
                "exec", f"{prefix}-proxy", "caddy", "reload",
                "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile",
                check=check,
            )

        def request(path, method="GET", authenticated=False, color=None, status=404):
            nonlocal request_count
            request_count += 1
            request_id = f"m1-{request_count}"
            headers = {"X-M1-Request-Id": request_id}
            if authenticated:
                headers.update(AUTH_HEADERS)
            body = "m1-body-preservation" if method == "POST" else None
            connection = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
            try:
                # http.client preserves duplicate slashes, escapes and dot
                # segments in the request target instead of normalizing them.
                connection.request(method, path, body=body, headers=headers)
                response = connection.getresponse()
                data = response.read()
                assert response.status == status, (method, path, response.status, data)
                upstream = response.getheader("X-M1-Upstream")
                if color:
                    assert upstream == color, (path, upstream, color)
                    assert response.getheader("X-M1-Method") == method
                    assert response.getheader("X-M1-URI") == path
                    if method != "HEAD":
                        if path.split("?", 1)[0] == "/actuator/health":
                            assert json.loads(data) == {"status": "UP"}
                        else:
                            assert data == (body or "").encode(), (path, data)
                    expected[color].add(request_id)
                else:
                    assert upstream is None, (path, upstream)
                    if status == 404:
                        assert data == (b"" if method == "HEAD" else b"Not Found")
                    blocked_ids.add(request_id)
            finally:
                connection.close()

        def matrix(color):
            for method in ("GET", "HEAD", "POST"):
                for authenticated in (False, True):
                    for path in BLOCKED:
                        request(path, method, authenticated)
                    for path in ALLOWED:
                        request(path, method, authenticated, color, 200)
            request("/actuator/%zz", status=400)

        def verify_upstream_logs():
            received = {"blue": set(), "green": set()}
            for color in received:
                result = docker("logs", f"{prefix}-{color}")
                for line in (result.stdout + "\n" + result.stderr).splitlines():
                    try:
                        entry = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    headers = entry.get("request", {}).get("headers", {})
                    received[color].update(headers.get("X-M1-Request-Id", []))
                assert not (received[color] & blocked_ids), (
                    "Blocked request reached upstream", color, received[color] & blocked_ids
                )
                assert received[color] <= expected[color], (color, received[color] - expected[color])
            return received == expected

        try:
            docker("network", "create", prefix)
            network_created = True
            for color in ("blue", "green"):
                mock = work / f"{color}.Caddyfile"
                mock.write_text(
                    ':8081 {\n'
                    '\tlog {\n\t\toutput stdout\n\t\tformat json\n\t}\n'
                    f'\theader X-M1-Upstream {color}\n'
                    '\theader X-M1-Method {method}\n'
                    '\theader X-M1-URI {http.request.orig_uri}\n'
                    '\trespond /actuator/health `{"status":"UP"}` 200\n'
                    '\trespond "{http.request.body}" 200\n'
                    '}\n'
                )
                start(f"{prefix}-{color}", mock, "--network-alias", f"app-{color}")
                eventually(
                    lambda c=color: docker(
                        "exec", f"{prefix}-{c}", "wget", "-qO-",
                        "http://127.0.0.1:2019/config/", check=False,
                    ).returncode == 0,
                    f"{color} mock readiness",
                )

            render("blue")
            start(
                f"{prefix}-proxy", config, "--env", "API_DOMAIN=http://:8080",
                "--publish", "127.0.0.1::8080",
            )
            eventually(
                lambda: docker(
                    "exec", f"{prefix}-proxy", "wget", "-qO-",
                    "http://127.0.0.1:2019/config/", check=False,
                ).returncode == 0,
                "proxy readiness",
            )
            version = docker("exec", f"{prefix}-proxy", "caddy", "version").stdout.strip()
            assert version.startswith("v2.11.4 "), version
            print(version, flush=True)
            port = int(docker("port", f"{prefix}-proxy", "8080/tcp").stdout.strip().rsplit(":", 1)[1])

            for color in ("blue", "green"):
                render(color)
                for command in ("adapt", "validate"):
                    docker(
                        "exec", f"{prefix}-proxy", "caddy", command,
                        "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile",
                    )
            render("blue")
            matrix("blue")
            print("PASS blue routing and request matrix", flush=True)
            previous = config.read_text()
            render("green")
            reload_config()
            matrix("green")
            print("PASS blue -> green reload and request matrix", flush=True)
            config.write_text(previous)
            reload_config()
            request("/actuator/health", color="blue", status=200)
            request("/api/v1/class-sessions", color="blue", status=200)
            request("/actuator/prometheus")
            print("PASS previous configuration restore (green -> blue)", flush=True)

            config.write_text("http://:8080 {\n definitely_not_a_directive\n}\n")
            assert reload_config(check=False).returncode != 0
            request("/actuator/health", color="blue", status=200)
            request("/actuator/prometheus")
            config.write_text(previous)
            reload_config()
            print("PASS failed reload preserves running configuration", flush=True)

            eventually(verify_upstream_logs, "all allowed requests in upstream logs")
            # Stop mock processes without removing containers: logs must remain
            # available for the final proof that blocked requests never arrived.
            for color in ("blue", "green"):
                docker("exec", f"{prefix}-{color}", "caddy", "stop")
            request("/actuator/prometheus")
            request("/actuator/health", status=502)
            assert verify_upstream_logs()
            print("PASS blocked path stays 404 with upstream unavailable", flush=True)
            print(
                f"PASS {request_count} HTTP checks; blocked requests absent from upstream logs",
                flush=True,
            )
        except BaseException:
            for name in containers:
                result = docker("logs", "--tail", "12", name, check=False)
                print(f"Diagnostics: {name}\n{result.stdout}{result.stderr}", flush=True)
            raise
        finally:
            for name in reversed(containers):
                docker("rm", "--force", "--volumes", name, check=False)
            if network_created:
                docker("network", "rm", prefix, check=False)


if __name__ == "__main__":
    main()
