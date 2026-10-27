#!/usr/bin/env python3
"""Check resolved inventories, including build tooling; never add tools to APKs."""
import argparse
from pathlib import Path
import re

# Floors from the repository's Dependabot advisories. Newer compatible versions
# are accepted; these are not strict pins or dismissals of security alerts.
FLOORS = {
    "com.google.protobuf:protobuf-java": "3.25.5",
    "com.google.protobuf:protobuf-kotlin": "3.25.5",
    "com.google.protobuf:protobuf-javalite": "3.25.5",
    "org.jdom:jdom2": "2.0.6.1",
    "commons-beanutils:commons-beanutils": "1.11.0",
    "org.apache.commons:commons-lang3": "3.18.0",
    "org.bitbucket.b_c:jose4j": "0.9.6",
    "org.jsoup:jsoup": "1.23.2",
    "org.eclipse.jgit:org.eclipse.jgit": "6.10.1.202505221210-r",
    "org.apache.httpcomponents.client5:httpclient5": "5.6.3",
    "org.apache.httpcomponents.client5:httpclient5-cache": "5.6.3",
    "org.apache.httpcomponents.core5:httpcore5": "5.4.3",
    "org.apache.httpcomponents.core5:httpcore5-h2": "5.4.3",
}


def version_key(version):
    return tuple(int(part) for part in re.findall(r"\d+", version))


def violations(text, enforce_core=False):
    failures = []
    modules = [line.strip() for line in text.splitlines() if line.strip()]
    if not modules:
        return ["Empty dependency inventory"]
    for module in modules:
        parts = module.split(":")
        if len(parts) != 3:
            failures.append(f"Invalid module coordinate: {module}")
            continue
        group, name, version = parts
        if enforce_core and group == "androidx.core" and name in {"core", "core-ktx"} and version != "1.17.0":
            failures.append(f"Core baseline changed transitively: {module}; expected 1.17.0")
        floor = FLOORS.get(f"{group}:{name}")
        if group == "io.netty" and name.startswith("netty-") and not name.startswith("netty-tcnative"):
            floor = "4.1.137.Final"
        elif group == "com.fasterxml.jackson.core" and name in {"jackson-core", "jackson-databind"}:
            # 2.22.0-2.22.2 are vulnerable (high severity); 2.22.3 is the first fixed release.
            floor = "2.22.3"
        elif group == "org.bouncycastle" and name.endswith("-jdk18on"):
            floor = "1.86"
        if floor and version_key(version) < version_key(floor):
            failures.append(f"{module} is below security floor {floor}")
        if floor and re.search(r"(?i)(?:alpha|beta|rc|snapshot)", version):
            failures.append(f"Prerelease security dependency: {module}")
    return failures


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inventories", nargs="+", type=Path)
    parser.add_argument("--runtime", nargs="+", type=Path, default=[], help="APK inventories; also enforce the Core baseline")
    args = parser.parse_args()
    failures = []
    for path in args.inventories:
        failures.extend(f"{path}: {item}" for item in violations(path.read_text()))
    for path in args.runtime:
        failures.extend(f"{path}: {item}" for item in violations(path.read_text(), enforce_core=True))
    if failures:
        raise SystemExit("\n".join(failures))
    print(f"Security floors verified in {len(args.inventories) + len(args.runtime)} resolved dependency inventories")


if __name__ == "__main__":
    main()
