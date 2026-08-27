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
 *   - local id (usually a UUID) <-> remote client_id (uuid, unique)
 *   - local priceMinor (paise)  <-> remote price (numeric rupees)
 *   - local sizeSweepMm (int)   <-> remote size (free text, "1200mm")
 *   - local role recognition/display <-> remote role shop/catalogue
 *   - slug/mrpMinor/currency/source have no remote column and stay local-only;
 *     .eelens keeps the full picture.
 *   - remote products.deleted_at mirrors local soft-delete.
 *
 * Not every local id is a UUID: the bundled demo catalogue (seeded on the
 * phone and carried over here by import) uses fixed slugs like
 * "havells-enticer-vineer", which the uuid-typed client_id column rejects
 * outright ("invalid input syntax for type uuid"). For a row like that, a
 * UUID is generated once, sent as client_id, and written back onto the local
 * record as cloudClientId -- so every later push and pull agrees on the same
 * cloud identity instead of minting a new one (and a new duplicate remote
 * row) every time.
 */

function cloudPhotoPath(productId, photoId) {
  return `${productId}/${photoId}.jpg`;
}

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function isValidUuid(value) {
  return typeof value === 'string' && UUID_RE.test(value);
}

/** The client_id to use for this product/photo, without persisting anything. */
function resolveCloudClientId(entity) {
  if (isValidUuid(entity.id)) return entity.id;
  if (isValidUuid(entity.cloudClientId)) return entity.cloudClientId;
  return uuid();
}

/** Resolves this product's client_id, persisting a freshly generated one so it is stable next time. */
async function resolveProductClientId(product) {
  const clientId = resolveCloudClientId(product);
  if (clientId !== product.id && clientId !== product.cloudClientId) {
    product.cloudClientId = clientId;
    await putProduct(product);
  }
  return clientId;
}

/**
 * Resolves this photo's client_id, persisting a freshly generated one
 * immediately (not batched) so a push that fails partway through a
 * product's photos does not mint a different id -- and a duplicate remote
 * row -- for the same photo on retry.
 */
async function resolvePhotoClientId(photo) {
  const clientId = resolveCloudClientId(photo);
  if (clientId !== photo.id && clientId !== photo.cloudClientId) {
    photo.cloudClientId = clientId;
    await putPhoto(photo);
  }
  return clientId;
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

/**
 * Downloads by the storage_path the row itself carries, rather than
 * reconstructing one from local ids -- the device pulling a photo may not
 * use the same local ids as the device that pushed it (see cloudClientId
 * above), so the row's own path is the only value both can agree on.
 */
async function cloudDownloadPhoto(storagePath, headers) {
  const url = `${SUPABASE_URL}/storage/v1/object/${SUPABASE_PHOTOS_BUCKET}/${storagePath}`;
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
  const clientId = await resolveProductClientId(product);
  const remoteProduct = {
    client_id: clientId,
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
    const photoClientId = await resolvePhotoClientId(photo);
    if (photo.cloudPushedSha256 !== photo.sha256) {
      await cloudUploadPhoto(product.id, photo, headers);
      photo.cloudPushedSha256 = photo.sha256;
      touched = true;
    }
    const [savedPhoto] = await cloudRest(
      'POST',
      'product_photos',
      {
        client_id: photoClientId,
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

/**
 * Marks a product deleted in the cloud. Call this at the moment of local
 * delete, with the product object still in hand -- once deleteProduct() has
 * run there is nothing left locally to read a cloudClientId back from.
 */
async function cloudDeleteProduct(product) {
  const clientId = isValidUuid(product.id) ? product.id : product.cloudClientId;
  if (!clientId) return; // never successfully pushed -- nothing in the cloud to mark deleted
  try {
    const headers = await cloudAuthHeaders(true);
    await cloudRest('PATCH', 'products', { deleted_at: new Date().toISOString() }, {
      headers,
      query: `client_id=eq.${clientId}`
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
  // Legacy-id products (the bundled catalogue) are pushed under a generated
  // cloudClientId instead of their real id -- match on that too, or a remote
  // row with no local match yet reads as brand new every single pull.
  const localByCloudClientId = new Map(localAll.filter((p) => p.cloudClientId).map((p) => [p.cloudClientId, p]));
  const findLocalProduct = (clientId) => localById.get(clientId) || localByCloudClientId.get(clientId) || null;

  let pulled = 0;
  const failed = [];
  for (let i = 0; i < remoteProducts.length; i++) {
    const rp = remoteProducts[i];
    onProgress?.(i, remoteProducts.length, rp);
    if (!rp.client_id) continue; // a row nothing here created yet; no local id to match

    try {
      const localProduct = findLocalProduct(rp.client_id);
      // The row a legacy-id product owns locally keeps its real id -- never
      // rename it to the cloud's generated UUID underneath the owner.
      const localId = localProduct ? localProduct.id : rp.client_id;

      if (rp.deleted_at) {
        if (localProduct) await deleteProduct(localId);
        continue;
      }

      const remoteUpdatedAt = new Date(rp.updated_at).getTime();
      if (localProduct && localProduct.updatedAt >= remoteUpdatedAt) continue; // local wins, unchanged

      const remotePhotos = await cloudRest('GET', 'product_photos', undefined, {
        headers,
        query: `product_id=eq.${rp.id}&select=*&order=sort_order.asc`
      });
      const existingPhotos = await photosFor(localId);
      // Same idea for photos: a legacy-id photo's client_id (its cloudClientId)
      // does not equal its local id, so map through both to find what is
      // already here -- and to resolve the cover photo below.
      const localPhotoIdByCloudId = new Map();
      existingPhotos.forEach((p) => {
        localPhotoIdByCloudId.set(p.id, p.id);
        if (p.cloudClientId) localPhotoIdByCloudId.set(p.cloudClientId, p.id);
      });

      const newPhotos = [];
      for (const rphoto of remotePhotos) {
        if (!rphoto.client_id || localPhotoIdByCloudId.has(rphoto.client_id)) continue;
        const blob = await cloudDownloadPhoto(rphoto.storage_path, headers);
        const bytes = new Uint8Array(await blob.arrayBuffer());
        const dims = await imageDimensions(blob);
        newPhotos.push({
          id: rphoto.client_id,
          productId: localId,
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
        // A brand new local photo adopts the cloud's id directly -- map it to
        // itself so a cover-photo match against it below still resolves.
        localPhotoIdByCloudId.set(rphoto.client_id, rphoto.client_id);
      }

      let coverPhotoId = localProduct?.coverPhotoId ?? null;
      if (rp.cover_photo_id != null) {
        const coverRemote = remotePhotos.find((x) => x.id === rp.cover_photo_id);
        if (coverRemote?.client_id) {
          coverPhotoId = localPhotoIdByCloudId.get(coverRemote.client_id) || coverPhotoId;
        }
      }

      const product = {
        id: localId,
        cloudClientId: localProduct?.cloudClientId,
        slug: localProduct?.slug || slugify(rp.brand, rp.name, localId),
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
