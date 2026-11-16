#!/usr/bin/env python3
"""
FTLink PC  -  chat + file-transfer hub for the FTLink Android app.

  pip install aiohttp
  python ftlink_pc.py            # GUI
  python ftlink_pc.py --headless # server only (prints events)

The phone connects to this PC over Wi-Fi/LAN. Chat goes over a WebSocket,
files go over HTTP (streamed, so any size / any type works).
"""
import argparse, asyncio, itertools, json, os, platform, queue, secrets, socket
import subprocess, sys, threading, time, uuid
from pathlib import Path

from aiohttp import web, WSMsgType

HTTP_PORT = 8765
DISC_PORT = 8766
APP_DIR = Path.home() / "FTLink"
RECV_DIR = APP_DIR / "Received"
CFG_FILE = APP_DIR / "config.json"


# ----------------------------------------------------------------- helpers
def load_token() -> str:
    APP_DIR.mkdir(exist_ok=True)
    RECV_DIR.mkdir(parents=True, exist_ok=True)
    try:
        return json.loads(CFG_FILE.read_text())["token"]
    except Exception:
        t = f"{secrets.randbelow(10**6):06d}"
        CFG_FILE.write_text(json.dumps({"token": t}))
        return t


def local_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


def unique_path(p: Path) -> Path:
    if not p.exists():
        return p
    i = 1
    while True:
        c = p.with_name(f"{p.stem} ({i}){p.suffix}")
        if not c.exists():
            return c
        i += 1


def fmt_size(n: int) -> str:
    n = float(n)
    for u in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024 or u == "TB":
            return f"{n:.0f} {u}" if u == "B" else f"{n:.1f} {u}"
        n /= 1024


def open_path(p) -> None:
    try:
        if platform.system() == "Windows":
            os.startfile(str(p))  # type: ignore[attr-defined]
        elif platform.system() == "Darwin":
            subprocess.Popen(["open", str(p)])
        else:
            subprocess.Popen(["xdg-open", str(p)])
    except Exception as e:
        print("Cannot open", p, e)


# ------------------------------------------------------------------ server
class Server:
    def __init__(self, token: str, ui_queue: "queue.Queue"):
        self.token = token
        self.q = ui_queue
        self.clients: set = set()
        self.registry: dict = {}      # file id -> Path (files the phone may download)
        self.history: list = []       # PC->phone messages, replayed on (re)connect
        self.loop: asyncio.AbstractEventLoop | None = None
        self.ready = threading.Event()

    # --- auth
    def _auth(self, req: web.Request):
        if not secrets.compare_digest(req.query.get("token", ""), self.token):
            raise web.HTTPUnauthorized(text="bad token")

    # --- websocket (chat + notifications)
    async def ws_handler(self, req: web.Request):
        self._auth(req)
        ws = web.WebSocketResponse(heartbeat=20)
        await ws.prepare(req)
        self.clients.add(ws)
        self.q.put(("clients", len(self.clients)))
        try:
            for m in self.history[-200:]:           # catch up on missed messages
                await ws.send_str(json.dumps(m))
            async for msg in ws:
                if msg.type == WSMsgType.TEXT:
                    try:
                        d = json.loads(msg.data)
                    except ValueError:
                        continue
                    if d.get("type") == "chat" and d.get("text"):
                        self.q.put(("msg", {"type": "chat", "id": d.get("id", uuid.uuid4().hex),
                                            "text": d["text"], "ts": time.time(), "from": "phone"}))
        finally:
            self.clients.discard(ws)
            self.q.put(("clients", len(self.clients)))
        return ws

    # --- phone -> PC file (raw body stream)
    async def upload(self, req: web.Request):
        self._auth(req)
        name = os.path.basename(req.query.get("name", "file")) or "file"
        dest = unique_path(RECV_DIR / name)
        loop = asyncio.get_running_loop()
        size = 0
        with open(dest, "wb") as f:
            async for chunk in req.content.iter_chunked(1 << 20):
                await loop.run_in_executor(None, f.write, chunk)
                size += len(chunk)
        fid = uuid.uuid4().hex
        self.registry[fid] = dest
        self.q.put(("msg", {"type": "file", "id": fid, "name": dest.name, "size": size,
                            "ts": time.time(), "from": "phone", "path": str(dest)}))
        return web.json_response({"id": fid})

    # --- PC -> phone file download (supports Range / resume)
    async def download(self, req: web.Request):
        self._auth(req)
        p = self.registry.get(req.match_info["fid"])
        if not p or not p.exists():
            raise web.HTTPNotFound()
        return web.FileResponse(p)

    # --- discovery: phone broadcasts, we answer
    class _Disc(asyncio.DatagramProtocol):
        def connection_made(self, t):
            self.t = t

        def datagram_received(self, data, addr):
            if data.strip() == b"FTLINK_DISCOVER":
                self.t.sendto(json.dumps({"app": "ftlink", "port": HTTP_PORT,
                                          "name": socket.gethostname()}).encode(), addr)

    async def _start(self):
        app = web.Application()
        app.add_routes([web.get("/ws", self.ws_handler),
                        web.post("/upload", self.upload),
                        web.get("/files/{fid}", self.download)])
        runner = web.AppRunner(app)
        await runner.setup()
        await web.TCPSite(runner, "0.0.0.0", HTTP_PORT).start()
        try:
            await self.loop.create_datagram_endpoint(self._Disc, local_addr=("0.0.0.0", DISC_PORT),
                                                     allow_broadcast=True)
        except Exception as e:
            print("Discovery disabled:", e)
        self.ready.set()

    def start_thread(self):
        def run():
            self.loop = asyncio.new_event_loop()
            asyncio.set_event_loop(self.loop)
            self.loop.run_until_complete(self._start())
            self.loop.run_forever()
        threading.Thread(target=run, daemon=True).start()
        self.ready.wait(10)

    # --- PC -> phone
    async def _broadcast(self, msg: dict):
        self.history.append(msg)
        for ws in list(self.clients):
            try:
                await ws.send_str(json.dumps(msg))
            except Exception:
                pass

    def _push(self, msg: dict):
        asyncio.run_coroutine_threadsafe(self._broadcast(msg), self.loop)

    def send_text(self, text: str):
        msg = {"type": "chat", "id": uuid.uuid4().hex, "text": text, "ts": time.time()}
        self._push(msg)
        self.q.put(("msg", {**msg, "from": "pc"}))

    def send_file(self, path):
        p = Path(path)
        fid = uuid.uuid4().hex
        self.registry[fid] = p
        msg = {"type": "file", "id": fid, "name": p.name, "size": p.stat().st_size, "ts": time.time()}
        self._push(msg)
        self.q.put(("msg", {**msg, "from": "pc", "path": str(p)}))


