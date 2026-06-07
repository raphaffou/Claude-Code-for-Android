import asyncio
import json
import os
import subprocess
import threading
from http.server import HTTPServer, BaseHTTPRequestHandler
from websockets.server import serve as ws_serve

CLAUDE_BIN = os.path.expanduser("~/.nvm/versions/node/v22.22.3/bin/claude")
WORK_DIR   = "/mnt/sdcard/claude_code"
HTTP_PORT  = 8765
WS_PORT    = 8766

# ── HTTP (ping / stop) ───────────────────────────────────────────────────────

class HTTPHandler(BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def do_GET(self):
        if self.path == "/ping":
            self._json(200, {"status": "ok"})
        elif self.path == "/stop":
            self._json(200, {"status": "stopping"})
            threading.Thread(target=lambda: (http_server.shutdown(), os._exit(0)), daemon=True).start()
        elif self.path.startswith("/load_session/"):
            session_id = self.path[len("/load_session/"):].strip("/")
            self._load_session(session_id)
        else:
            self.send_response(404); self.end_headers()

    def _load_session(self, session_id):
        import glob
        pattern = os.path.expanduser(f"~/.claude/projects/*/{session_id}.jsonl")
        files = glob.glob(pattern)
        if not files:
            self._json(404, {"error": "session not found"}); return

        messages = []
        with open(files[0], encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line: continue
                try:
                    obj = json.loads(line)
                    if obj.get("isSidechain"): continue
                    t = obj.get("type")

                    if t == "user":
                        content = obj.get("message", {}).get("content", "")
                        if isinstance(content, list):
                            text = "\n".join(
                                c.get("text", "") for c in content
                                if c.get("type") == "text"
                            )
                        else:
                            text = str(content)
                        if text.strip():
                            messages.append({"role": "user", "text": text.strip()})

                    elif t == "assistant":
                        content = obj.get("message", {}).get("content", [])
                        text = thinking = ""
                        if isinstance(content, list):
                            for c in content:
                                if c.get("type") == "text":
                                    text += c.get("text", "")
                                elif c.get("type") == "thinking":
                                    thinking += c.get("thinking", "")
                        elif isinstance(content, str):
                            text = content
                        if text.strip():
                            messages.append({"role": "assistant", "text": text.strip(),
                                             "thinking": thinking})
                except Exception:
                    pass

        self._json(200, {"messages": messages, "count": len(messages)})

    def _json(self, status, data):
        body = json.dumps(data).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try: self.wfile.write(body)
        except BrokenPipeError: pass

class ReusableTCPServer(HTTPServer):
    allow_reuse_address = True

http_server = ReusableTCPServer(("127.0.0.1", HTTP_PORT), HTTPHandler)

# ── Helpers ──────────────────────────────────────────────────────────────────

def build_prompt(history: list, new_message: str) -> str:
    parts = []
    for turn in history:
        role = "User" if turn["role"] == "user" else "Assistant"
        parts.append(f"{role}: {turn['content']}")
    parts.append(f"User: {new_message}")
    parts.append("Assistant:")
    return "\n\n".join(parts)

async def safe_send(ws, data: dict):
    try:
        await ws.send(json.dumps(data, ensure_ascii=False))
    except Exception:
        pass

# ── Stream-json event dispatcher ─────────────────────────────────────────────

# Accumule le texte/thinking déjà envoyé pour éviter les doublons avec --include-partial-messages
_sent_text    = ""
_sent_thinking = ""

async def dispatch(ws, event: dict):
    global _sent_text, _sent_thinking
    etype = event.get("type")

    if etype == "assistant":
        for content in event.get("message", {}).get("content", []):
            ctype = content.get("type")

            if ctype == "thinking":
                full = content.get("thinking", "")
                delta = full[len(_sent_thinking):]
                if delta:
                    _sent_thinking = full
                    await safe_send(ws, {"type": "thinking", "text": delta})

            elif ctype == "text":
                full = content.get("text", "")
                delta = full[len(_sent_text):]
                if delta:
                    _sent_text = full
                    await safe_send(ws, {"type": "text_delta", "text": delta})

            elif ctype == "tool_use":
                await safe_send(ws, {
                    "type": "tool_use",
                    "id":    content.get("id", ""),
                    "tool":  content.get("name", ""),
                    "input": json.dumps(content.get("input", {}), ensure_ascii=False),
                })

    elif etype == "user":
        for content in event.get("message", {}).get("content", []):
            if content.get("type") == "tool_result":
                raw = content.get("content", "")
                if isinstance(raw, list):
                    raw = "\n".join(c.get("text", "") for c in raw if c.get("type") == "text")
                await safe_send(ws, {
                    "type":        "tool_result",
                    "tool_use_id": content.get("tool_use_id", ""),
                    "content":     str(raw)[:3000],
                })

# ── Chat handler ─────────────────────────────────────────────────────────────

_current_proc: "asyncio.subprocess.Process | None" = None

async def handle_chat(ws, data: dict):
    global _current_proc
    text      = data.get("text", "")
    history   = data.get("history", [])
    settings  = data.get("settings", {})
    resume_id = data.get("resume_id")

    base_flags = ["--output-format", "stream-json", "--verbose", "--include-partial-messages"]

    if resume_id:
        cmd = [CLAUDE_BIN, "--resume", resume_id, "-p", text] + base_flags
    else:
        prompt = build_prompt(history, text)
        cmd = [CLAUDE_BIN, "-p", prompt] + base_flags

    model = settings.get("model")
    if model:
        cmd.extend(["--model", model])

    allowed = settings.get("allowedTools")
    if allowed is not None:
        cmd.extend(["--allowedTools", ",".join(allowed)] if allowed else ["--allowedTools", ""])

    if settings.get("dangerouslySkipPermissions"):
        cmd.append("--dangerously-skip-permissions")

    max_turns = settings.get("maxTurns", 10)
    cmd.extend(["--max-turns", str(max_turns)])

    global _sent_text, _sent_thinking
    _sent_text = ""
    _sent_thinking = ""
    proc = None
    try:
        print(f"CMD: {' '.join(cmd)}", flush=True)
        proc = await asyncio.create_subprocess_exec(
            *cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            cwd=WORK_DIR,
        )
        _current_proc = proc

        full_text     = ""
        session_id    = ""
        input_tokens  = 0

        async for raw_line in proc.stdout:
            line = raw_line.decode(errors="replace").strip()
            if not line:
                continue
            try:
                event = json.loads(line)

                # message_start (visible avec --verbose) contient input_tokens fiables
                if event.get("type") == "message_start":
                    u = event.get("message", {}).get("usage", {})
                    t = (u.get("input_tokens", 0)
                         + u.get("cache_read_input_tokens", 0)
                         + u.get("cache_creation_input_tokens", 0))
                    if t > 0:
                        input_tokens = t

                await dispatch(ws, event)

                if event.get("type") == "result":
                    full_text  = event.get("result", "")
                    session_id = event.get("session_id", "")
                    # Certaines versions du CLI mettent usage ici aussi
                    u = event.get("usage", {})
                    t = (u.get("input_tokens", 0)
                         + u.get("cache_read_input_tokens", 0)
                         + u.get("cache_creation_input_tokens", 0))
                    if t > 0:
                        input_tokens = t

            except json.JSONDecodeError:
                pass

        await proc.wait()
        stderr_out = await proc.stderr.read()
        if stderr_out:
            print(f"STDERR: {stderr_out.decode(errors='replace')[:500]}", flush=True)
        await safe_send(ws, {
            "type":         "done",
            "text":         full_text,
            "session_id":   session_id,
            "input_tokens": input_tokens,
        })

    except asyncio.CancelledError:
        if proc:
            try: proc.kill()
            except Exception: pass
        raise

    except Exception as e:
        await safe_send(ws, {"type": "error", "message": str(e)})

    finally:
        _current_proc = None

# ── WebSocket handler ────────────────────────────────────────────────────────

_current_task: "asyncio.Task | None" = None

async def ws_handler(ws):
    global _current_task, _current_proc
    try:
        async for raw in ws:
            try:
                data = json.loads(raw)
            except json.JSONDecodeError:
                continue

            msg_type = data.get("type")

            if msg_type == "cancel":
                if _current_proc:
                    try: _current_proc.kill()
                    except Exception: pass
                    _current_proc = None
                if _current_task and not _current_task.done():
                    _current_task.cancel()
                    _current_task = None
                await safe_send(ws, {"type": "cancelled"})

            elif msg_type == "chat":
                # Cancel any running task before starting a new one
                if _current_task and not _current_task.done():
                    _current_task.cancel()
                _current_task = asyncio.create_task(handle_chat(ws, data))

    except Exception:
        pass

# ── Entry point ──────────────────────────────────────────────────────────────

async def main():
    http_thread = threading.Thread(target=http_server.serve_forever, daemon=True)
    http_thread.start()
    print(f"HTTP  → http://127.0.0.1:{HTTP_PORT}  (ping / stop)")

    async with ws_serve(ws_handler, "127.0.0.1", WS_PORT, ping_interval=None):
        print(f"WS    → ws://127.0.0.1:{WS_PORT}  (chat)")
        await asyncio.Future()

if __name__ == "__main__":
    asyncio.run(main())
