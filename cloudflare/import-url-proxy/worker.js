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
 * Restricted to requests that plausibly come from the Catalogue Manager
 * itself: an Origin/Referer check against ALLOWED_ORIGIN, plus an optional
 * shared secret (env.PROXY_SHARED_SECRET, unset by default) for extra
 * hardening once deployed. This is a reasonable bar for a small internal
 * tool -- not a substitute for a real security review before this is
 * trusted with anything beyond what it does today: reading a public page
 * and returning its markup.
 */

const DEFAULT_ALLOWED_ORIGIN = 'https://lens.electricemporium.in';
const MAX_PAGE_BYTES = 4 * 1024 * 1024; // matches server.py's own MAX_PAGE_BYTES
const FETCH_TIMEOUT_MS = 20_000;
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

    const target = new URL(request.url).searchParams.get('url') || '';
    const refusal = refusalFor(target);
    if (refusal) return textResponse(400, refusal, allowedOrigin);

    let upstream;
    try {
      upstream = await fetchWithTimeout(
        target,
        { headers: { 'User-Agent': USER_AGENT, Accept: 'text/html,application/xhtml+xml,*/*;q=0.8' } },
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
};
