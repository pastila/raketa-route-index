"""URL → handler lookup over .idea/raketa-routes.json; a port of RouteMatcher.kt for the terminal."""
import json
import re
import sys
from urllib.parse import unquote

METHODS = {"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"}
HOST = re.compile(r"^[\w-]+(\.[\w-]+)*\.(in|online|ru|com|cnt|consul|local)(:\d+)?$", re.I)
REST = re.compile(r"^\{(\w+)(?::(.*))?}$")
LANG = re.compile(r"^(ru|en)$", re.I)


def compile_segment(seg, req):
    out, i = "", 0
    while i < len(seg):
        if seg[i] != "{":
            j = seg.find("{", i)
            j = len(seg) if j < 0 else j
            out += re.escape(seg[i:j])
            i = j
            continue
        depth, j = 0, i
        while j < len(seg):
            depth += seg[j] == "{"
            depth -= seg[j] == "}"
            if depth == 0:
                break
            j += 1
        body = seg[i + 1:j]
        name, _, inline = body.partition(":")
        out += "(?:" + (inline or req.get(name) or "[^/]+") + ")"
        i = j + 1
    try:
        return re.compile(out, re.I)
    except re.error:
        return re.compile(re.escape(seg), re.I)


class Compiled:
    def __init__(self, r):
        self.r = r
        req = r.get("requirements") or {}
        req = req if isinstance(req, dict) else {}
        raw = [s for s in r["path"].split("/") if s]
        self.rest = None
        m = REST.match(raw[-1]) if raw else None
        if m:
            rx = m.group(2) or req.get(m.group(1), "")
            if ".*" in rx or ".+" in rx:
                try:
                    self.rest = re.compile(rx, re.I)
                except re.error:
                    pass
        self.segs = [compile_segment(s, req) for s in (raw[:-1] if self.rest else raw)]
        self.literals = sum("{" not in s for s in raw)
        self.text = " ".join(filter(None, [r["path"], r["handler"], r.get("name"), r["app"]])).lower()


def parse(raw):
    s, method = raw.strip(), None
    first = s.split(" ", 1)[0]
    if first.upper() in METHODS and len(s) > len(first):
        method, s = first.upper(), s[len(first):].strip()
    text, host = s, None
    if "://" in s:
        s = s.split("://", 1)[1]
        host = s.split("/", 1)[0].split(":")[0].lower()
        s = "/" + s.split("/", 1)[1] if "/" in s else ""
    s = s.split("?")[0].split("#")[0]
    if host is None and not s.startswith("/") and "/" in s and HOST.match(s.split("/", 1)[0]):
        host = s.split("/", 1)[0].split(":")[0].lower()
        s = "/" + s.split("/", 1)[1]
    segs = [unquote(x) for x in s.split("/") if x]
    return method, host, segs, text


def bonus(r, host, method):
    b = 0
    if host and r["hosts"]:
        if host in (h.lower() for h in r["hosts"]):
            b += 100
        elif host.split(".")[0] in (h.split(".")[0].lower() for h in r["hosts"]):
            b += 80
        else:
            b -= 50
    if method and r["methods"]:
        b += 50 if method in r["methods"] else -100
    return b


def seg_match(c, qs, host, method):
    if not qs:
        return None
    rs = c.segs
    if c.rest:
        if len(qs) <= len(rs) or not all(rs[i].fullmatch(qs[i]) for i in range(len(rs))):
            return None
        if not c.rest.fullmatch("/".join(qs[len(rs):])):
            return None
        return 500 + c.literals * 5 + bonus(c.r, host, method)
    if len(qs) == len(rs) - 1 and rs[-1].fullmatch(""):
        rs = rs[:-1]
    if len(qs) > len(rs):
        return None
    off = len(rs) - len(qs)
    if not all(rs[off + i].fullmatch(q) for i, q in enumerate(qs)):
        return None
    return (1000 if off == 0 else 700 - off * 10) + c.literals * 5 + bonus(c.r, host, method)


def match(c, method, host, segs, text):
    if segs and (host or "/" in text):
        qs = segs[:-1] if segs[-1].lower() == "index.php" else segs
        score = seg_match(c, qs, host, method)
        if score is None and len(qs) > 1 and LANG.match(qs[0]):
            score = seg_match(c, qs[1:], host, method)
        if score is not None:
            return score
    tokens = text.lower().split()
    if tokens and all(t in c.text for t in tokens):
        return 300 + (20 if c.r["path"].lower().endswith(tokens[-1]) else 0) + bonus(c.r, None, method)
    return None


def main():
    path, query = sys.argv[1], sys.argv[2]
    routes = [Compiled(r) for r in json.load(open(path))["routes"]]
    q = parse(query)
    found = sorted(((s, c.r) for c in routes if (s := match(c, *q)) is not None), key=lambda x: -x[0])
    for score, r in found[:20]:
        methods = "|".join(r["methods"]) or "ANY"
        print(f"{score:5} {methods:6} {r['path']}  [{r['app']}]\n      {r['handler']}  {r['file']}:{r['line']}")
    if not found:
        print("no routes", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
