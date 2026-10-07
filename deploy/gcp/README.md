# Run Lazy Fitness 24/7 on a free Google VM

Status: the setup script is **not tested on a real VM** (none available here). The server change it relies on
(`HOST`) and the backup logic are tested locally. Expect to read an error or two on the first run.

Cost: free if you stay inside Google's Always Free limits: one `e2-micro` VM in `us-west1`, `us-central1` or
`us-east1`, a 30 GB **standard** persistent disk, and no more than 1 GB of data out per month to North America.
Check the current rules at cloud.google.com/free before you start. A card is needed to open the account.

## 1. Create the VM (console.cloud.google.com, about 10 minutes)

1. Create a Google Cloud account and a project, and add a billing card when asked.
2. **Safety net first:** Billing, then Budgets & alerts, create a budget of $1 with email alerts.
3. Compute Engine, Create instance:
   - Name: `lazy-fitness`
   - Region: `us-central1` (or `us-west1` / `us-east1`)
   - Machine type: **E2, e2-micro**
   - Boot disk: **Ubuntu 24.04 LTS**, disk type **Standard persistent disk**, size **30 GB**
   - Firewall: tick **Allow HTTP traffic** and **Allow HTTPS traffic**
4. Create. Leave the external IP as Ephemeral (the DuckDNS updater handles IP changes).

## 2. Get a free stable address (DuckDNS)

1. Sign in at duckdns.org and add a subdomain, for example `lazyfit-yourname`. You get `lazyfit-yourname.duckdns.org`.
2. Copy your **token** from the top of that page. Treat it like a password.

Own domain instead? Point an A record at the VM's external IP and skip the token.

## 3. Upload the code

On your Mac:

```bash
cd ~/Documents/lazy-fitness
zip -r lazy-fitness.zip backend deploy -x '*/__pycache__/*' '*.db*' '*/.admin_token' '*/.invite_code'
```

In the console, click **SSH** next to your VM. In the SSH window use the gear icon, then **Upload file**, and pick
`lazy-fitness.zip`. Then:

```bash
sudo apt-get install -y unzip
unzip -o lazy-fitness.zip -d lazy-fitness
cd lazy-fitness
```

## 4. Run the setup

```bash
sudo DOMAIN=lazyfit-yourname.duckdns.org DUCKDNS_TOKEN=paste-token-here bash deploy/gcp/setup.sh
```

Add `WITH_AI=1` in front of `bash` to install the Anthropic package.

At the end it prints the **server address**, **invite code**, **admin address** and **admin token**.
Give testers the first two. Open `https://your-address/admin` and sign in with the token.

## 5. Optional: Claude-built plans (this is the only paid part)

```bash
sudo WITH_AI=1 bash deploy/gcp/setup.sh     # once, installs the package
sudo nano /etc/lazy-fitness.env             # remove the # before ANTHROPIC_API_KEY and paste your key
sudo systemctl restart lazy-fitness
```

Each user is capped at 3 AI plans a day (`AI_DAILY_LIMIT`). The key stays on the VM.

## Day-to-day

| Task | Command |
|---|---|
| See if it is running | `sudo systemctl status lazy-fitness` |
| Read logs | `sudo journalctl -u lazy-fitness -f` |
| Change invite code, user cap, AI limit | `sudo nano /etc/lazy-fitness.env`, then `sudo systemctl restart lazy-fitness` |
| Update the code | Upload a new zip, unzip, run the same setup command again. Settings and data are kept |
| Backup now | `sudo /usr/local/bin/lazy-fitness-backup` (nightly at 03:00 automatically, last 14 kept) |

**Copy a backup to your Mac** (a backup on the same VM does not protect you if the VM is lost): in the SSH window,
`sudo cp /var/backups/lazy-fitness/fitcoach-*.db ~/` then use the gear icon, **Download file**.

## Point the app at the permanent address

Testers enter the server address on the last onboarding step. To pre-fill it for everyone, change `API_BASE` in
`android/app/build.gradle.kts` to your `https://...` address and rebuild the APK.

## Known limits

- The admin page is on the public internet, protected by the token and a lockout after 20 wrong tries. Do not share the token.
- One VM, one SQLite file. Fine for tens of users, not for thousands.
- US region means roughly a quarter second of delay from India. Syncing ticks does not need to be faster.
- DuckDNS is a third-party free service. If it goes down, the HTTPS address stops resolving until it returns.
