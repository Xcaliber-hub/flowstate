// Flowstate chat push relay: a single-file Cloudflare Worker (free plan, no npm needed).
//
// The app calls POST /notify with { groupId, messageId } and its Firebase ID token. The relay:
//   1. verifies the ID token (signed by Google, issued for this Firebase project),
//   2. reads the group and the message from Firestore with a service account,
//   3. checks the caller wrote that message and is in that group, and that it was sent in the last 2 min,
//   4. sends one high-priority FCM data message to the topic "chat_<groupId>".
// So nobody can push text they did not send, or push into a group they are not in.
//
// Secrets (Worker > Settings > Variables and Secrets):
//   FIREBASE_PROJECT_ID  flowstate-241f0
//   SA_CLIENT_EMAIL      client_email from the service account JSON
//   SA_PRIVATE_KEY       private_key from the service account JSON (the whole -----BEGIN PRIVATE KEY----- block)

const JWK_URL = "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";
const ID_RE = /^[A-Za-z0-9_-]{1,64}$/;
const MAX_AGE_MS = 2 * 60 * 1000;

let jwkCache = { keys: null, until: 0 };
let tokenCache = { token: null, until: 0 };
const recent = new Map(); // messageId -> time, so a retry never pushes twice

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/") return text("Flowstate push relay is running.");
    if (request.method !== "POST" || url.pathname !== "/notify") return text("Not found", 404);
    try {
      const auth = request.headers.get("Authorization") || "";
      const idToken = auth.startsWith("Bearer ") ? auth.slice(7) : "";
      const uid = await verifyIdToken(idToken, env.FIREBASE_PROJECT_ID);
      if (!uid) return text("Unauthorized", 401);

      const body = await request.json().catch(() => ({}));
      const { groupId, messageId } = body;
      if (!ID_RE.test(groupId || "") || !ID_RE.test(messageId || "")) return text("Bad request", 400);
      if (recent.has(messageId)) return text("Already sent", 200);

      const access = await accessToken(env);
      const base = `https://firestore.googleapis.com/v1/projects/${env.FIREBASE_PROJECT_ID}/databases/(default)/documents/groups/${groupId}`;
      const [group, msg] = await Promise.all([getDoc(base, access), getDoc(`${base}/messages/${messageId}`, access)]);
      if (!group || !msg) return text("Not found", 404);

      const members = (group.fields?.memberIds?.arrayValue?.values || []).map(v => v.stringValue);
      const from = msg.fields?.uid?.stringValue;
      if (!members.includes(uid) || from !== uid) return text("Forbidden", 403);
      const sentAt = Date.parse(msg.fields?.sentAt?.timestampValue || "") || Date.now();
      if (Date.now() - sentAt > MAX_AGE_MS) return text("Too old", 409);

      const res = await fetch(`https://fcm.googleapis.com/v1/projects/${env.FIREBASE_PROJECT_ID}/messages:send`, {
        method: "POST",
        headers: { Authorization: `Bearer ${access}`, "Content-Type": "application/json" },
        body: JSON.stringify({
          message: {
            topic: `chat_${groupId}`,
            data: {
              type: "chat",
              groupId,
              messageId,
              groupName: (group.fields?.name?.stringValue || "Group chat").slice(0, 60),
              uid,
              name: (msg.fields?.name?.stringValue || "Someone").slice(0, 40),
              text: (msg.fields?.text?.stringValue || "").slice(0, 500),
              sentAt: String(sentAt),
            },
            android: { priority: "HIGH", ttl: "86400s" },
          },
        }),
      });
      if (!res.ok) return text(`FCM error ${res.status}: ${await res.text()}`, 502);
      remember(messageId);
      return text("Sent", 200);
    } catch (e) {
      return text(`Error: ${e && e.message ? e.message : e}`, 500);
    }
  },
};

function text(s, status = 200) { return new Response(s, { status, headers: { "Content-Type": "text/plain" } }); }

function remember(id) {
  const now = Date.now();
  recent.set(id, now);
  if (recent.size > 2000) for (const [k, t] of recent) if (now - t > MAX_AGE_MS) recent.delete(k);
}

async function getDoc(url, access) {
  const r = await fetch(url, { headers: { Authorization: `Bearer ${access}` } });
  if (r.status === 404) return null;
  if (!r.ok) throw new Error(`Firestore ${r.status}`);
  return r.json();
}

// ---------- Firebase ID token check ----------
function b64urlToBytes(s) {
  s = s.replace(/-/g, "+").replace(/_/g, "/");
  while (s.length % 4) s += "=";
  const bin = atob(s);
  return Uint8Array.from(bin, c => c.charCodeAt(0));
}
function b64urlJson(s) { return JSON.parse(new TextDecoder().decode(b64urlToBytes(s))); }

async function googleKeys() {
  if (jwkCache.keys && Date.now() < jwkCache.until) return jwkCache.keys;
  const r = await fetch(JWK_URL);
  const { keys } = await r.json();
  const maxAge = Number((r.headers.get("Cache-Control") || "").match(/max-age=(\d+)/)?.[1] || 3600);
  jwkCache = { keys, until: Date.now() + maxAge * 1000 };
  return keys;
}

async function verifyIdToken(token, projectId) {
  const parts = (token || "").split(".");
  if (parts.length !== 3 || !projectId) return null;
  // A malformed token is a 401 (not a 500), so the app does not retry it.
  let header, p;
  try { header = b64urlJson(parts[0]); p = b64urlJson(parts[1]); } catch (_) { return null; }
  const now = Math.floor(Date.now() / 1000);
  if (header.alg !== "RS256") return null;
  if (p.aud !== projectId || p.iss !== `https://securetoken.google.com/${projectId}`) return null;
  if (!(p.exp > now) || !(p.iat <= now + 300) || !p.sub) return null;
  const jwk = (await googleKeys()).find(k => k.kid === header.kid);
  if (!jwk) return null;
  const key = await crypto.subtle.importKey("jwk", jwk, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
  const ok = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, b64urlToBytes(parts[2]),
    new TextEncoder().encode(`${parts[0]}.${parts[1]}`));
  return ok ? p.sub : null;
}

// ---------- Service account access token ----------
function bytesToB64url(bytes) {
  let bin = ""; bytes.forEach(b => (bin += String.fromCharCode(b)));
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
function strToB64url(s) { return bytesToB64url(new TextEncoder().encode(s)); }

async function accessToken(env) {
  if (tokenCache.token && Date.now() < tokenCache.until) return tokenCache.token;
  const now = Math.floor(Date.now() / 1000);
  const head = strToB64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = strToB64url(JSON.stringify({
    iss: env.SA_CLIENT_EMAIL,
    scope: "https://www.googleapis.com/auth/firebase.messaging https://www.googleapis.com/auth/datastore",
    aud: "https://oauth2.googleapis.com/token",
    iat: now, exp: now + 3600,
  }));
  const pem = (env.SA_PRIVATE_KEY || "").replace(/\\n/g, "\n")
    .replace(/-----(BEGIN|END) PRIVATE KEY-----/g, "").replace(/\s+/g, "");
  const der = Uint8Array.from(atob(pem), c => c.charCodeAt(0));
  const key = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${head}.${claims}`)));
  const r = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: `${head}.${claims}.${bytesToB64url(sig)}`,
    }),
  });
  const j = await r.json();
  if (!j.access_token) throw new Error(`Google token error: ${JSON.stringify(j)}`);
  tokenCache = { token: j.access_token, until: Date.now() + (j.expires_in - 120) * 1000 };
  return j.access_token;
}
