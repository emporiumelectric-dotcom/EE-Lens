// Temporary diagnostic script -- NOT part of the shipped fetcher. Fetches
// the exact reported Havells URL with the same headers
// cloudflare/import-url-proxy/worker.js's handlePage/handleImage use, and
// inspects what "Import from product URL" would actually see: the raw page
// HTML's image markup (mirroring import-url.js's own collectImages/
// extractProduct logic closely enough to reproduce its candidate list), and
// separately what happens trying to download each candidate image URL with
// the Worker's own /image headers. This file and its throwaway workflow are
// removed once the investigation concludes -- see the commit this shipped
// in for what was actually found.
//
// This sandbox's own network egress is blocked for arbitrary domains
// (havells.com included), so this runs on GitHub's hosted runners instead,
// via .github/workflows/debug-import-fetch.yml -- the same reason
// android-build.yml exists for the Kotlin side.

const PAGE_URL = process.argv[2] || 'https://havells.com/fans/ceiling-fans/inveno-lx-bldc-ceiling-fan.html?color=7399';
const USER_AGENT = 'Mozilla/5.0 (EE Lens Catalogue Manager)';

function log(label, value) {
  console.log(`\n=== ${label} ===`);
  console.log(typeof value === 'string' ? value : JSON.stringify(value, null, 2));
}

const IMAGE_NOISE = /(logo|icon|sprite|placeholder|banner|thumb_?nail_?blank|1x1|pixel|badge|payment|footer|header)/i;
const MAX_IMAGE_CANDIDATES = 14;

function absolute(href, base) {
  try { return new URL(href, base).href; } catch { return null; }
}

// Close re-implementation of import-url.js's extractProduct image-collecting
// logic against raw HTML via regex (no DOMParser available in plain Node) --
// same attribute priority (src, then data-src, then data-lazy-src), same
// srcset handling, same noise/svg filters, same MAX_IMAGE_CANDIDATES cap.
function collectCandidateImages(html, baseUrl) {
  const raw = [];

  const metaImg = html.match(/<meta[^>]+(?:property|name)=["'](?:og:image|twitter:image)["'][^>]+content=["']([^"']+)["']/i);
  if (metaImg) raw.push(metaImg[1]);

  const ldBlocks = [...html.matchAll(/<script[^>]+type=["']application\/ld\+json["'][^>]*>([\s\S]*?)<\/script>/gi)];
  for (const block of ldBlocks) {
    try {
      const parsed = JSON.parse(block[1]);
      const nodes = Array.isArray(parsed) ? parsed : [parsed];
      for (const node of nodes) {
        if (node && node.image) {
          const imgs = Array.isArray(node.image) ? node.image : [node.image];
          for (const im of imgs) raw.push(typeof im === 'string' ? im : im?.url || im?.contentUrl);
        }
      }
    } catch { /* ignore malformed JSON-LD, same as jsonLdProducts does */ }
  }

  const imgTags = [...html.matchAll(/<img\b[^>]*>/gi)];
  for (const tagMatch of imgTags) {
    const tag = tagMatch[0];
    const attr = (name) => {
      const m = tag.match(new RegExp(`${name}=["']([^"']*)["']`, 'i'));
      return m ? m[1] : null;
    };
    const src = attr('src') || attr('data-src') || attr('data-lazy-src');
    if (src) raw.push(src);
    const srcset = attr('srcset');
    if (srcset) {
      const best = srcset.split(',').map((s) => s.trim().split(/\s+/)[0]).filter(Boolean).pop();
      if (best) raw.push(best);
    }
  }

  const seen = new Set();
  const images = [];
  for (const r of raw) {
    if (!r) continue;
    const url = absolute(r, baseUrl);
    if (!url || seen.has(url)) continue;
    if (/\.svg(\?|$)/i.test(url)) continue;
    if (IMAGE_NOISE.test(url)) continue;
    seen.add(url);
    images.push(url);
    if (images.length >= MAX_IMAGE_CANDIDATES) break;
  }
  return { images, imgTagCount: imgTags.length, rawCount: raw.length };
}

async function main() {
  console.log(`Fetching page: ${PAGE_URL}`);
  const pageResp = await fetch(PAGE_URL, {
    headers: {
      'User-Agent': USER_AGENT,
      Accept: 'text/html,application/xhtml+xml,*/*;q=0.8',
      'Accept-Language': 'en-IN,en;q=0.9'
    }
  });
  log('Page fetch status', `${pageResp.status} ${pageResp.statusText}`);
  log('Page final URL', pageResp.url);
  log('Page content-type', pageResp.headers.get('content-type'));
  const html = await pageResp.text();
  log('Page HTML length (bytes)', html.length);

  const challengeMarkers = [/cdn-cgi\/challenge-platform/i, /<title>\s*just a moment/i, /enable javascript and cookies to continue/i];
  log('Looks like Cloudflare challenge?', challengeMarkers.some((p) => p.test(html)));

  const { images, imgTagCount, rawCount } = collectCandidateImages(html, pageResp.url);
  log('<img> tag count in raw HTML', imgTagCount);
  log('raw candidate URLs before dedup/filter', rawCount);
  log('Final candidate images (what extractProduct would produce)', images);

  const firstImgTags = [...html.matchAll(/<img\b[^>]*>/gi)].slice(0, 25).map((m) => m[0]);
  log('First 25 raw <img> tags (verbatim)', firstImgTags);

  const noscriptBlocks = [...html.matchAll(/<noscript>([\s\S]*?)<\/noscript>/gi)].slice(0, 5).map((m) => m[1].slice(0, 300));
  log('First 5 <noscript> blocks (truncated 300 chars)', noscriptBlocks);

  for (const url of images) {
    try {
      const resp = await fetch(url, {
        headers: { 'User-Agent': USER_AGENT, Accept: 'image/*,*/*;q=0.8' },
        redirect: 'follow'
      });
      console.log(`\nIMAGE ${url}\n  -> status=${resp.status} content-type=${resp.headers.get('content-type')} finalUrl=${resp.url}`);
    } catch (err) {
      console.log(`\nIMAGE ${url}\n  -> FETCH ERROR: ${err.message}`);
    }
  }
}

main().catch((err) => {
  console.error('FAILED:', err);
  process.exit(1);
});
