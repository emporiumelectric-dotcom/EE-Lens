/*
 * The portable .eelens catalogue package.
 *
 *   manifest.json           format version, counts, integrity
 *   products.json           product records, each listing its photos
 *   photos/<productId>/<photoId>.jpg
 *
 * Fingerprints are deliberately NOT exported. They only mean anything for the
 * exact recognition model that produced them, so the phone regenerates them
 * after import rather than inheriting numbers that may silently be from an
 * older model.
 */


const FORMAT = 'eelens';
const FORMAT_VERSION = 2;   // 2 added mrpMinor alongside priceMinor
const TOOL = 'EE Lens PC Catalogue Manager';

const encoder = new TextEncoder();
const decoder = new TextDecoder();

function photoPath(productId, photoId) {
  return `photos/${productId}/${photoId}.jpg`;
}

/**
 * @param {object[]} products product rows
 * @param {object[]} photos photo rows including their blobs
 * @returns {Promise<Blob>} the .eelens package
 */
async function exportPackage(products, photos) {
  const byProduct = new Map();
  for (const photo of photos) {
    if (!byProduct.has(photo.productId)) byProduct.set(photo.productId, []);
    byProduct.get(photo.productId).push(photo);
  }

  const entries = [];
  const photoHashes = {};
  let photoCount = 0;

  const exported = [];
  for (const product of products) {
    const own = (byProduct.get(product.id) || []).slice().sort((a, b) => a.sortOrder - b.sortOrder);
    const records = [];
    for (const photo of own) {
      const bytes = new Uint8Array(await photo.blob.arrayBuffer());
      const path = photoPath(product.id, photo.id);
      entries.push({ name: path, data: bytes });
      photoHashes[path] = photo.sha256 || (await sha256Hex(bytes));
      photoCount++;
      records.push({
        id: photo.id,
        fileName: `${photo.id}.jpg`,
        sha256: photoHashes[path],
        width: photo.width,
        height: photo.height,
        bytes: bytes.length,
        sortOrder: photo.sortOrder,
        origin: photo.origin || 'file',
        role: photo.role === ROLE_CATALOGUE ? ROLE_CATALOGUE : ROLE_SHOP
      });
    }

    exported.push({
      id: product.id,
      slug: product.slug || '',
      brand: product.brand || '',
      name: product.name || '',
      model: product.model || '',
      category: product.category || null,
      colour: product.colour || null,
      sizeSweepMm: product.sizeSweepMm ?? null,
      priceMinor: product.priceMinor ?? null,   // selling price
      mrpMinor: product.mrpMinor ?? null,       // list price it is discounted from
      currency: product.currency || 'INR',
      description: product.description || '',
      specs: product.specs || {},
      coverPhotoId: product.coverPhotoId || null,
      source: product.source || 'user',
      createdAt: product.createdAt || Date.now(),
      updatedAt: product.updatedAt || Date.now(),
      photos: records
    });
  }

  const productsJson = JSON.stringify({ products: exported }, null, 2);
  const productsBytes = encoder.encode(productsJson);

  const manifest = {
    format: FORMAT,
    formatVersion: FORMAT_VERSION,
    createdBy: { tool: TOOL, version: '1.0.0' },
    createdAt: new Date().toISOString(),
    counts: { products: exported.length, photos: photoCount },
    recognition: {
      modelId: 'mobilenet_v3_small',
      embeddingsIncluded: false,
      note: 'Fingerprints are regenerated on the phone after import.'
    },
    integrity: {
      productsSha256: await sha256Hex(productsBytes),
      photoHashes
    }
  };

  return zipStore([
    { name: 'manifest.json', data: encoder.encode(JSON.stringify(manifest, null, 2)) },
    { name: 'products.json', data: productsBytes },
    ...entries
  ]);
}

/**
 * Reads and fully validates a package before any of it is used.
 * Throws with a message meant for the owner, not a stack trace.
 */
async function readPackage(arrayBuffer) {
  const files = await unzip(arrayBuffer);

  const manifestBytes = files.get('manifest.json');
  if (!manifestBytes) throw new Error("This file isn't an EE Lens catalogue.");

  let manifest;
  try {
    manifest = JSON.parse(decoder.decode(manifestBytes));
  } catch {
    throw new Error('This catalogue file is damaged and cannot be read.');
  }
  if (manifest.format !== FORMAT) throw new Error("This file isn't an EE Lens catalogue.");
  if (typeof manifest.formatVersion !== 'number') throw new Error('This catalogue file is damaged.');
  if (manifest.formatVersion > FORMAT_VERSION) {
    throw new Error(
      'This catalogue was made by a newer version of EE Lens. Update first, then try again.'
    );
  }

  const productsBytes = files.get('products.json');
  if (!productsBytes) throw new Error('This catalogue is missing its product list.');

  const expected = manifest.integrity?.productsSha256;
  if (expected && (await sha256Hex(productsBytes)) !== expected) {
    throw new Error('This catalogue is damaged: the product list does not match its checksum.');
  }

  let parsed;
  try {
    parsed = JSON.parse(decoder.decode(productsBytes));
  } catch {
    throw new Error('This catalogue is damaged: the product list could not be read.');
  }
  const products = Array.isArray(parsed.products) ? parsed.products : [];

  const photos = [];
  const missing = [];
  const corrupt = [];
  for (const product of products) {
    for (const record of product.photos || []) {
      const path = photoPath(product.id, record.id);
      const data = files.get(path);
      if (!data) { missing.push(path); continue; }

      const declared = manifest.integrity?.photoHashes?.[path] || record.sha256;
      if (declared && (await sha256Hex(data)) !== declared) { corrupt.push(path); continue; }

      photos.push({
        id: record.id,
        productId: product.id,
        blob: new Blob([data], { type: 'image/jpeg' }),
        sha256: declared || (await sha256Hex(data)),
        width: record.width || 0,
        height: record.height || 0,
        bytes: data.length,
        sortOrder: record.sortOrder ?? 0,
        origin: record.origin || 'import',
        role: record.role === ROLE_CATALOGUE ? ROLE_CATALOGUE : ROLE_SHOP
      });
    }
  }

  return { manifest, products, photos, missing, corrupt };
}

/** Suggested file name for an export. */
function suggestFileName(date = new Date()) {
  const pad = (n) => String(n).padStart(2, '0');
  return `catalogue-${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}.eelens`;
}
