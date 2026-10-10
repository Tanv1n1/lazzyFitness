# Lazy Fitness

Pune-focused diet and workout app.

| Part | Folder | What it is |
|---|---|---|
| Android app | `android/` | Kotlin + Jetpack Compose. Onboarding, wake-up timeline, ticks, home-screen widget, meal reminders. The plan engine (`PlanEngine.kt`) lives in the app, so it works fully offline |
| iPhone / web app | `backend/web/` | One page served by the backend at `/app`. Same plan engine, timeline, skip / swap / change, photo reading and water counter as the Android app. Testers add it to the Home Screen. No widget and no background reminders |
| Backend + admin site | `backend/` | Python standard library + SQLite. User sync API, optional Claude plan endpoint, admin dashboard at `/admin` |
| Hosting | `deploy/gcp/`, `deploy/railway/` | Setup script for a Google VM, and steps for Railway. `backend/Dockerfile` is what Railway builds |

## Status

| Piece | State |
|---|---|
| Backend and admin site | Tested: `python3 backend/selftest.py` runs 5 users at once (72 checks, including skip and swap sync, water, the AI plan path, meal photo reading against a fake provider, the `/app` files, calorie pricing and the avoid list). Dashboard checked in a browser |
| Claude plan endpoint (`/api/plan`) | Written, not tested against the live API (no key was available) |
| iPhone / web app | The plan engine was compared with the real Kotlin `PlanEngine.kt` on 8 profiles x 9 dates (752 lines, no differences). Onboarding, timeline, skip, swap, change request, photo, delete and the offline path were clicked through in a browser at phone width against a fake AI provider. **Not yet tried on an iPhone** |
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
medical flags, targets and a short free-text note go to the provider. The username never does. Any OpenAI-compatible
provider works (`chat/completions` with a bearer key):

```bash
export AI_BASE_URL=https://api.example.com/v1     # must be https
export AI_API_KEY=...
export AI_MODEL=the-model-id-as-that-provider-names-it
```

Without these three, AI is off and the app uses its offline planner. The consent text in the app names a third-party AI
service, so only enable a provider you are willing to name to your users. Photo reading needs a model that accepts images.

Optional extras:

| Variable | Use |
|---|---|
| `AI_FALLBACK_MODELS` | comma-separated model ids. They are raced, not just queued: the next model starts at once if one fails, or alongside it if one is slow. First valid answer wins. A lone model is tried twice |
| (photos) | photo reading uses the same chain. A model that reports "no food" is followed by the next one, since a model that cannot see the picture says that too |
| `AI_HEDGE_SECONDS` | how long a slow model runs alone before the next one joins (default 20) |
| `AI_MIN_GAP_SECONDS` | spacing between provider calls, for a requests-per-minute cap (`12.5` for 5 per minute) |

Replies are parsed leniently (code fences and trailing commas are tolerated). If the provider is busy or its balance is
empty, the app quietly falls back to the offline planner and photo reading says to type instead. The reason is in the
server log as `AI call failed: ...`.

Limits for a small test: `INVITE_CODE`, `MAX_USERS` (10 in `run_test.sh`), `AI_DAILY_LIMIT` (20 AI calls per user per day,
shared by plans, photo reads and step updates), admin lockout after 20 wrong tokens. Each AI call is one provider request.

Reminders are scheduled on the phone, so they work without a server or Firebase. Server-sent push (for example a coach
message to a user) would need Firebase Cloud Messaging and is not included.

## Build the Android app

1. Open `android/` in Android Studio. Set **Gradle JDK** to the bundled JetBrains Runtime 21 (Gradle cannot run on JDK 25).
2. Run on a phone, or Build, Build Bundle(s) / APK(s), Build APK(s) and send `app-debug.apk` to testers (they allow "install unknown apps" once).
3. Testers enter the server address and invite code on the last onboarding step. Phones need an HTTPS address: the Cloudflare tunnel from `run_test.sh`, or the permanent one from `deploy/gcp/`. The default `API_BASE` (`http://10.0.2.2:8080`) only reaches your Mac from an emulator.

## iPhone and other browsers

Open `https://<your server>/app` in Safari, tap Share, then Add to Home Screen, and open Lazy Fitness from the Home Screen. Sign up there: the Home Screen app keeps its own data, separate from Safari. The invite code is the same one the Android testers use. No Xcode, Apple account or App Store is needed.

What it does not do, compared with Android: no home-screen widget, and no reminders while the app is closed (iOS only allows those for web apps through web push, which needs extra server work). It shows what is next when you open it. Data lives in the browser's storage, so clearing website data for the app clears the plan and ticks.

## How it works

- **Timeline** starts at wake time with 400 ml water. Workout, meals and sleep are spaced across the person's own wake and sleep times. Each meal lists foods to eat and to skip.
- **Reminders**: one alarm is armed for the next undone step. It shows the meal, what to eat, what to skip and a Done button, then arms the next one. Reboot, app update and clock changes re-arm it.
- **Skip, swap, change**: on any step you can mark it skipped (with a reason) or say what you had instead. The AI then gives a short tip and, for a swap, estimates the calories. "Change this meal" (or workout) takes a request like "no paneer" and the AI rewrites just that step, with an Undo. For meals you can also choose or take a photo, and an AI vision model lists the food and estimates calories. You check and edit the result before saving. The photo is not stored, on the phone or the server.
- **Calories**: what a person ate instead, a rewritten meal and a meal photo are priced from `backend/foods.txt`, a table of about 400 Pune food names with calories per portion (edit it freely; the server reads it at start). "2 samosas and a cup of chai" is read as quantities and priced from the table, so it does not depend on the AI. Only foods the table lacks go to the AI, which gives the calories of one unit; that answer is stored in the database (`foods` table, listed at `/admin/api/foods`) and reused, and the first estimate for a food is never overwritten. Calories on the timeline for planned meals are still the day's target split by meal, not summed from the food.
- **Foods I don't want**: say "I don't like bhakri" (or "mujhe karela pasand nahi") when you change a meal and it is remembered for that person for good. A plain-text rule reads common phrasings and the AI reports the rest; one-off wishes like "no paneer today" are not remembered. The list is kept on the server, sent to the AI in every prompt, enforced on AI plans and rewrites (a reply that uses an avoided food is rejected), and applied by both apps when they pick the day's meals. People see and edit it on the Me tab, and the admin sees it in the user drawer. If a person excludes nearly everything for a meal slot, a meal is shown anyway rather than leaving a gap.
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
