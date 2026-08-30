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

// Every fetch below is timeout-guarded -- the real Worker's own
// fetchWithTimeout does this too (FETCH_TIMEOUT_MS); plain fetch() has no
// default timeout at all, so a single hung request (this run's first
// attempt hit exactly that -- cancelled after several minutes stuck on one
// fetch with no way to tell which) would otherwise stall the whole job.
async function fetchWithTimeout(url, options, timeoutMs = 20_000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...options, signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
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

// Close re-implementation of import-url.js's extractSpecs -- <tr>-with-2-
// cells and <dl> reading via regex (no DOMParser in plain Node).
function collectSpecs(html) {
  const specs = {};
  const add = (k, v) => {
    const key = k.replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim().replace(/[:•]+$/, '');
    const value = v.replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim();
    if (!key || !value) return;
    if (key.length > 40 || value.length > 120) return;
    if (Object.keys(specs).length >= 25) return;
    if (!(key in specs)) specs[key] = value;
  };
  const trBlocks = [...html.matchAll(/<tr\b[^>]*>([\s\S]*?)<\/tr>/gi)];
  for (const tr of trBlocks) {
    const cells = [...tr[1].matchAll(/<t[hd]\b[^>]*>([\s\S]*?)<\/t[hd]>/gi)];
    if (cells.length === 2) add(cells[0][1], cells[1][1]);
  }
  return { specs, trTagCount: trBlocks.length };
}

async function fetchPage(label) {
  console.log(`\nFetching page (${label}): ${PAGE_URL}`);
  const pageResp = await fetchWithTimeout(PAGE_URL, {
    headers: {
      'User-Agent': USER_AGENT,
      Accept: 'text/html,application/xhtml+xml,*/*;q=0.8',
      'Accept-Language': 'en-IN,en;q=0.9'
    }
  });
  const html = await pageResp.text();
  log(`[${label}] status`, `${pageResp.status} ${pageResp.statusText}`);
  log(`[${label}] HTML length (bytes)`, html.length);
  const { specs, trTagCount } = collectSpecs(html);
  log(`[${label}] <tr> tag count`, trTagCount);
  log(`[${label}] Specs found (Sweep/Power/Speed/Air delivery/Motor/Star rating/Warranty)`, {
    Sweep: specs.Sweep, Power: specs.Power, Speed: specs.Speed,
    'Air delivery': specs['Air delivery'], Motor: specs.Motor,
    'Star rating': specs['Star rating'], Warranty: specs.Warranty
  });
  log(`[${label}] ALL specs found`, specs);
  // Raw snippet around any "additional-attributes"/"specification" markup, to
  // see by eye whether the spec table exists in this exact HTML at all.
  const specSectionMatch = html.match(/additional-attributes[\s\S]{0,600}/i) || html.match(/product\.info\.additional[\s\S]{0,600}/i);
  log(`[${label}] raw snippet near spec table marker`, specSectionMatch ? specSectionMatch[0] : '(no "additional-attributes" or "product.info.additional" marker found in this HTML)');
  return { html, pageResp };
}

async function main() {
  // Fetch twice, back-to-back, to check whether the page's own content is
  // stable across requests (a real, plausible explanation on its own for
  // "worked earlier today, doesn't now" that has nothing to do with this
  // repo's code) before trusting a single sample either way.
  const first = await fetchPage('fetch 1');
  const second = await fetchPage('fetch 2');
  log('Both fetches returned byte-identical HTML?', first.html === second.html);

  const { html, pageResp } = first;
  log('Page HTML length (bytes)', html.length);

  const challengeMarkers = [/cdn-cgi\/challenge-platform/i, /<title>\s*just a moment/i, /enable javascript and cookies to continue/i];
  log('Looks like Cloudflare challenge?', challengeMarkers.some((p) => p.test(html)));

  const { images, imgTagCount, rawCount } = collectCandidateImages(html, pageResp.url);
  log('<img> tag count in raw HTML', imgTagCount);
  log('raw candidate URLs before dedup/filter', rawCount);
  log('Final candidate images (what extractProduct would produce)', images);

  const firstImgTags = [...html.matchAll(/<img\b[^>]*>/gi)].slice(0, 25).map((m) => m[0]);
  log('First 25 raw <img> tags (verbatim)', firstImgTags);

  // Testing the fix under consideration: how many <img> tags anywhere on the
  // page are gallery-scoped (self or an ancestor's class/id mentions
  // "gallery" or "product-image"), and what do their src values look like?
  // No real DOM/closest() available in plain Node, so this approximates
  // "ancestor" with a purely textual heuristic: an <img ...gallery...> tag
  // directly, OR one whose nearest enclosing element (last unclosed tag
  // before it, scanned backwards) mentions gallery/product-image. Good
  // enough to sanity-check the yield before writing the real DOM-based
  // version in import-url.js itself.
  const GALLERY_MARK = /gallery|product-image/i;
  const galleryScoped = [];
  for (const tagMatch of html.matchAll(/<img\b[^>]*>/gi)) {
    const tag = tagMatch[0];
    const idx = tagMatch.index;
    const selfScoped = GALLERY_MARK.test(tag);
    // Cheap approximation of "nearest enclosing element mentions gallery":
    // just check whether the word appears at all in a short window right
    // before this tag (a real ancestor-class check needs a real DOM, done
    // properly with Element.closest() in the actual import-url.js fix --
    // this is only here to sanity-check the likely yield before writing that).
    const windowBefore = html.slice(Math.max(0, idx - 800), idx);
    const ancestorScoped = !selfScoped && GALLERY_MARK.test(windowBefore);
    if (selfScoped || ancestorScoped) {
      const srcMatch = tag.match(/\bsrc=["']([^"']*)["']/i) || tag.match(/\bdata-src=["']([^"']*)["']/i);
      galleryScoped.push({ src: srcMatch ? srcMatch[1] : null, selfScoped, ancestorScoped });
    }
  }
  log(`Gallery-scoped <img> tags found (${galleryScoped.length})`, galleryScoped);

  // Narrower signal: literally "gallery-placeholder", the exact class seen
  // on the one hero photo tag in the sample above -- versus the broader
  // "gallery|product-image" match, which turned out to sweep in a LOT of
  // clearly-unrelated SKUs (a related-products carousel almost certainly
  // also styled with a class matching that broad pattern somewhere nearby).
  const NARROW_MARK = /gallery-placeholder/i;
  const narrowScoped = [];
  for (const tagMatch of html.matchAll(/<img\b[^>]*>/gi)) {
    const tag = tagMatch[0];
    const idx = tagMatch.index;
    const windowBefore = html.slice(Math.max(0, idx - 800), idx);
    if (NARROW_MARK.test(tag) || NARROW_MARK.test(windowBefore)) {
      const srcMatch = tag.match(/\bsrc=["']([^"']*)["']/i) || tag.match(/\bdata-src=["']([^"']*)["']/i);
      narrowScoped.push(srcMatch ? srcMatch[1] : null);
    }
  }
  log(`"gallery-placeholder"-scoped <img> tags found (${narrowScoped.length})`, narrowScoped);

  // How many img tags anywhere on the page reference this exact product's
  // own SKU family (fhcil5s, from the URL's own product code) -- the true
  // count of "real product photos" on this page, independent of any
  // selection heuristic, to check against the reported "9 real images".
  const skuMatches = [...new Set([...html.matchAll(/\/([a-z0-9_]*fhcil5s[a-z0-9_]*\.(?:jpg|png|webp))/gi)].map((m) => m[1]))];
  log(`Distinct filenames anywhere in the HTML matching this product's own SKU family (fhcil5s) (${skuMatches.length})`, skuMatches);

  // Do the words the user says they saw as VALUES ("1200", "230", "BLDC",
  // "5" star) appear ANYWHERE in the raw HTML at all -- in any tag shape,
  // not just a <tr>/<dl> extractSpecs already reads -- or are they genuinely
  // absent from this server-rendered page? And does Magento's own
  // x-magento-init JSON-config mechanism (the same class of thing Atomberg's
  // adapter reads from self.__next_f.push) carry the real gallery/specs data
  // instead of plain <img>/<table> tags?
  log('Literal "Air delivery" appears in raw HTML?', html.includes('Air delivery') || html.includes('Air Delivery'));
  log('Literal "BLDC" appears in raw HTML?', html.includes('BLDC'));
  log('Literal "230 m" appears in raw HTML?', html.includes('230 m') || html.includes('230m'));
  log('"x-magento-init" script blocks found', [...html.matchAll(/type=["']text\/x-magento-init["']/gi)].length);
  log('"mage/gallery/gallery" mentioned?', html.includes('mage/gallery/gallery'));
  log('"gallery-placeholder" total occurrences (any context)', (html.match(/gallery-placeholder/gi) || []).length);
  const galleryInitMatch = html.match(/gallery-placeholder["'][\s\S]{0,2000}/);
  log('2000 chars of raw HTML right after the first "gallery-placeholder" mention', galleryInitMatch ? galleryInitMatch[0] : '(not found)');
  const tabsMatch = html.match(/additional[\s\S]{0,10}information[\s\S]{0,800}/i) || html.match(/data-role=["']content["'][\s\S]{0,800}/i);
  log('Raw snippet near "additional information" / tab content marker', tabsMatch ? tabsMatch[0] : '(not found)');

  // Where do the real spec VALUES ("Air delivery", "BLDC", "230 m") actually
  // live in the markup, if not a <tr>/<dl> extractSpecs already reads?
  const airDeliveryIdx = html.search(/Air\s*delivery/i);
  log('600 chars of raw HTML BEFORE the first "Air delivery" mention', airDeliveryIdx >= 0 ? html.slice(Math.max(0, airDeliveryIdx - 600), airDeliveryIdx) : '(not found)');
  log('600 chars of raw HTML AFTER the first "Air delivery" mention', airDeliveryIdx >= 0 ? html.slice(airDeliveryIdx, airDeliveryIdx + 600) : '(not found)');

  const noscriptBlocks = [...html.matchAll(/<noscript>([\s\S]*?)<\/noscript>/gi)].slice(0, 5).map((m) => m[1].slice(0, 300));
  log('First 5 <noscript> blocks (truncated 300 chars)', noscriptBlocks);

  for (const url of images) {
    try {
      const resp = await fetchWithTimeout(url, {
        headers: { 'User-Agent': USER_AGENT, Accept: 'image/*,*/*;q=0.8' },
        redirect: 'follow'
      });
      console.log(`\nIMAGE ${url}\n  -> status=${resp.status} content-type=${resp.headers.get('content-type')} finalUrl=${resp.url}`);
    } catch (err) {
      console.log(`\nIMAGE ${url}\n  -> FETCH ERROR: ${err.name === 'AbortError' ? 'timed out' : err.message}`);
    }
  }
}

main().catch((err) => {
  console.error('FAILED:', err);
  process.exit(1);
});
