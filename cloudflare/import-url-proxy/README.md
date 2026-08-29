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
