/*
 * "Import from product URL".
 *
 * A fetched page is untrusted. It is parsed for values only — never executed,
 * never trusted, and never written to the catalogue on its own. Everything it
 * suggests lands in an editable preview that you approve or discard.
 *
 * DOMParser does not run scripts or load subresources, so parsing here is inert.
 */

const IMAGE_NOISE = /(logo|icon|sprite|placeholder|banner|thumb_?nail_?blank|1x1|pixel|badge|payment|footer|header)/i;
const MAX_IMAGE_CANDIDATES = 14;

/**
 * server.py's local helper only ever listens on this same PC, so it exists
 * -- and is worth trying -- only when this page was itself opened from
 * 127.0.0.1/localhost (the "EE Lens Manager.bat" workflow). Anywhere else,
 * including lens.electricemporium.in, it does not exist and never will;
 * the hosted Cloudflare Worker (CLOUD_FETCH_PROXY_URL, from config.js)
 * answers the same job there instead.
 *
 * [hostname] defaults to the real page's own -- overridable only so this is
 * unit-testable against a hostname this test server can't actually be
 * reached at; every real call site leaves it to the default.
 */
function isLocalHelperHost(hostname = location.hostname) {
  return hostname === '127.0.0.1' || hostname === 'localhost';
}

function fetchProxyEndpoint(url, hostname = location.hostname) {
  const base = isLocalHelperHost(hostname) ? '/page' : CLOUD_FETCH_PROXY_URL;
  return `${base}?url=${encodeURIComponent(url)}`;
}

/**
 * Same local-helper-vs-Worker split as fetchProxyEndpoint, for downloading an
 * image link instead of a page -- server.py answers that job on a different
 * route (/fetch) than the page one (/page), and the Worker answers both from
 * the same script, distinguished by path (see cloudflare/import-url-proxy/worker.js).
 * Used by both the candidate-image preview and the real download at approve
 * time (images.js's fetchImageUrl) -- neither a browser <img> tag nor a
 * direct fetch() can load most image hosts' links itself; both hit the same
 * CORS wall the page fetch does, just one step later in the flow.
 */
function fetchImageProxyEndpoint(url, hostname = location.hostname) {
  const base = isLocalHelperHost(hostname) ? '/fetch' : (CLOUD_FETCH_PROXY_URL ? `${CLOUD_FETCH_PROXY_URL}/image` : '');
  return `${base}?url=${encodeURIComponent(url)}`;
}

function noFetcherMessage(hostname = location.hostname) {
  return isLocalHelperHost(hostname)
    ? 'Importing a link needs the local helper. Start the manager with "EE Lens Manager.bat", or enter the product by hand.'
    : "Couldn't reach the fetch service, try again -- or enter the product by hand.";
}

/** Downloads a page through whichever fetcher answers where this is running -- see fetchProxyEndpoint. */
async function fetchProductPage(url) {
  const clean = url.trim();
  if (!/^https?:\/\//i.test(clean)) {
    throw new Error('Paste a link that starts with http:// or https://');
  }
  if (!isLocalHelperHost() && !CLOUD_FETCH_PROXY_URL) {
    throw new Error(noFetcherMessage());
  }

  let response;
  try {
    response = await fetch(fetchProxyEndpoint(clean), {
      headers: CLOUD_FETCH_PROXY_SECRET && !isLocalHelperHost()
        ? { 'X-Ee-Lens-Proxy-Key': CLOUD_FETCH_PROXY_SECRET }
        : undefined
    });
  } catch {
    throw new Error(noFetcherMessage());
  }

  if (!response.ok) {
    // Both fetchers (server.py's /page? route and the Worker) always
    // answer their own failures as plain text -- a message meant to be
    // read, never someone else's page. Anything else slipping through
    // (e.g. text/html) is a static host's own error page underneath, not
    // ours to show: lens.electricemporium.in has no backend of its own, so
    // a wrong or unreachable proxy URL 404s against GitHub Pages instead,
    // and that response body is GitHub's HTML, not a message for this box.
    const contentType = response.headers.get('content-type') || '';
    if (contentType.includes('text/plain')) throw new Error(await response.text());
    throw new Error(noFetcherMessage());
  }
  return response.json();
}

