# Import-from-URL fetch proxy

Lets "Import from product URL" work on `lens.electricemporium.in` (GitHub
Pages has no backend of its own), by fetching the page -- and, separately,
each of its images -- server-side on Cloudflare and handing them back to the
browser. That's the same job `server.py`'s `/page?` and `/fetch?` routes
already do when the Catalogue Manager is opened locally via `EE Lens
Manager.bat`. `import-url.js` and `images.js` pick whichever one to call
automatically; neither this file nor `server.py` needed to change to make
that work. See `worker.js`'s header comment for the design, and
`import-url.js`'s `fetchProxyEndpoint()`/`fetchImageProxyEndpoint()` for the
client side.

One Worker answers both jobs: `/?url=...` for a page, `/image?url=...` for
an image. Deploying is the one step below either way -- there's nothing
extra to set up for images specifically.

**Fixed, but worth knowing:** the candidate-image thumbnails shown while
reviewing an import used to load as plain `<img src>` tags pointed at this
Worker's `/image` route. That silently 403'd every single one on the hosted
site: a bare `<img>` load never sends an `Origin` header, only a `Referer`,
and under the browser's default `strict-origin-when-cross-origin` policy
that `Referer` is origin-only (no path) for any cross-origin request --
which this Worker's access check (`Origin`, or a *path-qualified* `Referer`)
didn't accept. `import-url.js`/`app.js` now load thumbnails through
`fetchImageUrl()` (a real `fetch()` call, same function that downloads the
image for real at approve time) instead, which sends `Origin` reliably.
This also means the optional `PROXY_SHARED_SECRET` header now reaches
thumbnail requests too, not just the approve-time download.

## Cost

Free. Cloudflare Workers' free plan includes 100,000 requests/day, no
credit card required to sign up. A shop's own "import from URL" usage is
nowhere close to that. If it ever were, the paid plan is $5/month for 10
million requests -- not a realistic scenario here, just noting it for
completeness.

## What you need to do (one-time, in the Cloudflare dashboard)

I can't do this step myself -- it needs your Cloudflare account.

1. **Sign up / log in** at [dash.cloudflare.com](https://dash.cloudflare.com)
   if you don't already have an account. Free plan, no card needed.
2. In the left sidebar, go to **Workers & Pages** → **Create** → **Create
   Worker**.
3. Give it a name -- `ee-lens-import-proxy` matches `wrangler.toml` here,
   but any name is fine, it just changes the URL you get in step 5.
4. It opens an in-browser code editor with a placeholder script. **Delete
   everything in it** and paste in the full contents of `worker.js` from
   this folder. Click **Deploy**.
5. Cloudflare gives you a URL like:
   `https://ee-lens-import-proxy.<your-subdomain>.workers.dev`
   **Copy this URL** -- you'll paste it into one line of `config.js` (see
   below). `<your-subdomain>` is a name Cloudflare assigned to your account
   the first time you used Workers; it's already filled in for you on that
   page, you don't need to invent anything.
