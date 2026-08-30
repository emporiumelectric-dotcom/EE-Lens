// Temporary diagnostic script -- NOT part of the shipped fetcher. Follow-up
// to PR #26 (magentoGalleryImages): a real regression report against a
// DIFFERENT real Havells product page (no ?color= query param, unlike the
// Inveno LX page PR #26 was built and tested against) says the import
// fetched 14 images that are ALL a completely different fan model.
//
// This inspects: how many x-magento-init blocks on THIS page match the
// "[data-role=swatch-options]" + "Magento_Swatches/js/swatch-renderer"
// predicate magentoGalleryImages() uses (.find() takes the FIRST one --
// if more than one block matches, that's the exact bug), what product
// id(s)/SKUs each one's own jsonConfig.images actually contains, and
// whether any of those SKUs match this URL's own model code (fhclae4sbw52)
// versus the reported wrong fan.
//
// This sandbox's own network egress is blocked for arbitrary domains
// (havells.com included), so this runs on GitHub's hosted runners instead,
// via .github/workflows/debug-florette-gallery.yml.

const PAGE_URL = process.argv[2] || 'https://havells.com/fans/ceiling-fans/florette-ul-bldc-smartsense-ceiling-fan-fhclae4sbw52.html';
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
  log('final URL', pageResp.url);
  log('HTML length (bytes)', html.length);

  const initBlocks = [...html.matchAll(/<script[^>]+type=["']text\/x-magento-init["'][^>]*>([\s\S]*?)<\/script>/gi)].map((m) => m[1]);
  log('x-magento-init blocks found', initBlocks.length);

  const swatchOptionsBlocks = initBlocks.filter(
    (b) => b.includes('[data-role=swatch-options]') && b.includes('Magento_Swatches/js/swatch-renderer')
  );
  log('Blocks matching magentoGalleryImages\' own predicate ([data-role=swatch-options] + swatch-renderer)', swatchOptionsBlocks.length);

  swatchOptionsBlocks.forEach((b, i) => {
    let parsed;
    try {
      parsed = JSON.parse(b);
    } catch (err) {
      log(`Block #${i}: FAILED to JSON.parse`, err.message);
      return;
    }
    const config = parsed['[data-role=swatch-options]']?.['Magento_Swatches/js/swatch-renderer']?.jsonConfig;
    if (!config) {
      log(`Block #${i}: no jsonConfig at the expected path`, Object.keys(parsed));
      return;
    }
    const productIds = Object.keys(config.images || {});
    log(`Block #${i}: productId -> first image filename`, productIds.map((id) => ({
      productId: id,
      firstImg: config.images[id]?.[0]?.img || config.images[id]?.[0]?.full || null
    })));
    log(`Block #${i}: attributes summary`, Object.values(config.attributes || {}).map((a) => ({
      code: a.code, label: a.label, options: (a.options || []).map((o) => ({ id: o.id, label: o.label, products: o.products }))
    })));
  });

  // The product's own model code is in the URL itself (fhclae4sbw52) --
  // does ANY image filename anywhere in the raw HTML match that family?
  const modelMatch = PAGE_URL.match(/-([a-z0-9]{8,})\.html/i);
  const modelCode = modelMatch ? modelMatch[1].toLowerCase() : null;
  log('Model code parsed from the URL itself', modelCode);
  if (modelCode) {
    const familyPrefix = modelCode.slice(0, 7); // e.g. "fhclae4" -- drop the trailing size/color suffix, same idea as the Inveno LX investigation's "fhcil5s" family match
    const matches = [...new Set([...html.matchAll(new RegExp(`([a-z0-9_]*${familyPrefix}[a-z0-9_]*\\.(?:jpg|jpeg|png|webp))`, 'gi'))].map((m) => m[1].toLowerCase()))];
    log(`Distinct filenames anywhere in raw HTML matching this product's own family (${familyPrefix})`, matches);
  }

  // Also: what does the add-to-cart form's own data-product-sku say the
  // CURRENTLY VIEWED product's real SKU is -- ground truth independent of
  // any gallery JSON, to check against the SKUs found above.
  const skuMatch = html.match(/data-product-sku=["']([^"']+)["']/i);
  log('data-product-sku on the add-to-cart form (ground truth for which product this page is)', skuMatch ? skuMatch[1] : '(not found)');

  const h1Match = html.match(/<h1[^>]*>([\s\S]{0,200}?)<\/h1>/i);
  log('<h1> text', h1Match ? h1Match[1].replace(/<[^>]+>/g, '').trim() : '(not found)');
}

main().catch((err) => {
  console.error('FAILED:', err);
  process.exit(1);
});
