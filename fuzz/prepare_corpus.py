import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGETS = ("identity_import", "control_records", "tunnel_records", "handshake_records")


def prepare(target, destination):
    destination.mkdir(parents=True, exist_ok=True)
    reference = json.loads((ROOT / "tests/fixtures/reference.json").read_text(encoding="utf-8"))
    if target == "identity_import":
        seeds = {"synthetic-profile": (ROOT / "fuzz/seeds/synthetic-profile.json").read_bytes()}
    elif target == "control_records":
        seeds = {name: bytes.fromhex(reference["protocol"][name])
                 for name in ("login", "connect_login", "public_list", "join")}
    elif target == "tunnel_records":
        seeds = {"ethernet": bytes.fromhex(reference["protocol"]["ethernet"]),
                 "syn": bytes.fromhex(reference["protocol"]["syn"])}
    elif target == "handshake_records":
        incoming = json.loads((ROOT / "tests/fixtures/incoming-sh.json").read_text(encoding="utf-8"))
        seeds = {f"incoming-{i}": bytes.fromhex(value)
                 for i, value in enumerate(incoming["messages"])}
        seeds.update({f"reference-{i}": bytes.fromhex(value)
                      for i, value in enumerate(reference["sh"]["messages"])})
    else:
        raise ValueError(f"Unknown target: {target}")
    for name, value in seeds.items():
        (destination / name).write_bytes(value)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("target", choices=TARGETS)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    prepare(args.target, args.destination)
