#!/usr/bin/env python3
"""Merge the ```yaml openapi-patch``` blocks of change requests into a copy of the frozen contract.

    python3 tools/cr-preview.py docs/change-requests/CR-00*.md --out /tmp/openapi.cr-preview.yaml

Mappings merge recursively; lists gain the patch's items they don't already have (so a patch can add a
global tag or a required field); any other value replaces. A block tagged `all-operations` is merged
into every operation. The frozen contracts/openapi/openapi.yaml is only read, never written.
"""
import argparse
import copy
import pathlib
import re
import sys

import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
METHODS = {"get", "put", "post", "delete", "patch"}
BLOCK = re.compile(r"```yaml openapi-patch( all-operations)?\n(.*?)```", re.S)


def merge(base, patch):
    for k, v in patch.items():
        if isinstance(v, dict) and isinstance(base.get(k), dict):
            merge(base[k], v)
        elif isinstance(v, list) and isinstance(base.get(k), list):
            base[k].extend(copy.deepcopy(i) for i in v if i not in base[k])
        else:
            base[k] = copy.deepcopy(v)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("crs", nargs="+")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    spec = yaml.safe_load((ROOT / "contracts/openapi/openapi.yaml").read_text())
    applied = 0
    for cr in sorted(args.crs):
        for every, body in BLOCK.findall(pathlib.Path(cr).read_text()):
            patch = yaml.safe_load(body)
            if every:
                for item in spec["paths"].values():
                    for m, op in item.items():
                        if m in METHODS:
                            merge(op, patch)
            else:
                merge(spec, patch)
            applied += 1
    if applied == 0:
        sys.exit("no openapi-patch blocks found")
    pathlib.Path(args.out).write_text(yaml.safe_dump(spec, sort_keys=False, allow_unicode=True))
    ops = sum(1 for item in spec["paths"].values() for m in item if m in METHODS)
    print(f"applied {applied} patch blocks from {len(args.crs)} CRs → {args.out} ({ops} operations)")


if __name__ == "__main__":
    main()