# --------------------------------------------------------------------- GUI
def run_gui(server: Server, token: str, q: "queue.Queue"):
    import tkinter as tk
    from tkinter import filedialog, scrolledtext

    root = tk.Tk()
    root.title("FTLink PC")
    root.geometry("660x720")

    top = tk.Frame(root)
    top.pack(fill="x", padx=8, pady=6)
    tk.Label(top, text=f"Address: {local_ip()}    Port: {HTTP_PORT}    Token: {token}",
             font=("Segoe UI", 11, "bold")).pack(anchor="w")
    status = tk.Label(top, text="Phones connected: 0", anchor="w")
    status.pack(anchor="w")

    chat = scrolledtext.ScrolledText(root, state="disabled", wrap="word", font=("Segoe UI", 10))
    chat.pack(fill="both", expand=True, padx=8)
    chat.tag_configure("me", justify="right", background="#d7ecff", rmargin=8, spacing1=4, spacing3=4)
    chat.tag_configure("them", justify="left", background="#eeeeee", lmargin1=8, lmargin2=8,
                       spacing1=4, spacing3=4)
    chat.tag_configure("meta", foreground="#888888", font=("Segoe UI", 8))
    chat.tag_configure("link", foreground="#0b57d0", underline=True)
    counter = itertools.count()

    def add(m: dict):
        mine = m["from"] == "pc"
        tag = "me" if mine else "them"
        chat.config(state="normal")
        if m["type"] == "chat":
            chat.insert("end", m["text"] + "\n", tag)
        else:
            lt = f"lk{next(counter)}"
            chat.insert("end", "[file] ", tag)
            chat.insert("end", f'{m["name"]} ({fmt_size(m["size"])})', (tag, "link", lt))
            chat.insert("end", "\n", tag)
            chat.tag_bind(lt, "<Button-1>", lambda e, p=m["path"]: open_path(p))
            chat.tag_bind(lt, "<Enter>", lambda e: chat.config(cursor="hand2"))
            chat.tag_bind(lt, "<Leave>", lambda e: chat.config(cursor=""))
        stamp = time.strftime("%H:%M", time.localtime(m.get("ts", time.time())))
        chat.insert("end", f'{"You" if mine else "Phone"} · {stamp}\n', "meta")
        chat.config(state="disabled")
        chat.see("end")

    bar = tk.Frame(root)
    bar.pack(fill="x", padx=8, pady=8)
    entry = tk.Entry(bar, font=("Segoe UI", 11))
    entry.pack(side="left", fill="x", expand=True)

    def send_text(_=None):
        t = entry.get().strip()
        if t:
            server.send_text(t)
            entry.delete(0, "end")

    def send_files():
        for p in filedialog.askopenfilenames(title="Send files to phone"):
            server.send_file(p)

    entry.bind("<Return>", send_text)
    tk.Button(bar, text="Send", command=send_text).pack(side="left", padx=4)
    tk.Button(bar, text="Send files…", command=send_files).pack(side="left")
    tk.Button(bar, text="Received folder", command=lambda: open_path(RECV_DIR)).pack(side="left", padx=4)

    def poll():
        try:
            while True:
                kind, data = q.get_nowait()
                if kind == "msg":
                    add(data)
                elif kind == "clients":
                    status.config(text=f"Phones connected: {data}")
        except queue.Empty:
            pass
        root.after(100, poll)

    poll()
    entry.focus()
    root.mainloop()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--headless", action="store_true")
    args = ap.parse_args()
    token = load_token()
    q: queue.Queue = queue.Queue()
    srv = Server(token, q)
    srv.start_thread()
    print(f"FTLink PC listening on {local_ip()}:{HTTP_PORT}  token={token}")
    print(f"Received files folder: {RECV_DIR}")
    if args.headless:
        try:
            while True:
                kind, data = q.get()
                print(kind, data)
        except KeyboardInterrupt:
            pass
    else:
        run_gui(srv, token, q)


if __name__ == "__main__":
    main()