6. (Recommended, optional) Add the shared-secret header for extra
   hardening beyond the built-in Origin check: on the Worker's page, go to
   **Settings** → **Variables and Secrets** → **Add** → name it
   `PROXY_SHARED_SECRET`, type **Secret**, and paste in any long random
   value (e.g. generate one at
   [1password.com/password-generator](https://1password.com/password-generator/)
   or similar). Save. If you do this, also set the same value as
   `CLOUD_FETCH_PROXY_SECRET` in `pc-catalogue-manager/config.js` (see
   below) -- **both must match exactly**, or every import will fail with
   "Missing or wrong proxy key." Leaving this unset is fine; the Origin
   check alone is the default and the Worker works without it.

## Wiring it up in this repo (already done, nothing more to deploy)

`pc-catalogue-manager/config.js` has two new lines:

```js
const CLOUD_FETCH_PROXY_URL = 'https://ee-lens-import-proxy.YOUR-SUBDOMAIN.workers.dev';
const CLOUD_FETCH_PROXY_SECRET = ''; // only needed if you set PROXY_SHARED_SECRET above
```

**Replace `YOUR-SUBDOMAIN`** with what Cloudflare actually gave you in step
5 above, commit, and push (or just edit the file directly on
`lens.electricemporium.in`'s repo and let the next deploy pick it up). If
you added the shared secret in step 6, put the same value in
`CLOUD_FETCH_PROXY_SECRET` too.

Until `CLOUD_FETCH_PROXY_URL` is filled in with your real Worker URL,
"Import from product URL" on the hosted site shows a clear "couldn't reach
the fetch service" message rather than failing silently or oddly -- it's
safe to merge and deploy this before you've done the Cloudflare steps
above; import just won't work on the hosted site until you have.

## If you'd rather use the CLI instead of the dashboard editor

If you have Node.js installed:

```sh
cd cloudflare/import-url-proxy
npx wrangler login      # opens a browser to authorize once
npx wrangler deploy     # reads wrangler.toml, deploys worker.js
```

`wrangler deploy`'s output prints the same `*.workers.dev` URL as step 5
above. To set the optional shared secret this way instead of the
dashboard: `npx wrangler secret put PROXY_SHARED_SECRET`.

## Updating the Worker later

Whenever `worker.js` changes in this repo, repeat step 4 above (paste the
new contents into the same Worker's editor and Deploy) or re-run
`wrangler deploy` from the CLI -- either updates the same URL in place, no
new setup needed.

## Known limitation: some sites' bot-detection will block this Worker outright

Flipkart, tested for real, returns its "Are you a human?" verification page
through this Worker instead of the real product page -- while the exact
same URL works correctly through `server.py`'s local helper.

Investigated before assuming a fix: compared the two requests header for
header. They already matched almost exactly (same `User-Agent`, same
`Accept`); the one real gap (`Accept-Language`, which `server.py` sent and
this Worker didn't) is now fixed, sending both requests down effectively
identical headers. That headers were already this close, yet only one side
is blocked, is itself the finding: this isn't a header problem. Cloudflare
Workers make outbound requests from Cloudflare's own shared datacenter IP
ranges, which large sites' bot-detection systems (Akamai, PerimeterX/HUMAN,
DataDome, and platforms' own in-house systems) specifically fingerprint and
block, independent of what headers say -- likely including the TLS/HTTP
handshake shape itself, a layer no header set in a Worker's `fetch()` call
can touch. `server.py`'s request, by contrast, leaves from the shop's own
ordinary PC connection, which looks like a normal shopper.

**This is very likely not fixable from inside this Worker.** A materially
different approach -- Cloudflare's separate Browser Rendering product, an
actual headless-Chrome-in-the-cloud service with a real browser's
fingerprint -- might fare better against fingerprint-based detection
specifically, but it still runs from Cloudflare's network (so may not
escape IP-based blocking either) and is a heavier, differently-priced tool
than this. Not attempted here. For a site that blocks datacenter traffic
like this, `server.py` (via `EE Lens Manager.bat`) remains the reliable
path -- which is exactly the job it's already there for. See `worker.js`'s
own comment above its `USER_AGENT` constant for the full writeup, including
why the User-Agent itself was deliberately left as the honest,
self-identifying string it already was rather than swapped for a fake
browser UA to try to look less like a known tool.

## Known limitation: even a Cloudflare-fronted site can start challenging this Worker temporarily

Separate from the Flipkart finding above: `atomberg.com` -- itself hosted
behind Cloudflare -- started returning its own "verify you are human"
interstitial through this Worker after being fetched repeatedly during
testing on the same day it had worked fine. `worker.js` now recognises that
specific interstitial (a stable, well-known Cloudflare page -- its
`/cdn-cgi/challenge-platform/` script path, "Just a moment..." title, and
cookie-notice wording have been consistent across every redesign of it) and
reports it plainly instead of handing the raw challenge page to the
importer to be mis-parsed as a garbled, blank "product". That's detection,
not evasion -- it does nothing to get past the challenge.

**Read this as very likely temporary, not a new persistent block.** A few
reasons, reasoned from how Cloudflare's own bot-management is documented to
work, not verified live (this sandbox has no path to `atomberg.com`):

- The same URL worked earlier the same day; a genuinely persistent block
  wouldn't have. Cloudflare's per-visitor risk scoring is dynamic --
  repeated requests to one zone in a short window from a shared,
  cookie-less source (which is structurally what a Worker's `fetch()`
  always is: no session, no `cf_clearance` cookie carried between calls)
  raises that score, and it decays over time once the traffic stops.
- This project tested this same URL and others on `atomberg.com`
  repeatedly on the same day while investigating the MRP gap above --
  itself the kind of burst pattern that trips exactly this.
- Separately worth knowing, not fixed here: each import already fires a
  concurrent `fetchImageUrl()` call per candidate image (up to 14) against
  the same origin -- a real burst on every normal import of a
  multi-photo product, not just during testing. Deliberately **not**
  changed in response to this report: doing so specifically because of a
  bot challenge would be exactly the kind of workaround this investigation
  was told not to attempt. If it's ever worth doing as a matter of being a
  considerate client generally, that's a separate, explicitly-scoped
  decision, not a side effect of chasing this issue away.

If imports from `atomberg.com` are still challenged after some time has
passed, that would be worth a fresh look -- but the honest expectation
going in is that this clears on its own.

## Known limitation, worth a real review before this is trusted further

`worker.js`'s `refusalFor()` blocks obviously-private hostnames
(`localhost`, `127.*`, `10.*`, `192.168.*`, etc.) by string pattern, but
the Workers runtime has no API to resolve a hostname to its actual IP
address before fetching it -- so a public hostname that happens to resolve
to a private address (DNS rebinding) isn't caught the same way
`server.py`'s local helper catches it (which does a real DNS lookup and
inspects the resolved address). Cloudflare's platform already restricts
what a Worker's `fetch()` can reach on Cloudflare's own internal network,
but that's a platform behavior this code doesn't verify, not a guarantee
about arbitrary private networks. Fine for the job this does today
(reading a public product page's HTML for the importer to parse, never
executed or trusted) -- worth a proper look if this proxy is ever asked to
do more.