function absolute(href, base) {
  try { return new URL(href, base).href; } catch { return null; }
}

function textOf(node) {
  return (node?.textContent || '').replace(/\s+/g, ' ').trim();
}

/** Every JSON-LD block on the page that describes a Product. */
function jsonLdProducts(doc) {
  const found = [];
  for (const script of doc.querySelectorAll('script[type="application/ld+json"]')) {
    let parsed;
    try { parsed = JSON.parse(script.textContent); } catch { continue; }
    const queue = Array.isArray(parsed) ? [...parsed] : [parsed];
    while (queue.length) {
      const node = queue.shift();
      if (!node || typeof node !== 'object') continue;
      if (Array.isArray(node['@graph'])) queue.push(...node['@graph']);
      const type = node['@type'];
      const types = Array.isArray(type) ? type : [type];
      if (types.some((t) => String(t).toLowerCase() === 'product')) found.push(node);
    }
  }
  return found;
}

function meta(doc, ...names) {
  for (const name of names) {
    const el = doc.querySelector(`meta[property="${name}"], meta[name="${name}"]`);
    const value = el?.getAttribute('content')?.trim();
    if (value) return value;
  }
  return '';
}

function firstString(value) {
  if (!value) return '';
  if (typeof value === 'string') return value.trim();
  if (Array.isArray(value)) return firstString(value[0]);
  if (typeof value === 'object') return firstString(value.name || value.value || value['@id']);
  return '';
}

function collectImages(value, out) {
  if (!value) return;
  if (typeof value === 'string') { out.push(value); return; }
  if (Array.isArray(value)) { value.forEach((v) => collectImages(v, out)); return; }
  if (typeof value === 'object') collectImages(value.url || value.contentUrl, out);
}

/** Key/value rows from specification tables and definition lists. */
function extractSpecs(doc) {
  const specs = {};
  const add = (k, v) => {
    const key = k.replace(/\s+/g, ' ').trim().replace(/[:•]+$/, '');
    const value = v.replace(/\s+/g, ' ').trim();
    if (!key || !value) return;
    if (key.length > 40 || value.length > 120) return;
    if (Object.keys(specs).length >= 25) return;
    if (!(key in specs)) specs[key] = value;
  };

  for (const row of doc.querySelectorAll('tr')) {
    const cells = row.querySelectorAll('th, td');
    if (cells.length === 2) add(textOf(cells[0]), textOf(cells[1]));
  }
  for (const list of doc.querySelectorAll('dl')) {
    const terms = [...list.querySelectorAll('dt')];
    const values = [...list.querySelectorAll('dd')];
    terms.forEach((dt, i) => values[i] && add(textOf(dt), textOf(values[i])));
  }
  return specs;
}

/**
 * Reads the first usable number out of each candidate in turn.
 *
 * Extracts the first digit-led run ("3180.0" out of "Rs.3180.0"), rather
 * than stripping every non-digit character and parsing what's left: that
 * naive approach turns "Rs.3180.0" into ".3180.0" -- the abbreviation's own
 * period lands directly against the digits, and parseFloat reads that as
 * 0.318 (stopping at the second period), not 3180. Matching a real number
 * token instead means an "Rs." prefix (or "₹", "MRP:", anything else that
 * isn't itself a digit) is simply skipped, never welded onto the value.
 */
function firstNumber(...candidates) {
  for (const candidate of candidates) {
    if (candidate == null || candidate === '') continue;
    const match = String(candidate).match(/\d[\d,]*(?:\.\d+)?/);
    if (!match) continue;
    const numeric = Number.parseFloat(match[0].replace(/,/g, ''));
    if (Number.isFinite(numeric) && numeric > 0) return Math.round(numeric * 100);
  }
  return null;
}

/** What the shop charges. */
function extractPriceMinor(doc, product) {
  const offers = product?.offers;
  const offer = Array.isArray(offers) ? offers[0] : offers;
  return firstNumber(
    offer?.price,
    offer?.lowPrice,
    offer?.priceSpecification?.price,
    meta(doc, 'product:price:amount', 'og:price:amount')
  );
}

