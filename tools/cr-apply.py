#!/usr/bin/env python3
"""Apply an APPROVED change request's ```yaml openapi-patch``` blocks to contracts/openapi/openapi.yaml.

    python3 tools/cr-apply.py docs/change-requests/CR-001-document-error-responses.md --version 1.0.1

Same merge rules as tools/cr-preview.py, but edits the real contract with a round-trip YAML parser so
comments, ordering and inline style survive and the diff shows only the change. Refuses a CR whose
decision isn't "approved". Response codes are kept in order (2xx…5xx, then `default`).
"""
import argparse
import pathlib
import re
import sys

from ruamel.yaml import YAML
from ruamel.yaml.comments import CommentedMap, CommentedSeq

ROOT = pathlib.Path(__file__).resolve().parent.parent
CONTRACT = ROOT / "contracts/openapi/openapi.yaml"
METHODS = {"get", "put", "post", "delete", "patch"}
BLOCK = re.compile(r"```yaml openapi-patch( all-operations)?\n(.*?)```", re.S)

yaml = YAML()
yaml.preserve_quotes = True
yaml.width = 4096
yaml.indent(mapping=2, sequence=4, offset=2)


def styled(v):
    """New nodes: a lone $ref (or other one-key scalar map) stays inline like the rest of the file."""
    if isinstance(v, dict):
        m = CommentedMap()
        for k, x in v.items():
            m[k] = styled(x)
        if len(m) <= 2 and all(not isinstance(x, (dict, list)) for x in m.values()):
            m.fa.set_flow_style()
        return m
    if isinstance(v, list):
        s = CommentedSeq(styled(x) for x in v)
        if all(isinstance(x, str) for x in v):
            s.fa.set_flow_style()
        return s
    return v


def merge(base, patch):
    for k, v in patch.items():
        if isinstance(v, dict) and isinstance(base.get(k), dict):
            merge(base[k], v)
        elif isinstance(v, list) and isinstance(base.get(k), list):
            for i in v:
                if i not in base[k]:
                    base[k].append(styled(i))
        else:
            base[k] = styled(v)


def order_responses(spec):
    for item in spec["paths"].values():
        for m, op in item.items():
            if m in METHODS and "responses" in op:
                r = op["responses"]
                keys = sorted(r.keys(), key=lambda c: (c == "default", str(c)))
                for k in keys:
                    r.move_to_end(k) if hasattr(r, "move_to_end") else None
                if list(r.keys()) != keys:
                    items = [(k, r[k]) for k in keys]
                    for k in list(r.keys()):
                        del r[k]
                    for k, v in items:
                        r[k] = v


def dump(spec) -> str:
    import io
    buf = io.StringIO()
    yaml.dump(spec, buf)
    return buf.getvalue()


def _squash(line: str) -> str:
    """Comparison key: indentation kept, braces and other spaces ignored (ruamel rewrites both)."""
    indent = len(line) - len(line.lstrip(" "))
    return " " * indent + re.sub(r"[{}\s]", "", line)


# A flow mapping that starts a value: after ": ", "- ", "[" or ", " (never inside prose).
_OPEN = re.compile(r"(:\s|-\s|\[|,\s)\{(?=\S)")
_CLOSE = re.compile(r"(?<=[^\s{])\}(?=\s*(?:$|,|\]|\}))")


def restore_style(original: str, dumped: str) -> str:
    """ruamel drops the spaces inside `{ … }`. Lines that only differ by that spacing get their original
    text back; genuinely new lines get the file's `{ key: value }` spacing."""
    known = {}
    for line in original.splitlines():
        known.setdefault(_squash(line), line)
    out = []
    for line in dumped.splitlines():
        if line in known.values():
            out.append(line)
        elif _squash(line) in known:
            out.append(known[_squash(line)])
        else:
            out.append(_CLOSE.sub(" }", _OPEN.sub(lambda m: m.group(1) + "{ ", line)))
    return "\n".join(out) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cr")
    ap.add_argument("--version", required=True, help="new info.version of the contract")
    args = ap.parse_args()

    text = pathlib.Path(args.cr).read_text()
    # A filled-in decision, not the template's "approved / rejected / deferred" placeholder.
    if not re.search(r"^Decision: approved\s+Decision date: \d{4}-\d{2}-\d{2}", text, re.M):
        sys.exit(f"{args.cr} is not approved; refusing to change the frozen contract")
    original = CONTRACT.read_text()
    spec = yaml.load(original)
    blocks = BLOCK.findall(text)
    for every, body in blocks:
        patch = YAML(typ="safe").load(body)
        if every:
            for item in spec["paths"].values():
                for m, op in item.items():
                    if m in METHODS:
                        merge(op, patch)
        else:
            merge(spec, patch)
    order_responses(spec)
    spec["info"]["version"] = args.version
    CONTRACT.write_text(restore_style(original, dump(spec)))
    print(f"applied {len(blocks)} block(s) from {pathlib.Path(args.cr).name}; contract version {args.version}")


if __name__ == "__main__":
    main()
