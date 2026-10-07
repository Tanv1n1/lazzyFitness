#!/usr/bin/env python3
"""Lazy Fitness backend: user sync API for the Android app, admin API and admin website.

Runs on the Python standard library only (SQLite storage). The optional AI plan
endpoint uses the official `anthropic` package if it is installed and
ANTHROPIC_API_KEY (or an `ant auth login` profile) is available.

  python3 server.py                      # http://0.0.0.0:8080
  ADMIN_TOKEN=choose-a-long-secret python3 server.py
"""
import json
import os
import re
import secrets
import sqlite3
import sys
import threading
import time
import uuid
from datetime import date, datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

HERE = Path(__file__).resolve().parent
DB_PATH = os.environ.get("FITCOACH_DB", str(HERE / "fitcoach.db"))
PORT = int(os.environ.get("PORT", "8080"))
HOST = os.environ.get("HOST", "0.0.0.0")   # use 127.0.0.1 behind Caddy or a tunnel
PASS_STEPS = 6          # a day counts toward the streak at 6 of 9 steps
TOTAL_STEPS = 9
STEP_IDS = ["water", "workout", "breakfast", "mid", "lunch", "eve", "dinner", "prebed", "sleep"]
AI_MODEL = os.environ.get("FITCOACH_MODEL", "claude-opus-5-5")
AI_ENABLED = os.environ.get("AI_ENABLED", "1") != "0"
AI_DAILY_LIMIT = int(os.environ.get("AI_DAILY_LIMIT", "3"))     # AI plans per user per day (cost cap)
INVITE_CODE = os.environ.get("INVITE_CODE", "")                  # empty = open registration
MAX_USERS = int(os.environ.get("MAX_USERS", "25"))              # users allowed
_admin_fails = []                                                # timestamps of recent bad admin tokens

_local = threading.local()


def db():
    if not hasattr(_local, "c"):
        c = sqlite3.connect(DB_PATH)
        c.row_factory = sqlite3.Row
        c.execute("PRAGMA journal_mode=WAL")
        _local.c = c
    return _local.c


def init_db():
    c = db()
    c.executescript(
        """
        CREATE TABLE IF NOT EXISTS users(
          id TEXT PRIMARY KEY, secret TEXT NOT NULL, username TEXT NOT NULL,
          age INTEGER, sex TEXT, height_cm REAL, weight_kg REAL,
          goal TEXT, diet TEXT, place TEXT, wtime TEXT, wake TEXT, sleep TEXT,
          conds TEXT DEFAULT '[]',
          kcal INTEGER, protein INTEGER, plan_source TEXT DEFAULT 'local',
          created_at TEXT NOT NULL, last_seen TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS daily(
          user_id TEXT NOT NULL, day TEXT NOT NULL, done TEXT NOT NULL DEFAULT '[]',
          done_count INTEGER NOT NULL DEFAULT 0, updated_at TEXT NOT NULL,
          PRIMARY KEY(user_id, day));
        CREATE TABLE IF NOT EXISTS ai_use(user_id TEXT NOT NULL, day TEXT NOT NULL, n INTEGER NOT NULL DEFAULT 0,
          PRIMARY KEY(user_id, day));
        """
    )
    c.commit()


def now_iso():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def today():
    return date.today().isoformat()


def admin_token():
    t = os.environ.get("ADMIN_TOKEN")
    if t:
        return t
    f = HERE / ".admin_token"
    if f.exists():
        return f.read_text().strip()
    t = secrets.token_urlsafe(24)
    f.write_text(t)
    try:
        os.chmod(f, 0o600)
    except OSError:
        pass
    return t


ADMIN_TOKEN = None


# ---------- validation ----------
def num(v, lo, hi, name):
    try:
        x = float(v)
    except (TypeError, ValueError):
        raise ValueError(f"{name} must be a number")
    if not lo <= x <= hi:
        raise ValueError(f"{name} must be between {lo} and {hi}")
    return x


def pick(p, k, opts):
    if p.get(k) not in opts:
        raise ValueError(f"{k} invalid")
    return p[k]


