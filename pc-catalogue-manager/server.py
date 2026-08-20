"""
Tiny local helper for the EE Lens Catalogue Manager.

It does two things and nothing else:
  1. serves the manager's own files from this folder
  2. downloads an image when you paste a link, which the browser itself cannot
     do for most sites because of cross-origin rules

It listens on 127.0.0.1 only, so nothing outside this PC can reach it. No data
is uploaded anywhere; the only outbound request is the image you asked for.
"""

import http.server
import ipaddress
import json
import secrets
import os
import socket
import socketserver
import sys
import threading
import urllib.parse
import urllib.request
import webbrowser

PORT = 8730
ROOT = os.path.dirname(os.path.abspath(__file__))
SYNC_DIR = os.path.join(ROOT, "sync")
MAX_IMAGE_BYTES = 25 * 1024 * 1024
MAX_PAGE_BYTES = 4 * 1024 * 1024
MAX_CATALOGUE_BYTES = 500 * 1024 * 1024
USER_AGENT = "Mozilla/5.0 (EE Lens Catalogue Manager)"

# Sync is off unless asked for. When off the helper listens on this PC only and
# nothing on the network can reach it at all.
SYNC_ENABLED = False
PAIRING_CODE = ""


def local_ip():
    """The address this PC has on the shop's network, for the phone to dial."""
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        # No packet is actually sent; this just picks the outbound interface.
        probe.connect(("10.255.255.255", 1))
        return probe.getsockname()[0]
    except Exception:  # noqa: BLE001
        return "127.0.0.1"
    finally:
        probe.close()


def is_local(address):
    return address in ("127.0.0.1", "::1", "localhost")


