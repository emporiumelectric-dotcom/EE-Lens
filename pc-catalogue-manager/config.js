/*
 * Supabase project configuration.
 *
 * SUPABASE_ANON_KEY is the "publishable" key, not a secret: it is designed to
 * sit in client-side code and be visible to anyone who opens this page. Row
 * Level Security policies on the ee_lens.products / ee_lens.product_photos
 * tables — not this key — decide what an anon or signed-in request can
 * actually read or write. Never put the service_role key here or anywhere
 * else in this app; it bypasses RLS entirely and must stay server-side only
 * (this tool has no server side).
 *
 * Kept in its own file so there is exactly one place to look when rotating
 * the key or pointing this tool at a different Supabase project.
 */
const SUPABASE_URL = 'https://buzidwccluskdkccidev.supabase.co';
const SUPABASE_ANON_KEY = 'sb_publishable_Zm5PI1gxB8ZU6_m4Dydirw_THsgZR7x';
// The schema these tables live in — every REST call must say so explicitly,
// since PostgREST only serves the "public" schema by default.
const SUPABASE_SCHEMA = 'ee_lens';
// Private Storage bucket holding product photos synced to the cloud.
const SUPABASE_PHOTOS_BUCKET = 'ee-lens-photos';
/*
 * "Import from product URL" needs something to fetch an external page --
 * and, separately, each of that page's images -- on this tool's behalf; the
 * browser cannot do either directly for a site that doesn't grant CORS
 * permission (see import-url.js's fetchProductPage/fetchImageProxyEndpoint
 * and images.js's fetchImageUrl). The local helper (server.py, started by
 * "EE Lens Manager.bat") only ever listens on this same PC, so it only
 * exists -- and is only used -- when this page itself was opened from
 * 127.0.0.1/localhost. Anywhere else (lens.electricemporium.in included, a
 * GitHub Pages site with no backend of its own) both jobs go to this
 * Cloudflare Worker instead. See cloudflare/import-url-proxy/README.md for
 * what to deploy and paste here.
 */
const CLOUD_FETCH_PROXY_URL = 'https://ee-lens-fetch-proxy.emporiumelectric.workers.dev'; // e.g. 'https://ee-lens-import-proxy.YOUR-SUBDOMAIN.workers.dev'
const CLOUD_FETCH_PROXY_SECRET = ''; // only needed if the Worker's PROXY_SHARED_SECRET is set