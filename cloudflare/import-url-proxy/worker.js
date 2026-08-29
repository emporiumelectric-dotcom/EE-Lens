/*
 * Fetches an external product page server-side and hands its HTML back to
 * the browser, so "Import from product URL" works on
 * lens.electricemporium.in (GitHub Pages -- no backend of its own) the same
 * way it already does when EE Lens Manager.bat's local server.py answers
 * the identical job on 127.0.0.1. Both endpoints return the same
 * {finalUrl, html} JSON shape, so pc-catalogue-manager/import-url.js's
 * fetchProductPage() doesn't need to know or care which one answered --
 * see that file's fetchProxyEndpoint().
 *
 * Also answers /image?url=... the same way server.py's own /fetch route
 * does: downloads the image server-side and hands back the raw bytes,
 * since a browser can't do that itself for any image host that doesn't
 * send CORS headers (most of them) -- see fetchImageProxyEndpoint() in
 * import-url.js and fetchImageUrl() in images.js.
 *
 * Restricted to requests that plausibly come from the Catalogue Manager
 * itself: an Origin/Referer check against ALLOWED_ORIGIN, plus an optional
 * shared secret (env.PROXY_SHARED_SECRET, unset by default) for extra
 * hardening once deployed. This is a reasonable bar for a small internal
 * tool -- not a substitute for a real security review before this is
 * trusted with anything beyond what it does today: reading a public page
 * or image and returning it.
 */

const DEFAULT_ALLOWED_ORIGIN = 'https://lens.electricemporium.in';
const MAX_PAGE_BYTES = 4 * 1024 * 1024; // matches server.py's own MAX_PAGE_BYTES
const MAX_IMAGE_BYTES = 25 * 1024 * 1024; // matches server.py's own MAX_IMAGE_BYTES
const FETCH_TIMEOUT_MS = 20_000;

/**
 * Investigated, not guessed: a real product page on Flipkart returned its
 * "Are you a human?" verification page through this Worker, while the exact
 * same URL works through server.py's local helper. Before touching
 * anything, compared what each side actually sends -- and they already
 * matched: server.py's handle_page and this file send the identical
 * User-Agent string below, and (until this comment's own fix, just below)
 * differed only in server.py also sending Accept-Language, which this file
 * now sends too. That headers were already this close, yet one path is
 * blocked and the other isn't, is itself the evidence: it points away from
 * headers and toward *where the request comes from*, not what it says.
 *
 * Cloudflare Workers make outbound requests from Cloudflare's own shared
 * datacenter IP ranges -- well-known, heavily-trafficked, and specifically
 * fingerprinted and blocked by large sites' bot-detection systems (Akamai,
 * PerimeterX/HUMAN, DataDome, and large e-commerce platforms' own in-house
 * systems all do this), independent of any header content. server.py's
 * request instead leaves from whatever ordinary residential/business IP the
 * shop's own PC is on, which looks like a normal shopper and isn't
 * challenged. This also likely includes TLS/HTTP fingerprinting (JA3/JA4
 * handshake shape, ALPN/HTTP-version negotiation) that Cloudflare's network
 * stack presents and that no header set in this fetch() call can change --
 * that layer sits below anything JavaScript here controls.
 *
 * Net finding: this is very likely not fixable by adjusting headers sent
 * from *this* Worker, because it is not primarily a header problem. A
 * materially different approach -- e.g. Cloudflare's separate Browser
 * Rendering product, an actual headless-Chrome-in-the-cloud service, which
 * presents a real browser's TLS/JS fingerprint -- might fare better against
 * fingerprint-based detection specifically, but still runs from Cloudflare's
 * network and is a heavier, differently-priced tool than a plain fetch()
 * Worker; not attempted here. For a site that blocks datacenter traffic like
 * this, the shop's own local helper (server.py, via "EE Lens Manager.bat")
 * remains the reliable path -- which is exactly what it's already for.
 *
 * The User-Agent itself is left as the honest, self-identifying string
 * below (matching server.py) rather than swapped for a fake full browser UA
 * string: the evidence above suggests UA content was never the actual
 * blocker (server.py sends this same minimal string and isn't blocked), and
 * impersonating a real browser to look less like an identified tool is a
 * different, more evasive move than this fetcher's stated job -- reading a
 * public page's own markup, not disguising who's asking.
 */