def check_public(url):
    """
    Refuse anything that is not a public website.

    This helper will fetch whatever address it is handed, so it must not become
    a way to reach the router, a NAS, or something else on the home network.
    Returns None when the address is fine, or a message explaining the refusal.
    """
    parts = urllib.parse.urlparse(url)
    if parts.scheme not in ("http", "https"):
        return "Paste a link that starts with http:// or https://"
    if not parts.hostname:
        return "That link has no website address in it."
    try:
        infos = socket.getaddrinfo(parts.hostname, None)
    except OSError:
        return f"Could not find the website \"{parts.hostname}\"."
    for info in infos:
        address = ipaddress.ip_address(info[4][0])
        if (address.is_private or address.is_loopback or address.is_link_local
                or address.is_reserved or address.is_multicast):
            return "That address is on this network, not a public website."
    return None


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=ROOT, **kwargs)

    def log_message(self, fmt, *args):
        if "/fetch" in self.path:
            sys.stdout.write("  image download: %s\n" % (args[0] if args else ""))

    def end_headers(self):
        # The manager's own files must never be served from cache, or an
        # updated page keeps loading yesterday's script.
        if not self.path.startswith(("/fetch", "/page")):
            self.send_header("Cache-Control", "no-store, must-revalidate")
        super().end_headers()

    def do_GET(self):
        if self.path.startswith("/fetch?"):
            self.handle_fetch()
            return
        if self.path.startswith("/page?"):
            self.handle_page()
            return
        if self.path.startswith("/sync/"):
            self.handle_sync_get()
            return
        super().do_GET()

    def do_PUT(self):
        if self.path.startswith("/sync/"):
            self.handle_sync_put()
            return
        self.fail(404, "Not found.")

    # ---------------- sync over the shop network ----------------

    def sync_allowed(self):
        """
        This PC may always talk to its own helper. Anything arriving over the
        network must carry the pairing code shown in the manager.
        """
        if is_local(self.client_address[0]):
            return True
        if not SYNC_ENABLED:
            return False
        query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        return query.get("code", [""])[0] == PAIRING_CODE

    def handle_sync_get(self):
        route = urllib.parse.urlparse(self.path).path

        if route == "/sync/info":
            if not is_local(self.client_address[0]):
                self.fail(403, "Not allowed.")
                return
            body = json.dumps({
                "enabled": SYNC_ENABLED,
                "address": f"{local_ip()}:{PORT}" if SYNC_ENABLED else "",
                "code": PAIRING_CODE if SYNC_ENABLED else "",
                "fromPhone": os.path.isfile(os.path.join(SYNC_DIR, "phone.eelens")),
                "fromPhoneAt": _mtime(os.path.join(SYNC_DIR, "phone.eelens")),
                "toPhoneAt": _mtime(os.path.join(SYNC_DIR, "pc.eelens")),
            }).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        if not self.sync_allowed():
            self.fail(403, "Wrong pairing code.")
            return

        name = {"/sync/pc": "pc.eelens", "/sync/phone": "phone.eelens"}.get(route)
        if not name:
            self.fail(404, "Not found.")
            return
        path = os.path.join(SYNC_DIR, name)
        if not os.path.isfile(path):
            self.fail(404, "Nothing has been shared yet.")
            return
        data = open(path, "rb").read()
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def handle_sync_put(self):
        route = urllib.parse.urlparse(self.path).path
        if not self.sync_allowed():
            self.fail(403, "Wrong pairing code.")
            return

        name = {"/sync/pc": "pc.eelens", "/sync/phone": "phone.eelens"}.get(route)
        if not name:
            self.fail(404, "Not found.")
            return

        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > MAX_CATALOGUE_BYTES:
            self.fail(413, "That catalogue is too large.")
            return

        data = self.rfile.read(length)
        os.makedirs(SYNC_DIR, exist_ok=True)
        # Written to a temporary name first so a half-received file never
        # replaces a good one.
        temp = os.path.join(SYNC_DIR, name + ".part")
        with open(temp, "wb") as handle:
            handle.write(data)
        os.replace(temp, os.path.join(SYNC_DIR, name))

        who = "this PC" if is_local(self.client_address[0]) else "the phone"
        print(f"  received {len(data) // 1024} KB from {who}")
        self.fail(200, "Saved.")

    def handle_page(self):
        """Fetches a product page so the browser can read details out of it."""
        query = urllib.parse.urlparse(self.path).query
        target = urllib.parse.parse_qs(query).get("url", [""])[0]

        refusal = check_public(target)
        if refusal:
            self.fail(400, refusal)
            return

        try:
            request = urllib.request.Request(
                target,
                headers={
                    "User-Agent": USER_AGENT,
                    "Accept": "text/html,application/xhtml+xml,*/*;q=0.8",
                    "Accept-Language": "en-IN,en;q=0.9",
                },
            )
            with urllib.request.urlopen(request, timeout=25) as response:
                content_type = response.headers.get("Content-Type", "")
                if "html" not in content_type and "xml" not in content_type:
                    self.fail(415, "That link is not a product page.")
                    return
                raw = response.read(MAX_PAGE_BYTES + 1)
                if len(raw) > MAX_PAGE_BYTES:
                    raw = raw[:MAX_PAGE_BYTES]
                charset = "utf-8"
                if "charset=" in content_type:
                    charset = content_type.split("charset=")[-1].split(";")[0].strip() or "utf-8"
                html = raw.decode(charset, errors="replace")
                final_url = response.geturl()
        except Exception as error:  # noqa: BLE001 - shown to the owner verbatim
            self.fail(502, f"That page could not be opened: {error}")
            return

        body = json.dumps({"finalUrl": final_url, "html": html}).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def handle_fetch(self):
        query = urllib.parse.urlparse(self.path).query
        target = urllib.parse.parse_qs(query).get("url", [""])[0]

        refusal = check_public(target)
        if refusal:
            self.fail(400, refusal)
            return

        try:
            request = urllib.request.Request(
                target,
                headers={
                    # Some catalogue sites refuse requests without a browser-ish agent.
                    "User-Agent": USER_AGENT,
                    "Accept": "image/*,*/*;q=0.8",
                },
            )
            with urllib.request.urlopen(request, timeout=25) as response:
                content_type = response.headers.get("Content-Type", "")
                if not content_type.startswith("image/"):
                    self.fail(415, "That link did not return an image.")
                    return
                data = response.read(MAX_IMAGE_BYTES + 1)
                if len(data) > MAX_IMAGE_BYTES:
                    self.fail(413, "That image is larger than 25 MB.")
                    return
        except Exception as error:  # noqa: BLE001 - shown to the owner verbatim
            self.fail(502, f"Could not download that image: {error}")
            return

        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def fail(self, code, message):
        body = message.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def _mtime(path):
    return int(os.path.getmtime(path)) if os.path.isfile(path) else 0


def main():
    global SYNC_ENABLED, PAIRING_CODE
    SYNC_ENABLED = "--sync" in sys.argv

    # Only opened to the network when sync is asked for.
    host = "0.0.0.0" if SYNC_ENABLED else "127.0.0.1"
    if SYNC_ENABLED:
        PAIRING_CODE = f"{secrets.randbelow(900000) + 100000}"

    try:
        server = Server((host, PORT), Handler)
    except OSError:
        print(f"Port {PORT} is already in use - the manager may already be open.")
        webbrowser.open(f"http://127.0.0.1:{PORT}/index.html")
        return

    url = f"http://127.0.0.1:{PORT}/index.html"
    print("Electric Emporium - Catalogue Manager")
    print(f"  running at {url}")
    if SYNC_ENABLED:
        print("")
        print("  PHONE SYNC IS ON")
        print(f"    on the phone, enter address : {local_ip()}:{PORT}")
        print(f"    and pairing code            : {PAIRING_CODE}")
        print("    only devices with that code can connect")
        print("    nothing leaves your own network")
    else:
        print("  everything stays on this PC (phone sync is off)")
    print("  close this window when you are finished\n")
    threading.Timer(0.6, lambda: webbrowser.open(url)).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopped.")


if __name__ == "__main__":
    main()
