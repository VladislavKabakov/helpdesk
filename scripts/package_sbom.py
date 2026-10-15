#!/usr/bin/env python3
"""Build an exact inventory of BOOT-INF/lib from the Gradle CycloneDX metadata."""
import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
from pathlib import Path
import uuid
import zipfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", type=Path, default=Path("build/libs/helpdesk.jar"))
    parser.add_argument("--input", type=Path, default=Path("build/reports/cyclonedx/bom.json"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    source = json.loads(args.input.read_text())
    by_file = {f"{c['name']}-{c['version']}.jar": c for c in source["components"]}
    components = []
    app_version = None
    with zipfile.ZipFile(args.jar) as jar:
        manifest = jar.read("META-INF/MANIFEST.MF").decode()
        app_version = dict(line.split(": ", 1) for line in manifest.splitlines() if ": " in line)["Implementation-Version"]
        for entry in sorted(jar.namelist()):
            if not (entry.startswith("BOOT-INF/lib/") and entry.endswith(".jar")):
                continue
            name = entry.rsplit("/", 1)[-1]
            content = jar.read(entry)
            original = by_file.get(name)
            if original:
                component = {k: original[k] for k in ("type", "group", "name", "version", "purl")}
            else:
                # Boot adds its extraction tool after runtimeClasspath resolution.
                with zipfile.ZipFile(io.BytesIO(content)) as nested:
                    manifest = nested.read("META-INF/MANIFEST.MF").decode()
                props = dict(line.split(": ", 1) for line in manifest.splitlines() if ": " in line)
                version = props.get("Implementation-Version")
                if (props.get("Automatic-Module-Name") != "spring.boot.jarmode.tools"
                        or not version or name != f"spring-boot-jarmode-tools-{version}.jar"):
                    raise ValueError(f"Unmatched packaged component: {name}")
                component = {"type": "library", "group": "org.springframework.boot", "name": "spring-boot-jarmode-tools",
                             "version": version,
                             "purl": f"pkg:maven/org.springframework.boot/spring-boot-jarmode-tools@{version}"}
            component["bom-ref"] = component["purl"]
            component["hashes"] = [{"alg": "SHA-256", "content": hashlib.sha256(content).hexdigest()}]
            components.append(component)
    if not components or len({c["bom-ref"] for c in components}) != len(components):
        raise ValueError("Empty or ambiguous inventory")
    app = dict(source["metadata"]["component"])
    app.update(type="application", version=app_version)
    app["purl"] = f"pkg:maven/{app['group']}/{app['name']}@{app_version}"
    app["bom-ref"] = app["purl"]
    app["hashes"] = [{"alg": "SHA-256", "content": hashlib.sha256(args.jar.read_bytes()).hexdigest()}]
    result = {
        "bomFormat": "CycloneDX", "specVersion": "1.6", "version": 1,
        "serialNumber": "urn:uuid:" + str(uuid.uuid4()),
        "metadata": {"timestamp": datetime.now(timezone.utc).isoformat(), "component": app,
                     "properties": [{"name": "helpdesk:scope", "value": "Exact BOOT-INF/lib inventory; flat application dependencies; excludes build/test/OS"}]},
        "components": components,
        "dependencies": [{"ref": app["bom-ref"], "dependsOn": [c["bom-ref"] for c in components]}],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(f"PASS: {len(components)} packaged libraries, each mapped and hashed")


if __name__ == "__main__":
    main()
