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
| Backend and admin site | Tested: `python3 backend/selftest.py` runs 5 users at once (35 checks, including skip and swap sync, water, the AI plan path and meal photo reading against a fake provider). Dashboard checked in a browser |
| Claude plan endpoint (`/api/plan`) | Written, not tested against the live API (no key was available) |
| Android app | Builds from the command line (Gradle 8.13, AGP 8.13.2). **Not yet run on a phone or emulator**: onboarding, timeline, skip and swap dialogs, photo reading, reminders and the widget are untested at runtime |
| `deploy/gcp/setup.sh` | Syntax-checked only. Not run on a real VM |

## Run the backend

```bash
cd backend
./run_test.sh          # invite code, free Cloudflare tunnel if installed, keeps the Mac awake
python3 server.py      # or plain: http://localhost:8080/admin
```

The admin token comes from `ADMIN_TOKEN`, or is generated once into `backend/.admin_token`.

Optional AI-written plans. The key stays on the server, never in the app. Only age, sex, height, weight, goal, diet,
medical flags, targets and a short free-text note go to the provider. The username never does. Pick one provider:

```bash
# Anthropic
pip install anthropic
export ANTHROPIC_API_KEY=...       # or run `ant auth login`

# or any OpenAI-compatible provider (chat/completions with a bearer key)
export AI_BASE_URL=https://api.example.com/v1     # must be https
export AI_API_KEY=...
export AI_MODEL=the-model-id-as-that-provider-names-it
```

Without either, the app uses its offline planner. The consent text in the app names a third-party AI service, so only
enable a provider you are willing to name to your users.

Meal photo reading needs a model that accepts images, so `AI_MODEL` must be one that can see them.

Limits for a small test: `INVITE_CODE`, `MAX_USERS` (10 in `run_test.sh`), `AI_DAILY_LIMIT` (3 plans per user per day),
`AI_PHOTO_DAILY_LIMIT` (10 photo reads per user per day), `AI_ENABLED=0`, admin lockout after 20 wrong tokens.
Cost depends on the provider. With Anthropic, a plan or a photo read is one API call.

Reminders are scheduled on the phone, so they work without a server or Firebase. Server-sent push (for example a coach
message to a user) would need Firebase Cloud Messaging and is not included.

## Build the Android app

1. Open `android/` in Android Studio. Set **Gradle JDK** to the bundled JetBrains Runtime 21 (Gradle cannot run on JDK 25).
2. Run on a phone, or Build, Build Bundle(s) / APK(s), Build APK(s) and send `app-debug.apk` to testers (they allow "install unknown apps" once).
3. Testers enter the server address and invite code on the last onboarding step. Phones need an HTTPS address: the Cloudflare tunnel from `run_test.sh`, or the permanent one from `deploy/gcp/`. The default `API_BASE` (`http://10.0.2.2:8080`) only reaches your Mac from an emulator.

## How it works

- **Timeline** starts at wake time with 400 ml water. Workout, meals and sleep are spaced across the person's own wake and sleep times. Each meal lists foods to eat and to skip.
- **Reminders**: one alarm is armed for the next undone step. It shows the meal, what to eat, what to skip and a Done button, then arms the next one. Reboot, app update and clock changes re-arm it.
- **Skip, swap, ask**: on any step you can mark it skipped (with a reason), say what you had instead, or write a change request for your coach. For meals you can also choose or take a photo, and an AI vision model lists the food and estimates calories. You check and edit the result before saving. The photo is not stored, on the phone or the server.
- **Water**: a glass counter on the Today screen and four water reminders between breakfast and dinner, each with a "Drank a glass" button. Reminders stop once the day's target is reached.
- **Last 7 days** card on the Me tab: check-in days, average completion, skips, swaps and the step skipped most.
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
