# Lazy Fitness

Pune-focused diet and workout app.

| Part | Folder | What it is |
|---|---|---|
| Android app | `android/` | Kotlin + Jetpack Compose. Onboarding, wake-up timeline, ticks, home-screen widget, meal reminders. The plan engine (`PlanEngine.kt`) lives in the app, so it works fully offline |
| Backend + admin site | `backend/` | Python standard library + SQLite. User sync API, optional Claude plan endpoint, admin dashboard at `/admin` |
| Hosting | `deploy/gcp/`, `deploy/railway/` | Setup script for a Google VM, and steps for Railway. `backend/Dockerfile` is what Railway builds |

## Status

| Piece | State |
|---|---|
| Backend and admin site | Tested: `python3 backend/selftest.py` runs 5 users at once (16 checks). Dashboard checked in a browser |
| Claude plan endpoint (`/api/plan`) | Written, not tested against the live API (no key was available) |
| Android app | Builds from the command line (Gradle 8.13, AGP 8.13.2) after the cleanup. **Not yet run on a phone or emulator**: onboarding, timeline, reminders and the widget are untested at runtime |
| `deploy/gcp/setup.sh` | Syntax-checked only. Not run on a real VM |

## Run the backend

```bash
cd backend
./run_test.sh          # invite code, free Cloudflare tunnel if installed, keeps the Mac awake
python3 server.py      # or plain: http://localhost:8080/admin
```

The admin token comes from `ADMIN_TOKEN`, or is generated once into `backend/.admin_token`.

Optional Claude-built plans (the key stays on the server, never in the app):

```bash
pip install anthropic
export ANTHROPIC_API_KEY=...       # or run `ant auth login`
```

Without it the app uses its offline planner. Limits for a small test: `INVITE_CODE`, `MAX_USERS`
(10 in `run_test.sh`), `AI_DAILY_LIMIT` (3 plans per user per day), `AI_ENABLED=0`, admin lockout after 20 wrong tokens.
A Claude plan is one API call, roughly a few cents (an estimate, not measured). `FITCOACH_MODEL=claude-haiku-4-5` is cheaper.

## Build the Android app

1. Open `android/` in Android Studio. Set **Gradle JDK** to the bundled JetBrains Runtime 21 (Gradle cannot run on JDK 25).
2. Run on a phone, or Build, Build Bundle(s) / APK(s), Build APK(s) and send `app-debug.apk` to testers (they allow "install unknown apps" once).
3. Testers enter the server address and invite code on the last onboarding step. Phones need an HTTPS address: the Cloudflare tunnel from `run_test.sh`, or the permanent one from `deploy/gcp/`. The default `API_BASE` (`http://10.0.2.2:8080`) only reaches your Mac from an emulator.

## How it works

- **Timeline** starts at wake time with 400 ml water. Workout, meals and sleep are spaced across the person's own wake and sleep times. Each meal lists foods to eat and to skip.
- **Reminders**: one alarm is armed for the next undone step. It shows the meal, what to eat, what to skip and a Done button, then arms the next one. Reboot, app update and clock changes re-arm it.
- **Widget**: progress, streak, next step and a Done button.
- **Sync**: only after the user ticks the consent box. Profile, targets, medical flags and daily ticks go to the server; free-text notes stay on the phone. Users delete their data from the Me tab (`DELETE /api/me`).
- **Admin site**: users, daily active, 7-day completion, streaks, who has gone quiet, which steps get skipped, goal / diet / medical-flag mix, per-user 30-day history. Admin can delete a user.
- **Medical conditions** filter meals and add notes. Kidney issues are marked as a draft to confirm with a nephrologist. General guidance, not medical advice.

## Before real users

- HTTPS in front of the backend (`deploy/gcp/` does this). The admin page shows health data, so keep the token secret and write a privacy notice.
- Reminders use inexact alarms (`setAndAllowWhileIdle`), so they can arrive a few minutes late. Exact alarms need extra permission and Play Store justification.
- No server-sent push (FCM). Reminders are scheduled on the device.
- App signing and a release build config.
- Free hosting tiers that sleep or wipe files (Render, Koyeb) lose the SQLite file. Use the VM in `deploy/gcp/` or move to a hosted database.
