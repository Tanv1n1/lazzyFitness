#!/usr/bin/env python3
"""Starts a throwaway server and checks it with 5 users at the same time. Run: python3 selftest.py"""
import json
import os
import random
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from datetime import date, timedelta

PORT = 8123
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
        day = (date.today() - timedelta(days=d)).isoformat()
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
           "INVITE_CODE": INVITE, "MAX_USERS": "5", "AI_DAILY_LIMIT": "2"}
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
        check("wrong user key is refused", req("POST", "/api/sync", {"day": date.today().isoformat(), "done": []}, some_user)[0] == 401)
        check("admin API refuses no token", req("GET", "/admin/api/users")[0] == 401)

        c, _ = req("POST", "/api/plan", {"profile": {}}, {"X-User-Id": uid0, "X-User-Key": "wrong"})
        check("plan endpoint needs a valid user", c == 401)

        # a user deletes their own data, which frees a slot
        uid4, key4 = results[4][1], results[4][4]
        check("user can delete their own account", req("DELETE", "/api/me", headers={"X-User-Id": uid4, "X-User-Key": key4})[0] == 200)
        check("deleted user is gone from admin", req("GET", f"/admin/api/users/{uid4}", headers=A)[0] == 404)
        check("freed slot lets a new tester in", req("POST", "/api/register", profile(5))[0] == 200)

        print("\n" + ("ALL CHECKS PASSED" if not failures else f"{len(failures)} CHECK(S) FAILED: {failures}"))
        return 1 if failures else 0
    finally:
        srv.terminate()


if __name__ == "__main__":
    sys.exit(main())
