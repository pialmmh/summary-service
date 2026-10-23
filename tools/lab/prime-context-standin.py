#!/usr/bin/env python3
"""A stand-in for prime-context's ONE read road the summary side uses, for the lab only.

    POST /get-specific-tenant-root?name=<root>   ->   the tenant tree: {"dbName", "parent", "children": {name: tenant}, "context"}

It answers the tree that is in a JSON file, read at every request — the lab script rewrites the file when it
"provisions" a reseller, as prime-context's tree changes after a rebuild. It listens on 127.0.0.1 only. The real
prime-context binds a 10.10.x.x address (its BindGuard) and brings its own database, change feed and doorbell: this
lab runs summary-service alone, so its tree comes from here. (A lab that does run the real one gives it an address
of this box; the lab key accepts that — brief S11.) Nothing else of prime-context is imitated: no write road exists.

    prime-context-standin.py <port> <tree.json> [bind address, default 127.0.0.1]
"""
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT, TREE = int(sys.argv[1]), sys.argv[2]
BIND = sys.argv[3] if len(sys.argv) > 3 else "127.0.0.1"   # another address only inside a lab's own network namespace


class Roads(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path.split("?")[0] != "/get-specific-tenant-root":
            self.send_response(404); self.end_headers(); return
        with open(TREE, "rb") as tree:
            body = tree.read()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self.send_response(405); self.end_headers()

    def log_message(self, fmt, *args):
        sys.stderr.write("prime-context stand-in: " + fmt % args + "\n")


HTTPServer((BIND, PORT), Roads).serve_forever()