const USER_AGENT = 'Mozilla/5.0 (EE Lens Catalogue Manager)';

function corsHeaders(allowedOrigin) {
  return {
    'Access-Control-Allow-Origin': allowedOrigin,
    'Access-Control-Allow-Methods': 'GET, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, X-Ee-Lens-Proxy-Key',
    'Access-Control-Max-Age': '86400',
    Vary: 'Origin'
  };
}

function textResponse(status, message, allowedOrigin) {
  return new Response(message, {
    status,
    headers: { 'Content-Type': 'text/plain; charset=utf-8', ...corsHeaders(allowedOrigin) }
  });
}

function jsonResponse(body, allowedOrigin) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json; charset=utf-8', ...corsHeaders(allowedOrigin) }
  });
}

/**
 * Refuses anything that is not a plausible public website -- mirrors
 * server.py's check_public, adapted for the Workers runtime, which has no
 * API to resolve a hostname to an IP address before fetching it (no
 * dns.resolve equivalent here). This is a weaker, string-pattern-only guard
 * than server.py's DNS-resolved check -- Cloudflare's own platform already
 * blocks a Worker's fetch() from reaching most of its internal network, but
 * that is a platform behavior this code doesn't control or verify, not a
 * guarantee. Flagged in the PR description as needing review before this
 * proxy is trusted with anything beyond its current job.
 */
function refusalFor(rawUrl) {
  if (!rawUrl) return 'Paste a link that starts with http:// or https://';
  let parsed;
  try {
    parsed = new URL(rawUrl);
  } catch {
    return 'That link has no website address in it.';
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    return 'Paste a link that starts with http:// or https://';
  }
  const host = parsed.hostname.toLowerCase();
  const privateHostPatterns = [
    /^localhost$/,
    /^127\./,
    /^0\.0\.0\.0$/,
    /^10\./,
    /^192\.168\./,
    /^169\.254\./, // link-local, including the cloud metadata address
    /^172\.(1[6-9]|2\d|3[01])\./, // 172.16.0.0/12
    /^::1$/,
    /^fe80:/i,
    /^fc00:/i,
    /^fd00:/i
  ];
  if (privateHostPatterns.some((pattern) => pattern.test(host))) {
    return 'That address is on a private network, not a public website.';
  }
  return null;
}

async function fetchWithTimeout(url, options, timeoutMs) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...options, signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
}

/** GET /?url=... -- the page-fetch job, returning {finalUrl, html}. */
async function handlePage(target, allowedOrigin) {
  let upstream;
  try {
    upstream = await fetchWithTimeout(
      target,
      {
        headers: {
          'User-Agent': USER_AGENT,
          Accept: 'text/html,application/xhtml+xml,*/*;q=0.8',
          // server.py's own handle_page sends this too; this Worker didn't,
          // which was a real, fixable gap -- but see the comment on
          // USER_AGENT above for why this alone won't get past a site that
          // blocks on where the request comes from, not what it says.
          'Accept-Language': 'en-IN,en;q=0.9'
        }
      },
      FETCH_TIMEOUT_MS
    );
  } catch (error) {
    const timedOut = error.name === 'AbortError';
    return textResponse(
      502,
      timedOut ? 'That page took too long to answer.' : `That page could not be opened: ${error.message}`,
      allowedOrigin
    );
  }

  const contentType = upstream.headers.get('content-type') || '';
  if (!contentType.includes('html') && !contentType.includes('xml')) {
    return textResponse(415, 'That link is not a product page.', allowedOrigin);
  }

  const buffer = await upstream.arrayBuffer();
  if (buffer.byteLength > MAX_PAGE_BYTES) {
    return textResponse(413, 'That page is larger than this fetcher will read.', allowedOrigin);
  }

  let charset = 'utf-8';
  const charsetMatch = contentType.match(/charset=([^;]+)/i);
  if (charsetMatch) charset = charsetMatch[1].trim();
  let html;
  try {
    html = new TextDecoder(charset).decode(buffer);
  } catch {
    html = new TextDecoder('utf-8').decode(buffer); // an unrecognised charset name -- read as UTF-8 rather than fail outright
  }

  return jsonResponse({ finalUrl: upstream.url, html }, allowedOrigin);
}

