import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def packages(path):
    with path.open("rb") as stream:
        return tomllib.load(stream)["package"]


def identity(package):
    return (package["name"], package["version"], package.get("source"), package.get("checksum"))


root = packages(ROOT / "Cargo.lock")
fuzz = packages(ROOT / "fuzz/Cargo.lock")
names = {package["name"] for package in root}
allowed = {identity(package) for package in root}
divergent = sorted(identity(package)[:2] for package in fuzz
                   if package["name"] in names and identity(package) not in allowed)
if divergent:
    raise SystemExit(f"Fuzz dependency versions differ from the application lockfile: {divergent}")
print("Shared fuzz dependencies match the application lockfile.")