/** The list price it is discounted from, when the page states one. */
function extractMrpMinor(doc, product) {
  const offers = product?.offers;
  const offer = Array.isArray(offers) ? offers[0] : offers;
  const spec = offer?.priceSpecification;
  const list = Array.isArray(spec)
    ? spec.find((s) => /list|strikethrough|rrp|msrp/i.test(String(s?.priceType || s?.['@type'] || '')))
    : spec;
  return firstNumber(
    offer?.highPrice,
    list?.price,
    product?.mrp,
    meta(doc, 'product:original_price:amount')
  ) ?? mrpFromText(doc);
}

/**
 * Backstop for when nothing above finds an MRP -- common for real shop pages,
 * whose JSON-LD (when they publish any at all) usually states only the
 * selling price. A strikethrough element is the strongest signal a page
 * gives that a number is "the price this used to be", even on sites that
 * never spell out the word "MRP" anywhere; the labelled-text patterns catch
 * everything else. Never a guess at what MRP *should* be -- only ever reads
 * a number the page itself already presents as the original/list price.
 *
 * A real, permanent gap this cannot close: a page that shows its MRP only as
 * a struck-through price styled by an *external* stylesheet through a
 * hashed, non-semantic class name (React CSS Modules and similar -- common
 * on large sites, Flipkart included), with no semantic <del>/<s> tag, no
 * inline style, and no textual "MRP"/"list price" label anywhere, cannot be
 * told apart from any other number on the page by reading its HTML alone.
 * DOMParser never fetches or applies stylesheets, so "this text is struck
 * through" is only visible here when the page's own markup says so directly
 * -- a semantic tag, an inline style, or a class name that names itself.
 * There is no fix for that short of rendering the page in a real browser
 * (Playwright/Puppeteer) and reading computed styles, which is a materially
 * different, heavier tool than this file.
 */
function mrpFromText(doc) {
  const strikethroughSelector = [
    'del', 's', 'strike',
    '[style*="line-through" i]',
    '[class*="strike" i]', '[class*="mrp" i]', '[class*="line-through" i]',
    '[class*="list-price" i]', '[class*="old-price" i]', '[class*="was-price" i]', '[class*="original-price" i]'
  ].join(', ');
  for (const el of doc.querySelectorAll(strikethroughSelector)) {
    const found = firstNumber(textOf(el));
    if (found) return found;
  }
  const text = textOf(doc.body);
  const labelled =
    text.match(/\bm\.?\s?r\.?\s?p\.?\s*[:.]?\s*(?:rs\.?|inr|₹)\s*([\d,]+(?:\.\d+)?)/i) ||
    text.match(/\b(?:list|original)\s+price\s*[:.]?\s*(?:rs\.?|inr|₹)\s*([\d,]+(?:\.\d+)?)/i) ||
    text.match(/\bwas\s*[:.]?\s*(?:rs\.?|inr|₹)\s*([\d,]+(?:\.\d+)?)/i);
  return labelled ? firstNumber(labelled[1]) : null;
}

/* ---------- site adapters ---------- */

/**
 * Atomberg publishes almost nothing useful in JSON-LD — no SKU, no price, no
 * category, one image — but embeds a full GraphQL result in the page as Next.js
 * flight data. That payload is the only trustworthy source here.
 *
 * The visible page is actively misleading: the ₹ amounts belong to coupons and
 * offers, "SKU" matches an SVG attribute, and the first `sku` in the payload
 * belongs to a customer review. Everything below is read from the product node.
 */
function atombergPayload(html) {
  const chunks = [...html.matchAll(/self\.__next_f\.push\(\[1,"(.*?)"\]\)/gs)].map((m) => m[1]);
  if (chunks.length === 0) return null;
  // Each chunk is already the body of a JSON string, so its quotes are escaped;
  // re-escaping them would break the parse. Join first, unescape once.
  const joined = chunks.join('');
  try {
    return JSON.parse(`"${joined}"`);
  } catch {
    // One malformed chunk should not lose the whole payload.
    let out = '';
    for (const chunk of chunks) {
      try { out += JSON.parse(`"${chunk}"`); } catch { /* skip this chunk */ }
    }
    return out || null;
  }
}