def clean_profile(p):
    username = str(p.get("username", "")).strip()
    if not 2 <= len(username) <= 24:
        raise ValueError("username must be 2 to 24 characters")
    if not re.match(r"^[\w .\-]+$", username):
        raise ValueError("username has unsupported characters")
    hhmm = re.compile(r"^([01]\d|2[0-3]):[0-5]\d$")
    for k in ("wake", "sleep"):
        if not hhmm.match(str(p.get(k, ""))):
            raise ValueError(f"{k} must be HH:MM")
    conds = [c for c in p.get("conds", []) if c in
             ("diabetes", "thyroid", "pcos", "bp", "chol", "acidity", "lactose", "gluten", "kidney", "other")]
    return {
        "username": username,
        "age": int(num(p.get("age"), 14, 90, "age")),
        "sex": pick(p, "sex", ("male", "female", "other")),
        "height_cm": num(p.get("height_cm"), 120, 230, "height"),
        "weight_kg": num(p.get("weight_kg"), 30, 250, "weight"),
        "goal": pick(p, "goal", ("lose", "gain", "maintain")),
        "diet": pick(p, "diet", ("veg", "egg", "nonveg")),
        "place": pick(p, "place", ("home", "gym")),
        "wtime": pick(p, "wtime", ("morning", "evening")),
        "wake": p["wake"], "sleep": p["sleep"],
        "conds": json.dumps(conds),
        "kcal": int(num(p.get("kcal", 0), 0, 6000, "kcal")),
        "protein": int(num(p.get("protein", 0), 0, 400, "protein")),
        "plan_source": "ai" if p.get("plan_source") == "ai" else "local",
    }


# ---------- AI plan (optional) ----------
def ai_plan(profile):
    try:
        import anthropic
    except ImportError:
        return None, "anthropic package not installed (pip install anthropic)"
    try:
        client = anthropic.Anthropic()
    except Exception as e:  # missing credentials
        return None, f"no Anthropic credentials: {e}"
    slots = {"breakfast": 3, "mid": 3, "lunch": 3, "eve": 3, "dinner": 3, "prebed": 2}
    prompt = (
        "You are a nutrition coach for people living in Pune, India. Build meal options for this person.\n"
        f"Profile: {json.dumps(profile)}\n"
        "Rules:\n"
        "- Use foods easily available in Pune and Maharashtrian home cooking (bhakri, varan, usal, thalipeeth, poha, "
        "misal at home, sol kadhi, taak) plus common Indian staples.\n"
        "- Follow diet strictly: veg = no egg, no meat, no fish; egg = veg + eggs; nonveg = all.\n"
        "- Adapt to every medical condition listed in conds (diabetes, thyroid, pcos, bp, chol, acidity, lactose, "
        "gluten, kidney) and to anything in 'other'. For kidney, keep protein moderate and say to confirm with a nephrologist.\n"
        "- No supplements, no drug advice, no medical claims.\n"
        "- Every option has: name (short), items (3 to 5 strings with quantities), skip (2 to 3 Pune-specific things "
        "to avoid at this meal for this person).\n"
        f"- Option counts: {json.dumps(slots)}.\n"
        "Reply with only JSON: {\"summary\": string (max 220 chars), \"watchlist\": [6 strings], "
        "\"meals\": {\"breakfast\": [...], \"mid\": [...], \"lunch\": [...], \"eve\": [...], \"dinner\": [...], \"prebed\": [...]}}"
    )
    try:
        msg = client.messages.create(
            model=AI_MODEL, max_tokens=8000,
            messages=[{"role": "user", "content": prompt}],
        )
    except Exception as e:
        return None, f"AI request failed: {type(e).__name__}"
    if getattr(msg, "stop_reason", "") == "refusal":
        return None, "AI declined the request"
    text = "".join(b.text for b in msg.content if getattr(b, "type", "") == "text")
    m = re.search(r"\{.*\}", text, re.S)
    if not m:
        return None, "AI reply had no JSON"
    try:
        data = json.loads(m.group(0))
    except json.JSONDecodeError:
        return None, "AI reply was not valid JSON"
    meals = data.get("meals", {})
    out = {}
    for k in slots:
        opts = []
        for o in meals.get(k, []):
            if isinstance(o, dict) and isinstance(o.get("name"), str) and isinstance(o.get("items"), list):
                opts.append({
                    "name": o["name"][:80],
                    "items": [str(i)[:90] for i in o["items"]][:6],
                    "skip": [str(i)[:90] for i in o.get("skip", [])][:3],
                })
        if opts:
            out[k] = opts
    if not out:
        return None, "AI reply had no usable meals"
    return {
        "summary": str(data.get("summary", ""))[:240],
        "watchlist": [str(w)[:90] for w in data.get("watchlist", [])][:8],
        "meals": out,
    }, None


