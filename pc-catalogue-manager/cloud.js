/*
 * Cloud sync: mirrors this catalogue to Supabase (ee_lens.products /
 * ee_lens.product_photos, plus the ee-lens-photos Storage bucket) so the PC
 * and the Android app can both read and write the same catalogue without
 * passing a .eelens file back and forth by hand.
 *
 * This is a manual, pull-based sync -- "Push to cloud" and "Pull from cloud"
 * -- deliberately mirroring the existing Phone-sync UX rather than pushing on
 * every keystroke. The .eelens export/import remains the full-fidelity,
 * works-with-no-internet backup; this is a lighter cloud mirror on top of it.
 *
 * Reading is open to everyone (RLS: anon can SELECT); writing needs a signed-
 * in Supabase session (RLS: authenticated only), matching supabase.js's
 * existing sign-in gate.
 *
 * Local <-> remote field mapping, because the live ee_lens schema is
 * narrower than the local one:
 *   - local id (a UUID)         <-> remote client_id (uuid, unique)
 *   - local priceMinor (paise)  <-> remote price (numeric rupees)
 *   - local sizeSweepMm (int)   <-> remote size (free text, "1200mm")
 *   - local role recognition/display <-> remote role shop/catalogue
 *   - slug/mrpMinor/currency/source have no remote column and stay local-only;
 *     .eelens keeps the full picture.
 *   - remote products.deleted_at mirrors local soft-delete.
 */

function cloudPhotoPath(productId, photoId) {
  return `${productId}/${photoId}.jpg`;
}

function localRoleToCloud(role) {
  return role === ROLE_CATALOGUE ? 'catalogue' : 'shop';
}

function cloudToLocalRole(role) {
  return role === 'catalogue' ? ROLE_CATALOGUE : ROLE_SHOP;
}

/** Pulls the leading number out of a free-text size like "1200mm". */
function parseSizeMm(size) {
  if (!size) return null;
  const match = String(size).match(/(\d+)/);
  return match ? Number(match[1]) : null;
}

function cloudLastSyncAt(direction) {
  const raw = localStorage.getItem(`ee-lens-cloud-${direction}-at`);
  return raw ? Number(raw) : null;
}

function cloudSetLastSyncAt(direction, whenMs) {
  try {
    localStorage.setItem(`ee-lens-cloud-${direction}-at`, String(whenMs));
  } catch {
    // Not essential -- the sync itself already happened.
  }
}

/** Headers for a REST call. Write requests require a signed-in session. */
async function cloudAuthHeaders(write) {
  const headers = {
    apikey: SUPABASE_ANON_KEY,
    'Accept-Profile': SUPABASE_SCHEMA,
    'Content-Profile': SUPABASE_SCHEMA
  };
  if (write) {
    await ensureFreshSession();
    if (!isSignedIn()) throw new Error('Sign in to push to the cloud.');
    headers.Authorization = `Bearer ${session.access_token}`;
  } else {
    headers.Authorization = `Bearer ${SUPABASE_ANON_KEY}`;
  }
  return headers;
}