/**
 * GET /image?url=... -- downloads an image link the browser itself can't,
 * for the same reason it can't read the page: most image hosts don't send
 * CORS headers either. Mirrors server.py's own /fetch route: same checks,
 * same failure wording, so import-url.js's callers don't need to know which
 * one answered.
 */
async function handleImage(target, allowedOrigin) {
  let upstream;
  try {
    upstream = await fetchWithTimeout(
      target,
      { headers: { 'User-Agent': USER_AGENT, Accept: 'image/*,*/*;q=0.8' } },
      FETCH_TIMEOUT_MS
    );
  } catch (error) {
    const timedOut = error.name === 'AbortError';
    return textResponse(
      502,
      timedOut ? 'That image took too long to download.' : `Could not download that image: ${error.message}`,
      allowedOrigin
    );
  }

  if (!upstream.ok) {
    return textResponse(502, `Could not download that image (HTTP ${upstream.status}).`, allowedOrigin);
  }

  const contentType = upstream.headers.get('content-type') || '';
  if (!contentType.startsWith('image/')) {
    return textResponse(415, 'That link did not return an image.', allowedOrigin);
  }

  const buffer = await upstream.arrayBuffer();
  if (buffer.byteLength > MAX_IMAGE_BYTES) {
    return textResponse(413, 'That image is larger than 25 MB.', allowedOrigin);
  }

  return new Response(buffer, {
    status: 200,
    headers: { 'Content-Type': contentType, 'Cache-Control': 'no-store', ...corsHeaders(allowedOrigin) }
  });
}

export default {
  async fetch(request, env) {
    const allowedOrigin = env.ALLOWED_ORIGIN || DEFAULT_ALLOWED_ORIGIN;

    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: corsHeaders(allowedOrigin) });
    }
    if (request.method !== 'GET') {
      return textResponse(405, 'Only GET is supported.', allowedOrigin);
    }

    // The real access control -- gates non-browser callers too (curl,
    // scripts), unlike the CORS headers above, which only ever shape what a
    // browser is willing to hand back to its own page and mean nothing to
    // anything else making the same request directly.
    const origin = request.headers.get('Origin') || '';
    const referer = request.headers.get('Referer') || '';
    const originOk = origin === allowedOrigin || referer.startsWith(`${allowedOrigin}/`);
    if (!originOk) {
      return textResponse(403, 'This fetcher only answers requests from the Catalogue Manager.', allowedOrigin);
    }
    if (env.PROXY_SHARED_SECRET) {
      const key = request.headers.get('X-Ee-Lens-Proxy-Key') || '';
      if (key !== env.PROXY_SHARED_SECRET) {
        return textResponse(403, 'Missing or wrong proxy key.', allowedOrigin);
      }
    }

    const url = new URL(request.url);
    const target = url.searchParams.get('url') || '';
    const refusal = refusalFor(target);
    if (refusal) return textResponse(400, refusal, allowedOrigin);

    return url.pathname === '/image'
      ? handleImage(target, allowedOrigin)
      : handlePage(target, allowedOrigin);
  }
};