# ---------- metrics ----------
def streak_for(days_done, today_s):
    """days_done: dict day -> done_count. Consecutive passing days ending today or yesterday."""
    d = date.fromisoformat(today_s)
    if days_done.get(d.isoformat(), 0) < PASS_STEPS:
        d -= timedelta(days=1)
    n = 0
    while days_done.get(d.isoformat(), 0) >= PASS_STEPS:
        n += 1
        d -= timedelta(days=1)
    return n


def user_row(u, logs, today_s):
    days = {r["day"]: r["done_count"] for r in logs}
    last7 = [(date.fromisoformat(today_s) - timedelta(days=i)).isoformat() for i in range(7)]
    comp7 = round(100 * sum(days.get(d, 0) for d in last7) / (7 * TOTAL_STEPS))
    last_log = max(days) if days else None
    return {
        "id": u["id"], "username": u["username"], "age": u["age"], "sex": u["sex"],
        "height_cm": u["height_cm"], "weight_kg": u["weight_kg"], "goal": u["goal"], "diet": u["diet"],
        "place": u["place"], "wtime": u["wtime"], "wake": u["wake"], "sleep": u["sleep"],
        "conds": json.loads(u["conds"] or "[]"), "kcal": u["kcal"], "protein": u["protein"],
        "plan_source": u["plan_source"], "created_at": u["created_at"], "last_seen": u["last_seen"],
        "today_done": days.get(today_s, 0), "comp7": comp7,
        "streak": streak_for(days, today_s), "last_log": last_log,
    }


def all_users(c):
    today_s = today()
    since = (date.today() - timedelta(days=60)).isoformat()
    logs = {}
    for r in c.execute("SELECT user_id, day, done_count FROM daily WHERE day >= ?", (since,)):
        logs.setdefault(r["user_id"], []).append(r)
    return [user_row(u, logs.get(u["id"], []), today_s) for u in c.execute("SELECT * FROM users")]


def summary(c):
    users = all_users(c)
    today_s = today()
    t = date.fromisoformat(today_s)
    n = len(users)
    week_ago = (t - timedelta(days=6)).isoformat()

    def count(key):
        out = {}
        for u in users:
            out[u[key]] = out.get(u[key], 0) + 1
        return out

    conds = {}
    for u in users:
        for k in u["conds"]:
            conds[k] = conds.get(k, 0) + 1

    trend = []
    for i in range(13, -1, -1):
        d = (t - timedelta(days=i)).isoformat()
        rows = c.execute("SELECT done_count FROM daily WHERE day=? AND user_id IN (SELECT id FROM users)", (d,)).fetchall()
        active = len(rows)
        avg = round(100 * sum(r["done_count"] for r in rows) / (active * TOTAL_STEPS)) if active else 0
        trend.append({"day": d, "active": active, "avg_completion": avg})

    step_hits = {s: 0 for s in STEP_IDS}
    step_total = 0
    for r in c.execute("SELECT done FROM daily WHERE day >= ?", (week_ago,)):
        step_total += 1
        for s in json.loads(r["done"]):
            if s in step_hits:
                step_hits[s] += 1
    adherence = [{"step": s, "pct": round(100 * step_hits[s] / step_total) if step_total else 0} for s in STEP_IDS]

    at_risk = [u for u in users if (u["last_log"] is None and (t - date.fromisoformat(u["created_at"][:10])).days >= 2)
               or (u["last_log"] and (t - date.fromisoformat(u["last_log"])).days >= 3)]
    return {
        "generated_at": now_iso(), "today": today_s,
        "total_users": n,
        "new_7d": sum(1 for u in users if u["created_at"][:10] >= week_ago),
        "dau": sum(1 for u in users if u["last_log"] == today_s),
        "wau": sum(1 for u in users if u["last_log"] and u["last_log"] >= week_ago),
        "avg_comp7": round(sum(u["comp7"] for u in users) / n) if n else 0,
        "avg_streak": round(sum(u["streak"] for u in users) / n, 1) if n else 0,
        "ai_plan_pct": round(100 * sum(1 for u in users if u["plan_source"] == "ai") / n) if n else 0,
        "goals": count("goal"), "diets": count("diet"), "places": count("place"), "conditions": conds,
        "trend": trend, "adherence": adherence, "at_risk": len(at_risk),
    }