async function cloudRest(method, table, body, { headers, query, prefer } = {}) {
  const url = `${SUPABASE_URL}/rest/v1/${table}${query ? `?${query}` : ''}`;
  const requestHeaders = { ...headers };
  if (body !== undefined) requestHeaders['Content-Type'] = 'application/json';
  if (prefer) requestHeaders.Prefer = prefer;

  const response = await fetch(url, {
    method,
    headers: requestHeaders,
    body: body !== undefined ? JSON.stringify(body) : undefined
  });
  if (!response.ok) {
    const text = await response.text().catch(() => '');
    throw new Error(`Cloud sync error (${response.status}): ${text || response.statusText}`);
  }
  if (response.status === 204) return null;
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

async function cloudUploadPhoto(productId, photo, headers) {
  const url = `${SUPABASE_URL}/storage/v1/object/${SUPABASE_PHOTOS_BUCKET}/${cloudPhotoPath(productId, photo.id)}`;
  const response = await fetch(url, {
    method: 'POST',
    headers: {
      apikey: headers.apikey,
      Authorization: headers.Authorization,
      'Content-Type': 'image/jpeg',
      'x-upsert': 'true'
    },
    body: photo.blob
  });
  if (!response.ok) {
    const text = await response.text().catch(() => '');
    throw new Error(`Photo upload failed (${response.status}): ${text || response.statusText}`);
  }
}

async function cloudDownloadPhoto(productId, photoId, headers) {
  const url = `${SUPABASE_URL}/storage/v1/object/${SUPABASE_PHOTOS_BUCKET}/${cloudPhotoPath(productId, photoId)}`;
  const response = await fetch(url, {
    headers: { apikey: headers.apikey, Authorization: headers.Authorization }
  });
  if (!response.ok) throw new Error(`Photo download failed (${response.status})`);
  return response.blob();
}

/**
 * Pushes one product and its photos. Product metadata is always upserted
 * (cheap); a photo's bytes are only re-uploaded when they have never been
 * pushed or have changed since -- the expensive part is skipped, not the
 * whole product.
 */
async function cloudPushProduct(product, headers) {
  const remoteProduct = {
    client_id: product.id,
    brand: product.brand || '',
    name: product.name || '',
    model: product.model || null,
    category: product.category || null,
    colour: product.colour || null,
    size: product.sizeSweepMm != null ? `${product.sizeSweepMm}mm` : null,
    price: product.priceMinor != null ? product.priceMinor / 100 : null,
    description: product.description || null,
    specs: product.specs || {},
    created_at: new Date(product.createdAt || Date.now()).toISOString(),
    updated_at: new Date(product.updatedAt || Date.now()).toISOString(),
    deleted_at: null
  };
  const [savedProduct] = await cloudRest('POST', 'products', remoteProduct, {
    headers,
    query: 'on_conflict=client_id',
    prefer: 'resolution=merge-duplicates,return=representation'
  });

  const photos = await photosFor(product.id);
  const remotePhotoIdByLocalId = {};
  let touched = false;
  for (const photo of photos) {
    if (photo.cloudPushedSha256 !== photo.sha256) {
      await cloudUploadPhoto(product.id, photo, headers);
      photo.cloudPushedSha256 = photo.sha256;
      touched = true;
    }
    const [savedPhoto] = await cloudRest(
      'POST',
      'product_photos',
      {
        client_id: photo.id,
        product_id: savedProduct.id,
        role: localRoleToCloud(photo.role),
        storage_path: cloudPhotoPath(product.id, photo.id),
        sort_order: photo.sortOrder,
        checksum: photo.sha256
      },
      { headers, query: 'on_conflict=client_id', prefer: 'resolution=merge-duplicates,return=representation' }
    );
    remotePhotoIdByLocalId[photo.id] = savedPhoto.id;
  }
  if (touched) await putPhotos(photos);

  const coverRemoteId = product.coverPhotoId ? (remotePhotoIdByLocalId[product.coverPhotoId] ?? null) : null;
  if (coverRemoteId !== savedProduct.cover_photo_id) {
    await cloudRest('PATCH', 'products', { cover_photo_id: coverRemoteId }, {
      headers,
      query: `id=eq.${savedProduct.id}`
    });
  }
}

/**
 * Fire-and-forget push of one product, for right after a local save. Never
 * blocks the editor and never throws -- a failure just becomes a toast, since
 * the product is already safely saved on this PC either way.
 */
function cloudBackgroundPush(product) {
  if (!isSignedIn()) return;
  (async () => {
    try {
      const headers = await cloudAuthHeaders(true);
      await cloudPushProduct(product, headers);
      cloudSetLastSyncAt('push', Date.now());
    } catch (error) {
      console.error('Cloud push failed', error);
      toast(`Saved here, but the cloud copy could not be updated: ${error.message}`, true);
    }
  })();
}

/** Marks a product deleted in the cloud. Call this at the moment of local delete. */
async function cloudDeleteProduct(productId) {
  try {
    const headers = await cloudAuthHeaders(true);
    await cloudRest('PATCH', 'products', { deleted_at: new Date().toISOString() }, {
      headers,
      query: `client_id=eq.${productId}`
    });
  } catch (error) {
    console.error('Cloud delete failed', error);
    toast(`Deleted here, but the cloud copy could not be updated: ${error.message}`, true);
  }
}

/** Pushes every local product. Continues past a single product's failure. */
async function cloudPushAll(onProgress) {
  const headers = await cloudAuthHeaders(true);
  const all = await listProducts();
  let pushed = 0;
  const failed = [];
  for (let i = 0; i < all.length; i++) {
    const product = all[i];
    onProgress?.(i, all.length, product);
    try {
      await cloudPushProduct(product, headers);
      pushed++;
    } catch (error) {
      console.error('Cloud push failed for', product.id, error);
      failed.push(product);
    }
  }
  onProgress?.(all.length, all.length, null);
  cloudSetLastSyncAt('push', Date.now());
  return { pushed, failed: failed.length, total: all.length };
}

/**
 * Pulls every remote product. A remote row newer than the matching local one
 * (or not seen locally yet) overwrites it; a locally-newer row is left alone
 * -- it will win on the next push instead. Remote deletions remove the local
 * product. This never needs a sign-in: reading is open to anon.
 */
async function cloudPullAll(onProgress) {
  const headers = await cloudAuthHeaders(false);
  const remoteProducts = await cloudRest('GET', 'products', undefined, {
    headers,
    query: 'select=*&order=updated_at.asc'
  });
  const localAll = await listProducts();
  const localById = new Map(localAll.map((p) => [p.id, p]));

  let pulled = 0;
  const failed = [];
  for (let i = 0; i < remoteProducts.length; i++) {
    const rp = remoteProducts[i];
    onProgress?.(i, remoteProducts.length, rp);
    if (!rp.client_id) continue; // a row nothing here created yet; no local id to match

    try {
      if (rp.deleted_at) {
        if (localById.has(rp.client_id)) await deleteProduct(rp.client_id);
        continue;
      }

      const remoteUpdatedAt = new Date(rp.updated_at).getTime();
      const localProduct = localById.get(rp.client_id);
      if (localProduct && localProduct.updatedAt >= remoteUpdatedAt) continue; // local wins, unchanged

      const remotePhotos = await cloudRest('GET', 'product_photos', undefined, {
        headers,
        query: `product_id=eq.${rp.id}&select=*&order=sort_order.asc`
      });
      const existingPhotos = await photosFor(rp.client_id);
      const existingPhotoIds = new Set(existingPhotos.map((p) => p.id));

      const newPhotos = [];
      for (const rphoto of remotePhotos) {
        if (!rphoto.client_id || existingPhotoIds.has(rphoto.client_id)) continue;
        const blob = await cloudDownloadPhoto(rp.client_id, rphoto.client_id, headers);
        const bytes = new Uint8Array(await blob.arrayBuffer());
        const dims = await imageDimensions(blob);
        newPhotos.push({
          id: rphoto.client_id,
          productId: rp.client_id,
          blob,
          sha256: rphoto.checksum || (await sha256Hex(bytes)),
          cloudPushedSha256: rphoto.checksum || undefined,
          width: dims.width,
          height: dims.height,
          bytes: bytes.length,
          sortOrder: rphoto.sort_order ?? 0,
          origin: 'import',
          role: cloudToLocalRole(rphoto.role)
        });
      }

      let coverPhotoId = localProduct?.coverPhotoId ?? null;
      if (rp.cover_photo_id != null) {
        const coverRemote = remotePhotos.find((x) => x.id === rp.cover_photo_id);
        if (coverRemote?.client_id) coverPhotoId = coverRemote.client_id;
      }

      const product = {
        id: rp.client_id,
        slug: localProduct?.slug || slugify(rp.brand, rp.name, rp.client_id),
        brand: rp.brand || '',
        name: rp.name || '',
        model: rp.model || '',
        category: rp.category || null,
        colour: rp.colour || null,
        sizeSweepMm: parseSizeMm(rp.size),
        priceMinor: rp.price != null ? Math.round(Number(rp.price) * 100) : null,
        mrpMinor: localProduct?.mrpMinor ?? null,
        currency: localProduct?.currency || 'INR',
        description: rp.description || '',
        specs: rp.specs || {},
        coverPhotoId,
        source: localProduct?.source || 'imported',
        createdAt: new Date(rp.created_at).getTime(),
        updatedAt: remoteUpdatedAt
      };
      await putProduct(product);
      if (newPhotos.length) await putPhotos(newPhotos);
      pulled++;
    } catch (error) {
      console.error('Cloud pull failed for', rp.client_id, error);
      failed.push(rp);
    }
  }
  onProgress?.(remoteProducts.length, remoteProducts.length, null);
  cloudSetLastSyncAt('pull', Date.now());
  return { pulled, failed: failed.length, total: remoteProducts.length };
}

/** Width/height of a downloaded photo, for the same fields a local photo has. */
function imageDimensions(blob) {
  return new Promise((resolve) => {
    const url = URL.createObjectURL(blob);
    const img = new Image();
    img.onload = () => {
      URL.revokeObjectURL(url);
      resolve({ width: img.naturalWidth, height: img.naturalHeight });
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      resolve({ width: 0, height: 0 });
    };
    img.src = url;
  });
}