/** Balanced-brace slice starting at the `{` or `[` following `key`. */
function sliceValue(text, key, from = 0) {
  const at = text.indexOf(`"${key}"`, from);
  if (at < 0) return null;
  const open = text.indexOf(text[text.indexOf(':', at) + 1] === '[' ? '[' : '{', at);
  if (open < 0) return null;
  const closer = text[open] === '[' ? ']' : '}';
  let depth = 0;
  for (let i = open; i < text.length; i++) {
    if (text[i] === text[open]) depth++;
    else if (text[i] === closer) { depth--; if (depth === 0) return text.slice(open, i + 1); }
  }
  return null;
}

/**
 * Each sellable variant, with its own code, colour, size, prices and gallery.
 *
 * A configurable product is not one thing: the colours here carry different
 * MRPs. Importing "the product" would mean picking one arbitrarily, so the
 * variants are returned and you choose which SKU to bring in.
 */
function atombergVariants(payload, prefix) {
  const slice = sliceValue(payload, 'variants');
  if (!slice) return [];
  let raw;
  try { raw = JSON.parse(slice); } catch { return []; }
  if (!Array.isArray(raw)) return [];

  return raw.map((entry) => {
    const attributes = {};
    for (const a of entry.attributes || []) if (a?.code) attributes[a.code] = a.label;
    const product = entry.product || {};
    const price = product.price_range?.minimum_price || {};

    const sizeLabel = attributes.sweepsize || attributes.size || '';
    const sizeMm = Number.parseInt(String(sizeLabel).replace(/[^\d]/g, ''), 10);

    const gallery = (product.media_gallery_entries || [])
      .filter((e) => e && e.file && e.disabled !== true)
      .sort((a, b) => (a.position ?? 0) - (b.position ?? 0))
      .map((e) => prefix + e.file);

    return {
      sku: product.sku || '',
      name: product.name || '',
      colour: attributes.color || attributes.colour || '',
      sizeSweepMm: Number.isFinite(sizeMm) && sizeMm > 0 ? sizeMm : null,
      mrpMinor: money(price.regular_price?.value),
      priceMinor: money(price.final_price?.value),
      category: product.primary_category || '',
      images: gallery,
      label: [attributes.color, sizeLabel].filter(Boolean).join(' · ') || product.sku || 'Variant'
    };
  }).filter((v) => v.sku);
}

const money = (value) =>
  Number.isFinite(Number(value)) && Number(value) > 0 ? Math.round(Number(value) * 100) : null;

