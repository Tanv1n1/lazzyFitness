#!/usr/bin/env python3
"""Starts a throwaway server and checks it with 5 users at the same time. Run: python3 selftest.py"""
import base64
import json
import os
import random
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
from http.server import BaseHTTPRequestHandler, HTTPServer
import urllib.request
from datetime import date, datetime, timedelta, timezone

PORT = 8123
IST = timezone(timedelta(hours=5, minutes=30))   # the server counts days in India time


def today():
    return datetime.now(IST).date()


FAKE_PORT = 8124
BASE = f"http://127.0.0.1:{PORT}"
ADMIN = "selftest-admin-token"
INVITE = "pune-test-5"
STEPS = ["water", "workout", "breakfast", "mid", "lunch", "eve", "dinner", "prebed", "sleep"]


def req(method, path, body=None, headers=None):
    h = {"Content-Type": "application/json", **(headers or {})}
    r = urllib.request.Request(BASE + path, method=method, headers=h,
                               data=json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(r, timeout=10) as resp:
            return resp.status, json.loads(resp.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def profile(i, **kw):
    p = {"consent": True, "invite": INVITE, "username": f"Tester{i}", "age": 22 + i, "sex": "male" if i % 2 else "female",
         "height_cm": 165 + i, "weight_kg": 60 + i, "goal": ["lose", "gain", "maintain"][i % 3],
         "diet": ["veg", "egg", "nonveg"][i % 3], "place": "home", "wtime": "evening", "wake": "06:30",
         "sleep": "23:00", "conds": [["diabetes"], [], ["thyroid", "acidity"], [], ["bp"]][i % 5], "kcal": 1900, "protein": 100}
    p.update(kw)
    return p


FAKE_MEAL = {"name": "Test meal", "items": ["1 bhakri", "1 bowl dal", "salad"], "skip": ["vada pav", "sweets"]}
FAKE_REPLY = {"summary": "Fake plan", "watchlist": ["a", "b"],
              "meals": {k: [FAKE_MEAL] for k in ("breakfast", "mid", "lunch", "eve", "dinner", "prebed")}}
FAKE_MEAL_READING = {"items": ["2 bhakri, ~220 kcal", "dal, 1 bowl, ~150 kcal"], "kcal": 370,
                     "confidence": "low", "note": "Portions are a guess."}
fake_seen = []   # (path, authorization header, body text) of every call the server makes to the fake AI provider


class FakeProvider(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length") or 0)).decode()
        fake_seen.append((self.path, self.headers.get("Authorization"), body))
        reply = FAKE_MEAL_READING if '"image_url"' in body else FAKE_REPLY
        text = "Here you go:\n" + json.dumps(reply)
        if json.loads(body).get("model") == "bad-model":      # the main model answers with no JSON, so the fallback must step in
            text = "Sorry, I cannot do that."
        out = json.dumps({"choices": [{"message": {"content": text}}]}).encode()
        self.send_response(429 if "force busy" in body else 200)    # lets a test play a provider that says "slow down"
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    def log_message(self, *a):
        pass


results, lock = {}, threading.Lock()
failures = []


def check(name, cond, detail=""):
    print(("PASS  " if cond else "FAIL  ") + name + (f"  ({detail})" if detail and not cond else ""))
    if not cond:
        failures.append(name)


def user_session(i):
    code, r = req("POST", "/api/register", profile(i))
    if code != 200:
        with lock:
            results[i] = ("register failed", code, r)
        return
    hdr = {"X-User-Id": r["user_id"], "X-User-Key": r["user_key"]}
    sent = {}
    for d in range(7):
        day = (today() - timedelta(days=d)).isoformat()
        done = random.sample(STEPS, random.randint(3, 9))
        c, _ = req("POST", "/api/sync", {"day": day, "done": done}, hdr)
        sent[day] = len(set(done))
        if c != 200:
            with lock:
                results[i] = ("sync failed", c, None)
            return
    c, _ = req("POST", "/api/profile", profile(i, weight_kg=61 + i), hdr)
    with lock:
        results[i] = ("ok", r["user_id"], sent, c, r["user_key"])


def main():
    tmp = tempfile.mkdtemp()
    env = {**os.environ, "FITCOACH_DB": os.path.join(tmp, "t.db"), "ADMIN_TOKEN": ADMIN, "PORT": str(PORT),
           "INVITE_CODE": INVITE, "MAX_USERS": "5", "AI_DAILY_LIMIT": "2", "AI_PHOTO_DAILY_LIMIT": "2",
           "AI_BASE_URL": f"http://127.0.0.1:{FAKE_PORT}/v1", "AI_API_KEY": "test-key", "AI_MODEL": "bad-model",
           "AI_FALLBACK_MODELS": "fake-model", "AI_VISION_MODEL": "fake-model"}
    fake = HTTPServer(("127.0.0.1", FAKE_PORT), FakeProvider)
    threading.Thread(target=fake.serve_forever, daemon=True).start()
    srv = subprocess.Popen([sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), "server.py")],
                           env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        for _ in range(40):
            try:
                if req("GET", "/api/health")[0] == 200:
                    break
            except Exception:
                time.sleep(0.15)
        check("server starts", True)
        check("wrong invite code is refused", req("POST", "/api/register", profile(0, invite="nope"))[0] == 403)
        check("missing consent is refused", req("POST", "/api/register", profile(0, consent=False))[0] == 400)
        check("bad profile is refused", req("POST", "/api/register", profile(0, age=3))[0] == 400)

        threads = [threading.Thread(target=user_session, args=(i,)) for i in range(5)]
        t0 = time.time()
        [t.start() for t in threads]
        [t.join() for t in threads]
        check("5 users register and sync at the same time", all(v[0] == "ok" for v in results.values()) and len(results) == 5,
              str(results))
        print(f"      ({time.time() - t0:.2f} s for 5 users x 9 requests)")

        check("6th user is turned away (test is full)", req("POST", "/api/register", profile(9))[0] == 403)

        A = {"Authorization": "Bearer " + ADMIN}
        code, summ = req("GET", "/admin/api/summary", headers=A)
        check("admin sees 5 users", code == 200 and summ["total_users"] == 5, str(summ.get("total_users")))
        check("all 5 logged today", summ.get("dau") == 5, str(summ.get("dau")))
        code, ul = req("GET", "/admin/api/users", headers=A)
        names = sorted(u["username"] for u in ul["users"])
        check("admin list has every tester", names == [f"Tester{i}" for i in range(5)], str(names))
        ok = True
        for i, v in results.items():
            uid, sent = v[1], v[2]
            _, d = req("GET", f"/admin/api/users/{uid}", headers=A)
            got = {l["day"]: l["done_count"] for l in d["logs"]}
            ok &= all(got.get(day) == n for day, n in sent.items())
            ok &= d["user"]["weight_kg"] == 61 + i
        check("each user's data is stored correctly and kept separate", ok)

        uid0 = results[0][1]
        some_user = {"X-User-Id": uid0, "X-User-Key": "wrong"}
        check("wrong user key is refused", req("POST", "/api/sync", {"day": today().isoformat(), "done": []}, some_user)[0] == 401)
        check("admin API refuses no token", req("GET", "/admin/api/users")[0] == 401)

        c, _ = req("POST", "/api/plan", {"profile": {}}, {"X-User-Id": uid0, "X-User-Key": "wrong"})
        check("plan endpoint needs a valid user", c == 401)

        # AI plan through an OpenAI-compatible provider (a fake one here)
        hdr0 = {"X-User-Id": results[0][1], "X-User-Key": results[0][4]}
        plan_body = {"profile": {**profile(0), "targets": {"kcal": 1900, "protein": 100}, "other": "peanut allergy"}}
        c, plan = req("POST", "/api/plan", plan_body, hdr0)
        check("AI plan comes back through the provider", c == 200 and set(plan.get("meals", {})) == set(FAKE_REPLY["meals"]), str(plan)[:120])
        path, auth, sent = fake_seen[0] if fake_seen else (None, None, "")
        check("provider call uses the bearer key and chat/completions", path == "/v1/chat/completions" and auth == "Bearer test-key", f"{path} {auth}")
        check("username never reaches the provider", "Tester0" not in sent)
        check("health data the plan needs does reach it", "diabetes" in sent and "peanut allergy" in sent)
        models_asked = [json.loads(b)["model"] for _, _, b in fake_seen]
        check("a failed main model falls back to the next one", models_asked == ["bad-model", "fake-model"], str(models_asked))
        calls_before = len(fake_seen)
        check("bad profile is refused before any provider call",
              req("POST", "/api/plan", {"profile": {"age": 3}}, hdr0)[0] == 400 and len(fake_seen) == calls_before)
        check("second plan allowed", req("POST", "/api/plan", plan_body, hdr0)[0] == 200)
        check("daily AI limit stops the third", req("POST", "/api/plan", plan_body, hdr0)[0] == 429)

        # skipped and replaced steps, change requests and water
        day = today().isoformat()
        entries = {"lunch": {"s": "replaced", "text": "Poha and chai", "kcal": 450, "note": "less oil please"},
                   "dinner": {"s": "skipped", "text": "Ate out"},
                   "bogus": {"s": "skipped"}, "mid": {"s": "hacked"}}
        c, _ = req("POST", "/api/sync", {"day": day, "done": ["lunch"], "entries": entries, "water": 5}, hdr0)
        _, d0 = req("GET", f"/admin/api/users/{results[0][1]}", headers=A)
        today_log = next((l for l in d0["logs"] if l["day"] == day), {})
        check("skipped and replaced steps are stored, junk is dropped",
              c == 200 and set(today_log.get("entries", {})) == {"lunch", "dinner"}, str(today_log.get("entries")))
        check("water glasses are stored", today_log.get("water") == 5)
        _, u0 = req("GET", "/admin/api/users", headers=A)
        row0 = next(u for u in u0["users"] if u["id"] == results[0][1])
        check("admin counts skips, swaps and requests",
              (row0["skipped7"], row0["replaced7"], row0["requests7"]) == (1, 1, 1),
              str({k: row0[k] for k in ("skipped7", "replaced7", "requests7")}))

        # meal photo through the (fake) AI provider
        jpeg = base64.b64encode(b"\xff\xd8\xff\xe0" + b"0" * 2000).decode()
        seen_before = len(fake_seen)
        c, meal = req("POST", "/api/meal-photo", {"image": jpeg}, hdr0)
        check("meal photo is read", c == 200 and meal.get("kcal") == 370 and len(meal.get("items", [])) == 2, str(meal))
        check("low confidence is flagged", meal.get("note", "").startswith("Low confidence"), str(meal))
        sent_photo = fake_seen[seen_before][2] if len(fake_seen) > seen_before else ""
        check("photo goes to the provider as an image, without the username",
              '"image_url"' in sent_photo and "Tester0" not in sent_photo)
        check("non-JPEG is refused", req("POST", "/api/meal-photo", {"image": base64.b64encode(b"hello world").decode()}, hdr0)[0] == 400)
        check("bad base64 is refused", req("POST", "/api/meal-photo", {"image": "***"}, hdr0)[0] == 400)
        check("photo endpoint needs a valid user",
              req("POST", "/api/meal-photo", {"image": jpeg}, {"X-User-Id": results[0][1], "X-User-Key": "x"})[0] == 401)
        check("second photo allowed", req("POST", "/api/meal-photo", {"image": jpeg}, hdr0)[0] == 200)
        check("daily photo limit stops the third", req("POST", "/api/meal-photo", {"image": jpeg}, hdr0)[0] == 429)

        # replies that are almost JSON
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        import server
        check("a trailing comma in the reply is tolerated", server.parse_json_reply('Sure: {"a": [1, 2,], "b": 1,} done')[0] == {"a": [1, 2], "b": 1})
        check("a plan missing meal slots is rejected", server.plan_from_reply({"meals": {"breakfast": [FAKE_MEAL]}})[0] is None)
        check("a complete plan is accepted", server.plan_from_reply(FAKE_REPLY)[0] is not None)
        check("a reply with no JSON is reported", server.parse_json_reply("no braces here")[0] is None)

        # a provider that is rate limited
        hdr1 = {"X-User-Id": results[1][1], "X-User-Key": results[1][4]}
        c, busy = req("POST", "/api/plan", {"profile": {**profile(1), "other": "force busy"}}, hdr1)
        check("provider rate limit becomes a busy reply", c == 429 and busy.get("error") == "ai_busy", f"{c} {busy}")

        # a user deletes their own data, which frees a slot
        uid4, key4 = results[4][1], results[4][4]
        check("user can delete their own account", req("DELETE", "/api/me", headers={"X-User-Id": uid4, "X-User-Key": key4})[0] == 200)
        check("deleted user is gone from admin", req("GET", f"/admin/api/users/{uid4}", headers=A)[0] == 404)
        check("freed slot lets a new tester in", req("POST", "/api/register", profile(5))[0] == 200)

        print("\n" + ("ALL CHECKS PASSED" if not failures else f"{len(failures)} CHECK(S) FAILED: {failures}"))
        return 1 if failures else 0
    finally:
        srv.terminate()
        fake.shutdown()


if __name__ == "__main__":
    sys.exit(main())
