# Deploying DevPilot (free tier, demo mode)

This guide deploys DevPilot as a **public portfolio demo** at no hosting cost:

| Piece | Host | Free-tier catch |
|---|---|---|
| Frontend (Next.js) | **Vercel** (Hobby) | None that matter here |
| Backend (Spring Boot) | **Render** (Free web service) | Sleeps after 15 min idle; first request takes 1–2 min to wake (Spring starts in ~90 s) |
| Database (Postgres + pgvector) | **Neon** (Free) | 0.5 GB storage; sleeps when idle (wakes in under a second) |
| LLM + embeddings | **Google Gemini API** (free tier) | Low rate limits; indexing is throttled, so it takes a few minutes |

## How it fits together

```
Browser ──► https://<app>.vercel.app ─┬─► Next.js pages (Vercel)
                                      │
                                      └─► /api/*, /oauth2/*, /login/oauth2/*
                                           proxied to ──► https://<api>.onrender.com (Spring Boot)
                                                                   │
                                                                   ├─► Neon Postgres (pgvector)
                                                                   ├─► Gemini API (OpenAI-compatible)
                                                                   └─► GitHub API
```

**Why the proxy?** The backend keeps you logged in with a session cookie. Browsers won't send
cookies to a *different* site (`vercel.app` → `onrender.com`) on API calls, so login would
silently fail. With `BACKEND_URL` set, `client/next.config.ts` makes Vercel forward the backend's
paths, so the browser only ever talks to `vercel.app` and the cookie stays first-party. No
custom domain needed.

**What demo mode does** (`DEMO_MODE=true`):

- The login page shows **Try the live demo**: visitors get a guest session without GitHub.
- Visitors only see the preset repos in `DEMO_REPOS`, and can't sync or index.
- Only `DEMO_ADMIN_GITHUB_USERNAMES` (you) can sign in with GitHub to sync and index, and only the preset repos.
- Each visitor gets `DEMO_MAX_MESSAGES_PER_USER` questions (default 20). All visitors combined get
  `DEMO_MAX_MESSAGES_PER_DAY` (default 200) per UTC day. This keeps the demo inside Gemini's free
  daily quota (and caps the bill if you use a paid provider instead).
