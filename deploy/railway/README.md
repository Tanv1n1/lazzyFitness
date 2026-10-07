# Deploy the backend on Railway

Status: not yet deployed. The same `backend/server.py` and `backend/admin/index.html` were tested standalone with
Railway-style settings (`HOST=0.0.0.0`, `PORT` from the environment). The Dockerfile itself is untested.

Cost: Railway's Free plan is $1 of credit a month, and workloads stop when credit runs out. Check the Usage tab after a
day or two. See docs.railway.com/pricing/plans for current terms, including whether sign-up asks for a card.

## Steps

1. Push this repo to GitHub (a private repo).
2. Railway: New Project, Deploy from GitHub repo, pick the repo.
3. In the service **Settings, Source**, set **Root Directory** to `backend`. Railway then builds `backend/Dockerfile`.
4. **Variables**:

   | Name | Value |
   |---|---|
   | `ADMIN_TOKEN` | a long random string: `python3 -c "import secrets;print(secrets.token_urlsafe(24))"` |
   | `INVITE_CODE` | any short code you give testers |
   | `MAX_USERS` | `10` |
   | `FITCOACH_DB` | `/data/fitcoach.db` |
   | `ANTHROPIC_API_KEY` | optional, only for Claude-built plans (also needs `anthropic` in the image) |

5. Add a **Volume** and mount it at `/data`. Without it the database is wiped on every restart.
6. **Settings, Networking, Generate Domain**. Check `https://<domain>/api/health` returns `{"ok": true}`.
7. In the app, last onboarding step: server address `https://<domain>`, and your invite code.

Admin site: `https://<domain>/admin`, sign in with `ADMIN_TOKEN`.