function parseAtomberg(doc, payload, finalUrl) {
  const ld = jsonLdProducts(doc)[0] || null;

  // Real SKUs are catalogue codes; the review's "sku" is a product title.
  const skus = [...new Set([...payload.matchAll(/"sku"\s*:\s*"([A-Z]{2}\d{3,6})"/g)].map((m) => m[1]))];

  const category = (payload.match(/"categories"\s*:\s*\[\s*\{\s*"name"\s*:\s*"([^"]+)"/) || [])[1] || '';

  // A configurable product publishes one gallery per variant — 1200/900 mm in
  // several colours here. They all belong to this product, so take every
  // gallery in the payload, in publication order, and drop repeats. Picking a
  // single gallery would arbitrarily lose the other colours.
  const files = [];
  let searchFrom = 0;
  let galleries = 0;
  while (true) {
    const slice = sliceValue(payload, 'media_gallery_entries', searchFrom);
    if (!slice) break;
    searchFrom = payload.indexOf(slice, searchFrom) + slice.length;
    let entries;
    try { entries = JSON.parse(slice); } catch { continue; }
    if (!Array.isArray(entries)) continue;
    galleries++;
    entries
      .filter((e) => e && e.file && e.disabled !== true)
      .sort((a, b) => (a.position ?? 0) - (b.position ?? 0))
      .forEach((e) => files.push(e.file));
  }

  // Every gallery file is relative; the cache prefix appears on any real image.
  const prefix = (html_prefix(payload) || '').replace(/\/$/, '');
  const images = [...new Set(files)]
    .map((file) => (prefix ? prefix + file : null))
    .filter(Boolean);

  const specsRaw = sliceValue(payload, 'tech_specs_attributes');
  const specs = {};
  if (specsRaw) {
    try {
      for (const item of JSON.parse(specsRaw)) {
        const key = item?.label || item?.attribute_label || item?.code;
        const value = item?.value || item?.attribute_value;
        if (key && value && String(value).length < 120) specs[String(key).trim()] = String(value).trim();
      }
    } catch { /* leave specs blank rather than guess */ }
  }

  const variants = atombergVariants(payload, prefix);
  const chosen = variants[0] || null;

  return {
    sourceUrl: finalUrl,
    hadStructuredData: true,
    adapter: 'atomberg',
    brand: firstString(ld?.brand) || 'Atomberg',
    // Variant fields win: they describe the SKU actually being imported.
    name: chosen?.name || firstString(ld?.name) || '',
    model: chosen?.sku || skus.join(', '),
    // Same reasoning as the generic parser above: a pattern match against a
    // known category wins over either raw value the payload offers, both of
    // which are the store's own internal taxonomy strings, not necessarily
    // this shop's list.
    category:
      guessCategory(`${chosen?.category || ''} ${category} ${chosen?.name || ''} ${finalUrl}`) ||
      chosen?.category ||
      category,
    colour: chosen?.colour || '',
    description: cleanDescription(firstString(ld?.description) || '', chosen?.name || firstString(ld?.name) || ''),
    priceMinor: chosen?.priceMinor ?? null,
    mrpMinor: chosen?.mrpMinor ?? null,
    sizeSweepMm: chosen?.sizeSweepMm ?? null,
    specs,
    images: chosen?.images?.length ? chosen.images : images,
    galleryCount: galleries,
    variants
  };
}

/** The image cache prefix this store stamps onto every catalogue image. */
function html_prefix(payload) {
  const m = payload.match(/(https:\/\/[^"']*\/media\/catalog\/product\/cache\/[a-f0-9]+)/);
  return m ? m[1] : '';
}

/**
 * Generic e-commerce SEO copy, not real product information: a templated
 * wrapper sentence ("Buy X for Rs.Y online at best price in India ... Only
 * Genuine Products. 30 Day Replacement Guarantee. Free Shipping. Cash on
 * Delivery available.") that shows up, worded almost identically, across
 * unrelated shops because it is generated by the storefront software, not
 * written by the seller. None of it describes the product itself.
 *
 * Matched whole-sentence, not as sub-phrases: these templated lines are
 * boilerplate front to back, so dropping the entire sentence is both safe
 * (never leaves a dangling connector word like a mid-sentence cut would) and
 * sufficient -- a real spec sentence never happens to open with "Buy" and
 * contain "online", or read as exactly "Free Shipping.".
 */
const BOILERPLATE_SENTENCE = [
  /^buy\b.*\bonline\b/i,
  /^only\s+genuine\s+products?\.?$/i,
  /^\d+(?:\s*\+\s*\d+)?\s*day\s+replacement\s+guarantee\.?$/i,
  /^free\s+shipping\b.*$/i,
  /^cash\s+on\s+delivery\b.*$/i,
  /^hurry,?\s+limited\s+stock\.?$/i,
  /^shop\s+now\.?$/i
];

function stripDescriptionBoilerplate(text) {
  const sentences = text.split(/(?<=[.!?])\s+/).map((s) => s.trim()).filter(Boolean);
  const kept = sentences.filter((s) => !BOILERPLATE_SENTENCE.some((pattern) => pattern.test(s)));
  return kept.join(' ').replace(/\s+/g, ' ').trim();
}

/**
 * Collapses a sentence, or the product's own name, when it appears twice in
 * a row -- the templated copy above wraps around the real description by
 * restating the product's name right where it was just stripped out of a
 * "Buy <name> ... online" lead-in, e.g. "<name> <name> comes with...". Only
 * an immediate repeat is collapsed; the same phrase recurring later (a spec
 * repeated in a bullet list, say) is left alone.
 */
function collapseAdjacentRepeats(text, name) {
  let out = text;
  if (name && name.trim().length > 3) {
    const escaped = name.trim().replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    out = out.replace(new RegExp(`(${escaped})(?:\\s+\\1)+`, 'gi'), '$1');
  }
  const sentences = out.split(/(?<=[.!?])\s+/).map((s) => s.trim()).filter(Boolean);
  const deduped = [];
  for (const sentence of sentences) {
    const prev = deduped[deduped.length - 1];
    if (prev && prev.toLowerCase() === sentence.toLowerCase()) continue;
    deduped.push(sentence);
  }
  return deduped.join(' ').trim();
}

function cleanDescription(raw, name = '') {
  if (!raw) return '';
  return collapseAdjacentRepeats(stripDescriptionBoilerplate(raw), name);
}

/**
 * Reads whatever the page is willing to say about the product.
 *
 * A field that cannot be read reliably is returned empty. A blank you fill in
 * is a small chore; a confident wrong value that you save without noticing is
 * a corrupt catalogue entry.
 */
function extractProduct(html, finalUrl) {
  const doc = new DOMParser().parseFromString(html, 'text/html');

  if (/(^|\.)atomberg\.com$/i.test(new URL(finalUrl).hostname)) {
    const payload = atombergPayload(html);
    if (payload) return parseAtomberg(doc, payload, finalUrl);
  }

  const product = jsonLdProducts(doc)[0] || null;

  // og:title and <title> are SEO headlines ("Buy Genuine … Online In India"),
  // not product names, so they are not used as a fallback.
  const name = firstString(product?.name) || textOf(doc.querySelector('h1')) || '';

  // og:site_name is the shop, never the manufacturer.
  const brand = firstString(product?.brand) || '';

  const description = cleanDescription(
    firstString(product?.description) ||
      meta(doc, 'og:description', 'description', 'twitter:description') || '',
    name
  );

  const model = firstString(product?.model) || firstString(product?.mpn) || firstString(product?.sku) || '';
  const colour = firstString(product?.color) || '';
  const category = firstString(product?.category) || '';

  const rawImages = [];
  collectImages(product?.image, rawImages);
  const og = meta(doc, 'og:image', 'twitter:image');
  if (og) rawImages.push(og);
  for (const img of doc.querySelectorAll('img')) {
    const src = img.getAttribute('src') || img.getAttribute('data-src') || img.getAttribute('data-lazy-src');
    if (src) rawImages.push(src);
    const srcset = img.getAttribute('srcset');
    if (srcset) {
      const best = srcset.split(',').map((s) => s.trim().split(/\s+/)[0]).filter(Boolean).pop();
      if (best) rawImages.push(best);
    }
  }

  const seen = new Set();
  const images = [];
  for (const raw of rawImages) {
    const url = absolute(raw, finalUrl);
    if (!url || seen.has(url)) continue;
    if (/\.svg(\?|$)/i.test(url)) continue;
    if (IMAGE_NOISE.test(url)) continue;
    seen.add(url);
    images.push(url);
    if (images.length >= MAX_IMAGE_CANDIDATES) break;
  }

  const specs = { ...extractSpecs(doc) };
  // A pattern match wins over the raw structured-data value: guessCategory
  // only ever returns a name from the shop's own standard list, while the
  // page's own "category" field is frequently an internal slug
  // ("exhaust_fan") that reads as a different shelf from "Exhaust Fans" the
  // moment it lands in the catalogue. Only fall back to that raw value when
  // nothing here matches it (or the name/URL) at all -- the review screen
  // then shows "-- none --" rather than ever offering it as free text.
  const guessed = guessCategory(`${category} ${name} ${finalUrl}`) || category;
  const sizeMatch = `${name} ${Object.values(specs).join(' ')}`.match(/(\d{3,4})\s?mm\b/i);

  return {
    sourceUrl: finalUrl,
    hadStructuredData: Boolean(product),
    adapter: 'generic',
    brand,
    name,
    model,
    category: guessed,
    colour,
    description,
    priceMinor: extractPriceMinor(doc, product),
    mrpMinor: extractMrpMinor(doc, product),
    sizeSweepMm: sizeMatch ? Number.parseInt(sizeMatch[1], 10) : null,
    specs,
    images,
    variants: []
  };
}

/**
 * Works out which shelf a product belongs on from its own name and link.
 *
 * Order matters: "wall fan" must be checked before plain "fan", and "water
 * heater" before "heater", or the broader word wins and the guess is wrong.
 *
 * The separator is "[\s_-]?", not just a space: a raw category straight off
 * a site's own structured data is often a slug ("exhaust_fan", "wall-fan"),
 * not the words a shopper would type, and this still needs to recognise it.
 */
const CATEGORY_HINTS = [
  [/wall[\s_-]?fan/i, 'Wall Fans'],
  [/pedestal[\s_-]?fan|stand[\s_-]?fan/i, 'Pedestal Fans'],
  [/exhaust[\s_-]?fan|ventilat/i, 'Exhaust Fans'],
  [/ceiling[\s_-]?fan|\bfan\b/i, 'Fans'],
  [/mixer|grinder|juicer/i, 'Mixers'],
  [/geyser|water[\s_-]?heater/i, 'Geysers'],
  [/immersion/i, 'Immersion Rod Heaters'],
  [/induction/i, 'Induction Cooktops'],
  [/kettle/i, 'Kettles'],
  [/\biron\b|steam[\s_-]?iron/i, 'Irons'],
  [/stabili[sz]er/i, 'Stabilisers'],
  [/distribution[\s_-]?(board|box)|\bmcb\b|\brccb\b/i, 'Distribution Box'],
  [/flood[\s_-]?light|street[\s_-]?light|outdoor|garden[\s_-]?light/i, 'Outdoor LEDs'],
  [/chandelier|fancy|decorative[\s_-]?lamp|pendant/i, 'Fancy Lamps'],
  [/\bwire\b|cable|flexible/i, 'Wires/Cables'],
  [/switch|socket|modular|plate/i, 'Switches'],
  [/air[\s_-]?condition|\bac\b|split[\s_-]?ac|window[\s_-]?ac/i, 'AC'],
  [/bulb|batten|panel|downlight|\bled\b|tube[\s_-]?light|lamp/i, 'LEDs']
];

function guessCategory(text) {
  for (const [pattern, category] of CATEGORY_HINTS) {
    if (pattern.test(text)) return category;
  }
  return '';
}

/**
 * Pulls the obvious numbers out of a product's own title and description.
 *
 * A mixer page that never publishes a specification table still says
 * "Power Hunk 800 W Mixer Grinder" in its name — that is the manufacturer's own
 * wording, not a guess. Anything found here lands in the editable preview, and
 * an existing value is never overwritten.
 */
function readSpecsFromText(candidate) {
  const text = `${candidate.name} ${candidate.description}`;
  const found = {};
  const take = (key, pattern, format = (m) => m[1]) => {
    if (candidate.specs[key]) return;
    const match = text.match(pattern);
    if (match) found[key] = format(match);
  };

  take('Wattage', /(\d{1,5})\s?(?:W\b|watt)/i, (m) => `${m[1]} W`);
  take('Capacity', /(\d{1,3}(?:\.\d)?)\s?(?:L\b|litre|liter)/i, (m) => `${m[1]} L`);
  take('Number of jars', /(\d)\s?jars?\b/i, (m) => m[1]);
  take('Speeds', /(\d)\s?speed/i, (m) => m[1]);
  take('Warranty', /(\d{1,2})\s?(?:\+\s?\d)?\s?year/i, (m) => `${m[1]} years`);
  take('Sweep', /(\d{3,4})\s?mm\b/i, (m) => `${m[1]} mm`);
  take('Lumens', /(\d{3,6})\s?(?:lm\b|lumens)/i, (m) => `${m[1]} lm`);
  take('Rating', /(\d{1,2})\s?(?:A\b|amp)/i, (m) => `${m[1]} A`);
  take('Star rating', /(\d)\s?star/i, (m) => `${m[1]} star`);
  take('Speed', /(\d{3,4})\s?(?:RPM|rpm)/i, (m) => `${m[1]} RPM`);

  return found;
}

/**
 * Discount is always derived, never stored — so it cannot drift out of step
 * with the two prices it comes from. Returns null unless both are present and
 * the MRP is genuinely higher.
 */
function discountPercent(mrpMinor, priceMinor) {
  if (!mrpMinor || !priceMinor || mrpMinor <= priceMinor) return null;
  return Math.round(((mrpMinor - priceMinor) / mrpMinor) * 100);
}
