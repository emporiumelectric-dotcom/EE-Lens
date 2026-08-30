// Temporary diagnostic script -- NOT part of the shipped fetcher. Follow-up
// to the tracking-pixel/promo-banner fix (see cloud.js/import-url.js git
// history): the real reported gap now is that the product's own 9-image
// detail carousel is completely missing from "Images found" -- only the
// unrelated colour-swatch thumbnails further down the page show up.
//
// Prior investigation already confirmed this page is Adobe Commerce/
// Magento (x-magento-init script blocks, "mage/gallery/gallery" widget
// mentioned). Magento's gallery widget is normally initialised from a JSON
// config embedded directly in the page's own HTML (inside an
// x-magento-init <script> block) -- the widget needs JS only to RENDER
// that data into the DOM, not to have the data exist in the first place.
// This script checks whether that's true here: if the real 9 image URLs
// are sitting in the raw HTML as JSON, this is a genuinely fixable parsing
// gap (same class of fix as the existing Atomberg adapter); if they are
// only ever fetched by a separate JS-triggered network call, it's a real,
// documented "needs a headless browser" limitation.
//
// This sandbox's own network egress is blocked for arbitrary domains
// (havells.com included), so this runs on GitHub's hosted runners instead,
// via .github/workflows/debug-havells-gallery.yml.

const PAGE_URL = process.argv[2] || 'https://havells.com/fans/ceiling-fans/inveno-lx-bldc-ceiling-fan.html?color=7399';
const USER_AGENT = 'Mozilla/5.0 (EE Lens Catalogue Manager)';

function log(label, value) {
  console.log(`\n=== ${label} ===`);
  console.log(typeof value === 'string' ? value : JSON.stringify(value, null, 2));
}

async function fetchWithTimeout(url, options, timeoutMs = 20_000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...options, signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
}

/** Balanced-brace slice starting at the { or [ following the first match of `key` at/after `from`. */
function sliceValue(text, key, from = 0) {
  const at = text.indexOf(key, from);
  if (at < 0) return null;
  const openBrace = text.indexOf('{', at);
  const openBracket = text.indexOf('[', at);
  const open = openBracket >= 0 && (openBrace < 0 || openBracket < openBrace) ? openBracket : openBrace;
  if (open < 0) return null;
  const closer = text[open] === '[' ? ']' : '}';
  let depth = 0;
  for (let i = open; i < text.length; i++) {
    if (text[i] === text[open]) depth++;
    else if (text[i] === closer) { depth--; if (depth === 0) return { slice: text.slice(open, i + 1), end: i + 1 }; }
  }
  return null;
}

async function main() {
  console.log(`Fetching page: ${PAGE_URL}`);
  const pageResp = await fetchWithTimeout(PAGE_URL, {
    headers: {
      'User-Agent': USER_AGENT,
      Accept: 'text/html,application/xhtml+xml,*/*;q=0.8',
      'Accept-Language': 'en-IN,en;q=0.9'
    }
  });
  const html = await pageResp.text();
  log('status', `${pageResp.status} ${pageResp.statusText}`);
  log('HTML length (bytes)', html.length);

  // Every x-magento-init script block: dump each one's target selector and
  // a size-capped preview, so we can see which one (if any) configures the
  // gallery widget and what shape its data takes.
  const initBlocks = [...html.matchAll(/<script[^>]+type=["']text\/x-magento-init["'][^>]*>([\s\S]*?)<\/script>/gi)].map((m) => m[1]);
  log(`x-magento-init blocks found`, initBlocks.length);
  const galleryBlocks = initBlocks.filter((b) => b.includes('gallery'));
  log(`x-magento-init blocks mentioning "gallery"`, galleryBlocks.length);
  galleryBlocks.forEach((b, i) => log(`gallery-mentioning x-magento-init block #${i} (first 3000 chars)`, b.slice(0, 3000)));

  // Also try locating "mage/gallery/gallery" directly and slicing out its
  // "data" array (the standard shape: {"[data-gallery-role=...]": {"mage/gallery/gallery": {"data": [...]}}}).
  const galleryKeyIdx = html.indexOf('mage/gallery/gallery');
  log('"mage/gallery/gallery" found at index', galleryKeyIdx);
  if (galleryKeyIdx >= 0) {
    const dataSlice = sliceValue(html, '"data"', galleryKeyIdx);
    if (dataSlice) {
      log('Raw "data" array slice near mage/gallery/gallery (first 4000 chars)', dataSlice.slice.slice(0, 4000));
      try {
        const parsed = JSON.parse(dataSlice.slice);
        log(`Parsed gallery data array: ${Array.isArray(parsed) ? parsed.length : 'not an array'} entries`, parsed);
      } catch (err) {
        log('Failed to JSON.parse the data slice', err.message);
      }
    } else {
      log('No "data" key found near mage/gallery/gallery within search window', null);
      log('2000 raw chars starting at mage/gallery/gallery', html.slice(galleryKeyIdx, galleryKeyIdx + 2000));
    }
  }

  // Broader net: any JSON-looking blob anywhere in the page mentioning this
  // product's own SKU family (fhcil5s) together with "img" or "full" or
  // "thumb" keys, the standard Magento gallery entry shape
  // ({"img":"...","thumb":"...","full":"...","caption":"..."}).
  const entryMatches = [...html.matchAll(/\{[^{}]{0,400}"(?:img|full|thumb)"\s*:\s*"[^"]*fhcil5s[^"]*"[^{}]{0,400}\}/gi)];
  log(`Gallery-entry-shaped JSON objects mentioning this product's own SKU (fhcil5s)`, entryMatches.length);
  entryMatches.slice(0, 12).forEach((m, i) => log(`entry #${i}`, m[0]));

  // And: how many distinct image filenames matching this SKU family appear
  // ANYWHERE in the raw HTML at all (repeats collapsed) -- ceiling on what
  // could possibly be recovered from static HTML alone, regardless of shape.
  const skuFiles = [...new Set([...html.matchAll(/([a-z0-9_]*fhcil5s[a-z0-9_]*\.(?:jpg|jpeg|png|webp))/gi)].map((m) => m[1].toLowerCase()))];
  log(`Distinct SKU-family filenames anywhere in raw HTML (${skuFiles.length})`, skuFiles);

  // Where do the colour-swatch thumbnails actually live -- what's near one,
  // to compare its markup shape against the (so far unlocated) real gallery.
  const swatchIdx = html.search(/swatch/i);
  if (swatchIdx >= 0) {
    log('600 chars around first "swatch" mention', html.slice(Math.max(0, swatchIdx - 200), swatchIdx + 600));
  }

  // The visible page shows a "1/9" counter -- what number does the RAW HTML
  // itself show right after gallery-placeholder (already found once before:
  // "current">1</span> / <span class="count">2</span>" for a 2-image case)?
  const pagCountMatch = html.match(/class="current">(\d+)<\/span>[\s\S]{0,80}?class="count">(\d+)<\/span>/);
  log('Pagination counter found in raw HTML (current/count)', pagCountMatch ? { current: pagCountMatch[1], count: pagCountMatch[2] } : '(not found)');
}

main().catch((err) => {
  console.error('FAILED:', err);
  process.exit(1);
});
