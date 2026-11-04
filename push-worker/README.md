# Flowstate chat push relay (free, about 10 minutes)

Instant group chat notifications without the Blaze plan. A tiny Cloudflare Worker (free plan:
100,000 requests a day) receives "I just sent a message" from the app, checks it, and asks Firebase
Cloud Messaging to push it to everyone else in the group. Firebase stays on Spark.

## 1. Get a Firebase service account key
1. Firebase console → flowstate-241f0 → gear icon → **Project settings** → **Service accounts**.
2. **Generate new private key** → it downloads a JSON file. Keep it private; never commit it.
3. Same settings page → **Cloud Messaging** tab: check **Firebase Cloud Messaging API (V1)** says *Enabled*.

## 2. Create the Worker
1. Sign up free at https://dash.cloudflare.com (no card needed).
2. **Workers & Pages** → **Create** → **Create Worker** → name it `flowstate-push` → **Deploy**.
3. **Edit code** → delete everything → paste the whole of `worker.js` from this folder → **Deploy**.

## 3. Add the secrets
Worker → **Settings** → **Variables and Secrets** → **Add**:

| Name | Type | Value |
|---|---|---|
| `FIREBASE_PROJECT_ID` | Text | `flowstate-241f0` |
| `SA_CLIENT_EMAIL` | Text | `client_email` from the JSON |
| `SA_PRIVATE_KEY` | Secret | `private_key` from the JSON, the whole `-----BEGIN PRIVATE KEY----- ... -----END PRIVATE KEY-----` text (the `\n`s are fine) |

Click **Deploy** after saving. Opening the Worker URL in a browser should say
"Flowstate push relay is running."

## 4. Point the app at it
Put the Worker URL (e.g. `https://flowstate-push.yourname.workers.dev`) into
`app/src/main/res/values/flowstate_push.xml` → `flowstate_push_url`, then push to main as usual.
Blank URL = pushes off; chat still works.

## Notes
- No Firestore rules change is needed: the relay reads with the service account.
- The relay only pushes a message that the caller wrote, in a group they belong to, sent in the last 2 minutes.
- Everyone needs 1.4.9+ to *receive* pushes (phones subscribe to their groups' topics on launch).
- Settings → Notifications → Chat notifications turns pushes off on that phone.
- OnePlus/ColorOS: if pushes arrive late with the app swiped away, set Flowstate's battery use to "Allow background activity".