- GitHub sign-in asks only for `read:user` (no access to anyone's private repos). That's enough
  because the preset repos are public.

---

## Step 0: Fork

1. Fork the repo to your GitHub account. Your preset repo will be `<your-username>/DevPilot`.
2. Make sure the fork is **public**: demo mode indexes it with a `read:user` token, which can only read public repos.

Every step below is free and none needs a credit card. If a service asks for one, stop: something
is set to a paid plan.

## Step 1: Frontend first (Vercel), to get its URL

The GitHub OAuth app and the backend both need the frontend's URL, so create the frontend first.

1. Sign up at vercel.com with GitHub (Hobby plan). **Add New → Project**, import your fork.
2. Project name: e.g. `devpilot`. **Root Directory: `client`** (important). Framework: Next.js
   (auto-detected). **Don't add any environment variables yet.**
3. Deploy. The site loads but can't reach a backend yet; that's expected.
4. Find the production URL: Project → **Settings → Domains**. Use the `*.vercel.app` domain listed
   there (e.g. `https://devpilot-abc.vercel.app`), not the long per-deployment URL with a hash.

Below, `FRONTEND` means that URL (with `https://`, no trailing slash).

## Step 2: Free Gemini API key (its own Google Cloud project)

1. Go to Google AI Studio (aistudio.google.com) and sign in with a Google account.
2. Click **Get API key → Create API key** and choose to create it in a **new project** (not the one
   you use for local testing), so local experiments can't use up the demo's daily quota.
   Copy the key → `OPENAI_API_KEY` (the app talks to Gemini through its OpenAI-compatible API,
   so the variable keeps the OpenAI name).
3. **Don't enable billing** on that project. Without billing, Google can't charge you: when the free
   quota runs out, requests are refused (the app shows a "try again later" message) instead of billed.

`render.yaml` already points the app at Gemini (`OPENAI_BASE_URL`, `OPENAI_CHAT_MODEL=gemini-3.5-flash-lite`,
`OPENAI_EMBEDDING_MODEL=gemini-embedding-001`, `EMBEDDING_DIMENSIONS=1536`). Free-tier limits to know about:

- Embeddings: 100 chunks per minute and **1,000 per day** (each chunk counts as one request). The
  daily quota frees up again within about a day; Google doesn't promise an exact reset time.
  Indexing is throttled to 90/min (`EMBEDDING_CHUNKS_PER_MINUTE`),
  so a repo the size of DevPilot (~750 chunks) takes about 9 minutes and uses ~75% of a day's
  quota: index it **once**, not repeatedly. Every chat question also uses one embedding.
- Chat: a daily request limit per model (see your project's limits at aistudio.google.com/rate-limit).
  Keep `DEMO_MAX_MESSAGES_PER_DAY` below it.
- On the free tier Google may use prompts to improve its products, so the login page tells visitors
  their questions are sent to Google Gemini (`AI_PROVIDER_NAME`; empty hides the line). Don't index
  anything private with a free key.

**Optional, paid: OpenAI instead.** Add ~$5 credit on platform.openai.com, create a project with a
monthly budget, create a key, and set on Render:

| Key | Value |
|---|---|
| `OPENAI_API_KEY` | your OpenAI key |
| `OPENAI_BASE_URL` | `https://api.openai.com/v1` (the `/v1` is required) |
| `OPENAI_CHAT_MODEL` | `gpt-4o-mini` |
| `OPENAI_EMBEDDING_MODEL` | `text-embedding-3-small` |
| `EMBEDDING_CHUNKS_PER_MINUTE` | `0` (no throttle needed) |
| `AI_PROVIDER_NAME` | `OpenAI` (named on the login page) |

Then re-index the preset repo: vectors from different embedding models can't be compared.

## Step 3: Database (Neon)
1. Sign up at neon.com (no card needed) and create a project. Pick region **AWS US East 2 (Ohio)**,
   because the Render service uses Ohio too. Keeping them close makes every query faster.
2. On the project dashboard click **Connect**. Turn **Connection pooling off** (use the direct
   connection) and copy the connection string. It looks like:
   `postgresql://neondb_owner:AbC123@ep-cool-name-123456.us-east-2.aws.neon.tech/neondb?sslmode=require`
3. Split it into the three values Spring Boot wants:
   - `SPRING_DATASOURCE_URL` = `jdbc:postgresql://ep-cool-name-123456.us-east-2.aws.neon.tech/neondb?sslmode=require`
     (add `jdbc:`, drop the `user:password@` part)
   - `SPRING_DATASOURCE_USERNAME` = `neondb_owner`
   - `SPRING_DATASOURCE_PASSWORD` = `AbC123`

You don't need to create any tables or extensions. On first start, the app creates its tables (Hibernate)
and the `vector` table/extension (Spring AI).

## Step 4: GitHub OAuth app (for your owner sign-in)
GitHub → Settings → Developer settings → **OAuth Apps** → New OAuth App:

- Application name: `DevPilot`
- Homepage URL: `FRONTEND`
- Authorization callback URL: `FRONTEND/login/oauth2/code/github`

Create it, then **Generate a new client secret**. Keep both values: `GITHUB_CLIENT_ID` and
`GITHUB_CLIENT_SECRET`. Use a separate OAuth app from your local ones, because each OAuth app has one callback URL.

## Step 5: Backend (Render)
1. Sign up at render.com with GitHub. **New → Blueprint**, pick your fork. Render reads
   `render.yaml` and proposes one service, `devpilot-api`, on the Free plan.
2. Fill in the values it asks for:

   | Key | Value |
   |---|---|
   | `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | from step 3 |
   | `OPENAI_API_KEY` | from step 2 |
   | `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` | from step 4 |
   | `GITHUB_REDIRECT_URI` | `FRONTEND/login/oauth2/code/github` |
   | `FRONTEND_URL` | `FRONTEND` |
   | `CORS_ALLOWED_ORIGINS` | `FRONTEND` |
   | `TOKEN_ENCRYPTOR_SALT` | output of `openssl rand -hex 8` (16 hex chars) |
   | `DEMO_ADMIN_GITHUB_USERNAMES` | your GitHub username |
   | `DEMO_REPOS` | `<your-username>/DevPilot` (comma-separate to add more) |

   `TOKEN_ENCRYPTOR_PASSWORD` is generated for you. Don't change it later, or stored GitHub
   tokens can't be decrypted (you'd just need to sign in again).
3. Apply. The first build compiles the backend in Docker and takes several minutes.
4. When it's live, copy the service URL (e.g. `https://devpilot-api-ab12.onrender.com`) and open
   `<that URL>/actuator/health`. You should see `{"status":"UP"}`.

If the deploy fails, open the service's **Logs**. Most failures are a typo in the database
values. See Troubleshooting below.

## Step 6: Connect the frontend to the backend

1. Vercel → Project → **Settings → Environment Variables**: add `BACKEND_URL` = your Render URL from
   step 5 (no trailing slash), for Production. Do **not** set `NEXT_PUBLIC_API_URL` on Vercel;
   `BACKEND_URL` takes care of it.
2. **Deployments → ⋯ → Redeploy.** `BACKEND_URL` is read at build time (it turns on the proxy), so
   saving the variable alone changes nothing.
3. Open `FRONTEND/api/auth/config`. It should return `{"demoMode":true,...}`, served by the
   backend through the proxy.

## Step 7: Index the preset repo (one time)

1. Open `FRONTEND/login` and click **Owner sign-in with GitHub** (wait 1–2 minutes if the backend is waking up).
2. On the dashboard click **Sync**, find `<your-username>/DevPilot`, click **Index**.
3. Wait for **Ready** (about 9 minutes for DevPilot on the Gemini free tier; keep the tab
   open so Render doesn't fall asleep), then ask a test question in chat.

## Step 8: Test it as a visitor

Open `FRONTEND` in a private/incognito window → **Try the live demo**. You should see only the
preset repo, no Sync/Index buttons, and chat should work. That's the link for your portfolio.

---

## Putting it on your portfolio

- Link straight to `FRONTEND/login` so visitors land on the **Try the live demo** button.
- Mention the cold start next to the link ("free hosting, first load takes 1–2 min"). That way a
  recruiter waits instead of assuming the site is down.
- A 30–60 second screen recording or a few screenshots next to the link helps people who won't wait.
- Credit the team (the contributors listed in `README.md`) and say what *you* built.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Login page shows "Waking up the server…" for over 3 minutes | Render is still deploying or crashed. Check Render logs |
| Opening the Render URL itself shows `{"status":404,..."No static resource ."}` | Expected: the backend is API-only. Use `FRONTEND`; check the backend with `<render-url>/actuator/health` |
| First Render start logs `constraint "uk..." of relation "users" does not exist, skipping` | Harmless: Hibernate drops-if-exists before creating its unique constraints on an empty database |
| `FRONTEND/api/auth/config` returns 404 | `BACKEND_URL` is missing, or was added without a **Redeploy** (it's read at build time) |
| Render has no memory graph | The free plan doesn't show metrics. Watch Logs for `OutOfMemoryError` / `exited with code 137`, especially while indexing |
| GitHub says "redirect_uri is not associated with this application" | OAuth app callback ≠ `GITHUB_REDIRECT_URI`. They must match exactly |
| After GitHub sign-in you land back on `/login?error=...` | `FRONTEND_URL` or `GITHUB_REDIRECT_URI` points at the wrong domain |
| Owner sign-in works but there's no Sync/Index | Your username isn't in `DEMO_ADMIN_GITHUB_USERNAMES` (it's case-insensitive) |
| "Demo mode only indexes the preset repos" | Add the repo's `owner/name` to `DEMO_REPOS` and redeploy |
| Visitors see no repos | The preset repo isn't indexed yet, or `DEMO_REPOS` doesn't match its full name |
| Indexing fails with 404 from GitHub | The repo is private. Make it public, or set `GITHUB_OAUTH_SCOPES=read:user,repo` |
| Render log: `Connection to ... refused` / `password authentication failed` | Recheck the three Neon values; the URL must start with `jdbc:postgresql://` |
| Render log: `OutOfMemoryError` | The free instance has 512 MB. Lower heap with `JAVA_OPTS=-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -Xss512k -XX:TieredStopAtLevel=1` |
| Chat says "The AI service is getting too many requests" | Gemini free-tier per-minute limit. Wait a minute |
| Chat says "used up today's free quota" | Gemini daily limit reached; it frees up again within about a day. Lower `DEMO_MAX_MESSAGES_PER_DAY` |
| Indexing fails with "daily embedding quota is used up" | The repo was indexed more than once today (1,000 chunks/day). Retry tomorrow |
| Indexing fails with "rate limit was reached while indexing" | Something else is using the same key's per-minute quota. Retry later |
| Chat or indexing fails with 404 from the AI provider | `OPENAI_BASE_URL` is missing its version path (`.../v1beta/openai` for Gemini, `.../v1` for OpenAI) |
| Indexing stops with "interrupted by a server restart" | Render slept or restarted mid-index. Click Retry, and keep the tab open while it runs |

## Optional: skip the cold start

A free Render service has enough monthly hours (750) to stay awake all month if something pings it
every ~10 minutes, e.g. a scheduled GitHub Action that does `curl <render-url>/actuator/health`. The
health check doesn't touch the database, so Neon still sleeps. Read Render's current free-tier
terms before doing this, and note that Render's paid Starter instance ($7/mo) removes sleep entirely.

## Running demo mode locally

Add to your `.env`:

```
DEMO_MODE=true
DEMO_ADMIN_GITHUB_USERNAMES=<your-username>
DEMO_REPOS=<your-username>/DevPilot
```

then `docker compose up --build` as usual.