# ---------- HTTP ----------
class Handler(BaseHTTPRequestHandler):
    server_version = "LazyFitness/1.0"

    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (self.log_date_time_string(), fmt % args))

    def _send(self, code, body, ctype="application/json"):
        data = body if isinstance(body, (bytes, bytearray)) else json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype + ("; charset=utf-8" if ctype.startswith("text") else ""))
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        if ctype.startswith("text/html"):
            self.send_header("Content-Security-Policy",
                             "default-src 'self'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; "
                             "font-src https://fonts.gstatic.com; script-src 'self' 'unsafe-inline'; img-src 'self' data:")
        self.end_headers()
        self.wfile.write(data)

    def _json(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n > 100_000:
            raise ValueError("body too large")
        return json.loads(self.rfile.read(n) or b"{}")

    def _admin_ok(self):
        now = time.time()
        _admin_fails[:] = [t for t in _admin_fails if now - t < 300]
        if len(_admin_fails) >= 20:          # too many bad tokens in 5 minutes: lock out for a while
            return False
        h = self.headers.get("Authorization", "")
        ok = h.startswith("Bearer ") and secrets.compare_digest(h[7:], ADMIN_TOKEN)
        if not ok and h:
            _admin_fails.append(now)
        return ok

    def _user(self):
        uid, key = self.headers.get("X-User-Id", ""), self.headers.get("X-User-Key", "")
        u = db().execute("SELECT * FROM users WHERE id=?", (uid,)).fetchone()
        if u and secrets.compare_digest(u["secret"], key):
            return u
        return None

    def do_GET(self):
        path = urlparse(self.path).path
        try:
            if path in ("/", "/admin", "/admin/"):
                return self._send(200, (HERE / "admin" / "index.html").read_bytes(), "text/html")
            if path == "/api/health":
                return self._send(200, {"ok": True})
            if path.startswith("/admin/api/"):
                if not self._admin_ok():
                    return self._send(401, {"error": "unauthorized"})
                c = db()
                if path == "/admin/api/summary":
                    return self._send(200, summary(c))
                if path == "/admin/api/users":
                    return self._send(200, {"users": all_users(c)})
                m = re.match(r"^/admin/api/users/([\w-]+)$", path)
                if m:
                    u = next((x for x in all_users(c) if x["id"] == m.group(1)), None)
                    if not u:
                        return self._send(404, {"error": "not found"})
                    since = (date.today() - timedelta(days=29)).isoformat()
                    logs = [{"day": r["day"], "done": json.loads(r["done"]), "done_count": r["done_count"]}
                            for r in c.execute("SELECT * FROM daily WHERE user_id=? AND day>=? ORDER BY day",
                                               (u["id"], since))]
                    return self._send(200, {"user": u, "logs": logs})
            return self._send(404, {"error": "not found"})
        except Exception as e:  # never leak internals
            sys.stderr.write(f"GET error: {e!r}\n")
            return self._send(500, {"error": "server error"})

    def do_DELETE(self):
        path = urlparse(self.path).path
        if path == "/api/me":  # a user deleting their own account and data
            u = self._user()
            if not u:
                return self._send(401, {"error": "unauthorized"})
            c = db()
            c.execute("DELETE FROM daily WHERE user_id=?", (u["id"],))
            c.execute("DELETE FROM ai_use WHERE user_id=?", (u["id"],))
            c.execute("DELETE FROM users WHERE id=?", (u["id"],))
            c.commit()
            return self._send(200, {"ok": True})
        m = re.match(r"^/admin/api/users/([\w-]+)$", path)
        if m and self._admin_ok():
            c = db()
            c.execute("DELETE FROM daily WHERE user_id=?", (m.group(1),))
            c.execute("DELETE FROM users WHERE id=?", (m.group(1),))
            c.commit()
            return self._send(200, {"ok": True})
        return self._send(401 if m else 404, {"error": "unauthorized" if m else "not found"})

    def do_POST(self):
        path = urlparse(self.path).path
        try:
            c = db()
            body = self._json()
            if path == "/api/register":
                if body.get("consent") is not True:
                    return self._send(400, {"error": "consent required"})
                if INVITE_CODE and not secrets.compare_digest(str(body.get("invite", "")), INVITE_CODE):
                    return self._send(403, {"error": "invite code not accepted"})
                if c.execute("SELECT COUNT(*) FROM users").fetchone()[0] >= MAX_USERS:
                    return self._send(403, {"error": "this test is full"})
                p = clean_profile(body)
                uid, secret = str(uuid.uuid4()), secrets.token_urlsafe(24)
                ts = now_iso()
                c.execute(
                    "INSERT INTO users(id,secret,username,age,sex,height_cm,weight_kg,goal,diet,place,wtime,wake,sleep,"
                    "conds,kcal,protein,plan_source,created_at,last_seen) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    (uid, secret, p["username"], p["age"], p["sex"], p["height_cm"], p["weight_kg"], p["goal"],
                     p["diet"], p["place"], p["wtime"], p["wake"], p["sleep"], p["conds"], p["kcal"], p["protein"],
                     p["plan_source"], ts, ts))
                c.commit()
                return self._send(200, {"user_id": uid, "user_key": secret})
            if path == "/api/profile":
                u = self._user()
                if not u:
                    return self._send(401, {"error": "unauthorized"})
                p = clean_profile(body)
                c.execute(
                    "UPDATE users SET username=?,age=?,sex=?,height_cm=?,weight_kg=?,goal=?,diet=?,place=?,wtime=?,"
                    "wake=?,sleep=?,conds=?,kcal=?,protein=?,plan_source=?,last_seen=? WHERE id=?",
                    (p["username"], p["age"], p["sex"], p["height_cm"], p["weight_kg"], p["goal"], p["diet"],
                     p["place"], p["wtime"], p["wake"], p["sleep"], p["conds"], p["kcal"], p["protein"],
                     p["plan_source"], now_iso(), u["id"]))
                c.commit()
                return self._send(200, {"ok": True})
            if path == "/api/sync":
                u = self._user()
                if not u:
                    return self._send(401, {"error": "unauthorized"})
                day = str(body.get("day", ""))
                date.fromisoformat(day)
                done = [s for s in body.get("done", []) if s in STEP_IDS]
                done = sorted(set(done), key=STEP_IDS.index)
                c.execute(
                    "INSERT INTO daily(user_id,day,done,done_count,updated_at) VALUES(?,?,?,?,?) "
                    "ON CONFLICT(user_id,day) DO UPDATE SET done=excluded.done, done_count=excluded.done_count, "
                    "updated_at=excluded.updated_at",
                    (u["id"], day, json.dumps(done), len(done), now_iso()))
                c.execute("UPDATE users SET last_seen=? WHERE id=?", (now_iso(), u["id"]))
                c.commit()
                return self._send(200, {"ok": True})
            if path == "/api/plan":
                u = self._user()
                if not u:
                    return self._send(401, {"error": "unauthorized"})
                if not AI_ENABLED:
                    return self._send(503, {"error": "ai_unavailable", "detail": "AI is switched off on this server"})
                used = c.execute("SELECT n FROM ai_use WHERE user_id=? AND day=?", (u["id"], today())).fetchone()
                if used and used["n"] >= AI_DAILY_LIMIT:
                    return self._send(429, {"error": "ai_limit", "detail": "daily AI plan limit reached"})
                c.execute("INSERT INTO ai_use(user_id,day,n) VALUES(?,?,1) ON CONFLICT(user_id,day) DO UPDATE SET n=n+1",
                          (u["id"], today()))
                c.commit()
                plan, err = ai_plan(body.get("profile", {}))
                if plan is None:
                    return self._send(503, {"error": "ai_unavailable", "detail": err})
                return self._send(200, plan)
            return self._send(404, {"error": "not found"})
        except ValueError as e:
            return self._send(400, {"error": str(e)})
        except sqlite3.Error as e:
            sys.stderr.write(f"db error: {e!r}\n")
            return self._send(500, {"error": "server error"})
        except Exception as e:
            sys.stderr.write(f"POST error: {e!r}\n")
            return self._send(500, {"error": "server error"})


def main():
    global ADMIN_TOKEN
    init_db()
    ADMIN_TOKEN = admin_token()
    print(f"Lazy Fitness backend on http://{HOST}:{PORT}")
    print(f"Admin site: http://localhost:{PORT}/admin")
    print(f"Invite code: {'required' if INVITE_CODE else 'NOT SET (anyone with the URL can register)'}  |  "
          f"max users: {MAX_USERS}  |  AI plans: {'on, ' + str(AI_DAILY_LIMIT) + '/user/day' if AI_ENABLED else 'off'}")
    if not os.environ.get("ADMIN_TOKEN"):
        print(f"Admin token is stored in {HERE / '.admin_token'}")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
