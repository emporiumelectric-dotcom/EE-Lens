/*
 * Cloud sync: mirrors this catalogue to Supabase (ee_lens.products /
 * ee_lens.product_photos, plus the ee-lens-photos Storage bucket) so the PC
 * and the Android app can both read and write the same catalogue without
 * passing a .eelens file back and forth by hand.
 *
 * This sync is automatic, not a button someone has to remember to press:
 * every save or delete pushes that one product right away (cloudBackgroundPush
 * / the delete call site in app.js), and every page load -- including a
 * plain browser refresh -- pulls in the cloud's latest (see cloudAutoPull in
 * app.js). The .eelens export/import remains the full-fidelity,
 * works-with-no-internet backup; this is a lighter cloud mirror on top of it.
 *
 * Reading is open to everyone (RLS: anon can SELECT); writing needs a signed-
 * in Supabase session (RLS: authenticated only), matching supabase.js's
 * existing sign-in gate. Two devices editing the same product before either
 * syncs is resolved last-write-wins, no merge, no prompt: cloudPullAll only
 * ever takes a remote row that is newer than the local one, and
 * cloudPushProduct (see pushWouldLoseToRemote) refuses to push over a remote
 * row that is already newer than the local edit.
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

// Photo bytes are bigger and slower than a plain REST call -- see
// fetchWithTimeout (supabase.js) for why every request needs a bound at all.
const CLOUD_PHOTO_TIMEOUT_MS = 60_000;

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

  const response = await fetchWithTimeout(url, {
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

async function cloudUploadPhoto(productClientId, photoClientId, photo, headers) {
  const url = `${SUPABASE_URL}/storage/v1/object/${SUPABASE_PHOTOS_BUCKET}/${cloudPhotoPath(productClientId, photoClientId)}`;
  const response = await fetchWithTimeout(
    url,
    {
      method: 'POST',
      headers: {
        apikey: headers.apikey,
        Authorization: headers.Authorization,
        'Content-Type': 'image/jpeg',
        'x-upsert': 'true'
      },
      body: photo.blob
    },
    CLOUD_PHOTO_TIMEOUT_MS
  );
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
  const response = await fetchWithTimeout(
    url,
    { headers: { apikey: headers.apikey, Authorization: headers.Authorization } },
    CLOUD_PHOTO_TIMEOUT_MS
  );
  if (!response.ok) throw new Error(`Photo download failed (${response.status})`);
  return response.blob();
}

/**
 * Removes one photo's bytes from Storage. A 404 is treated as success --
 * the goal ("this object should not exist") is already true, and this runs
 * after a partial-failure retry as easily as a fresh delete.
 */
async function cloudDeletePhotoObject(storagePath, headers) {
  const url = `${SUPABASE_URL}/storage/v1/object/${SUPABASE_PHOTOS_BUCKET}/${storagePath}`;
  const response = await fetchWithTimeout(
    url,
    { method: 'DELETE', headers: { apikey: headers.apikey, Authorization: headers.Authorization } },
    CLOUD_PHOTO_TIMEOUT_MS
  );
  if (!response.ok && response.status !== 404) {
    const text = await response.text().catch(() => '');
    throw new Error(`Photo delete failed (${response.status}): ${text || response.statusText}`);
  }
}

/**
 * Last-write-wins, made explicit: true when a remote row already carries an
 * updated_at at or after this device's local one, meaning a push would
 * clobber a newer edit made elsewhere with a stale one from here. No merge,
 * no prompt -- the loser's push is simply skipped; its own next pull brings
 * the winning version back to it instead.
 */
function pushWouldLoseToRemote(remoteUpdatedAtIso, localUpdatedAtMs) {
  if (!remoteUpdatedAtIso) return false; // never pushed before -- nothing to lose to
  return new Date(remoteUpdatedAtIso).getTime() >= (localUpdatedAtMs || 0);
}

/**
 * Pushes one product and its photos. Product metadata is always upserted
 * (cheap); a photo's bytes are only re-uploaded when they have never been
 * pushed or have changed since -- the expensive part is skipped, not the
 * whole product.
 */
async function cloudPushProduct(product, headers) {
  const clientId = await resolveProductClientId(product);

  // Checked before touching anything else: this push must never overwrite a
  // remote edit newer than the one it is carrying (see pushWouldLoseToRemote).
  const [existingRemote] = await cloudRest('GET', 'products', undefined, {
    headers,
    query: `client_id=eq.${clientId}&select=updated_at`
  });
  if (existingRemote && pushWouldLoseToRemote(existingRemote.updated_at, product.updatedAt)) {
    return;
  }

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
  const survivingClientIds = [];
  let touched = false;
  for (const photo of photos) {
    const photoClientId = await resolvePhotoClientId(photo);
    survivingClientIds.push(photoClientId);
    const photoBody = {
      client_id: photoClientId,
      product_id: savedProduct.id,
      role: localRoleToCloud(photo.role),
      sort_order: photo.sortOrder,
      checksum: photo.sha256
    };
    if (photo.cloudPushedSha256 !== photo.sha256) {
      // Keyed by clientId/photoClientId (the cloud identity), never
      // product.id/photo.id (the local one) -- a legacy-id product's local
      // id is a fixed slug like "havells-stealth-air-pearl-white", not
      // something a pulling device can ever reconstruct on its own.
      // storage_path is only ever set here, the one place bytes actually
      // land at that path; an already-pushed photo below leaves it out of
      // the upsert entirely so a repeat push can never point the row at a
      // path nothing was written to. See CloudSyncManager.kt's pushProduct
      // for the same fix on Android.
      await cloudUploadPhoto(clientId, photoClientId, photo, headers);
      photo.cloudPushedSha256 = photo.sha256;
      touched = true;
      photoBody.storage_path = cloudPhotoPath(clientId, photoClientId);
    }
    const [savedPhoto] = await cloudRest('POST', 'product_photos', photoBody, {
      headers,
      query: 'on_conflict=client_id',
      prefer: 'resolution=merge-duplicates,return=representation'
    });
    remotePhotoIdByLocalId[photo.id] = savedPhoto.id;
  }
  if (touched) await putPhotos(photos);

  // A photo removed locally must stop existing in the cloud too -- see
  // remotePhotoClientIdsToDelete's own comment for why this is safe to run
  // unconditionally here (pushWouldLoseToRemote has already passed for the
  // product as a whole, above).
  const remotePhotos = await cloudRest('GET', 'product_photos', undefined, {
    headers,
    query: `product_id=eq.${savedProduct.id}&select=*`
  });
  const remoteRowByClientId = new Map(
    remotePhotos.filter((r) => r.client_id).map((r) => [r.client_id, r])
  );
  for (const staleClientId of remotePhotoClientIdsToDelete([...remoteRowByClientId.keys()], survivingClientIds)) {
    const staleRow = remoteRowByClientId.get(staleClientId);
    if (staleRow.storage_path) await cloudDeletePhotoObject(staleRow.storage_path, headers);
    await cloudRest('DELETE', 'product_photos', undefined, { headers, query: `id=eq.${staleRow.id}` });
  }

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

/**
 * Pushes every local product. Continues past a single product's failure.
 *
 * Nothing in the UI calls this any more -- an individual save or delete
 * pushes itself, right away (cloudBackgroundPush / cloudDeleteProduct). This
 * stays as a manual recovery tool: run `cloudPushAll()` from this page's
 * browser console to push a catalogue that predates cloud sync, or to
 * recover after the cloud side was reset.
 */
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
 * Which local product (if any) a cloud row with this identity corresponds
 * to -- mirrors CloudSyncManager.kt's selectPullMatch on Android (added
 * there to fix the same duplication this port fixes on PC; see that
 * function's own comment for the full rationale). Pure and dependency-
 * free, tried in order:
 *  1. The row's client_id as a local id -- this device's own earlier push.
 *  2. As a recorded cloudClientId -- a legacy-id product already
 *     reconciled once.
 *  3. By content (brand/name/model) -- a legacy-id product (typically the
 *     bundled catalogue) pushed by a *different* device under a client_id
 *     this one has never seen. Weak identity on its own, so it only runs
 *     once both id-based checks have missed.
 */
function selectPullMatch(clientId, brand, name, model, locals) {
  for (const p of locals) if (p.id === clientId) return p;
  for (const p of locals) if (p.cloudClientId === clientId) return p;
  if (!brand || !name) return null;
  const norm = (s) => (s || '').trim().toLowerCase();
  for (const p of locals) {
    if (norm(p.brand) === norm(brand) && norm(p.name) === norm(name) && norm(p.model) === norm(model)) return p;
  }
  return null;
}

/**
 * Remote photo client_ids (out of the product's whole current remote set)
 * no longer matched by any surviving local photo -- a removed photo
 * pushing its own removal, the gap that let a photo deleted here linger on
 * every other device forever (nothing ever told Supabase to delete it;
 * cloudPushProduct only ever upserted whatever photosFor() currently
 * returns). Mirrors CloudSyncManager.kt's remotePhotoClientIdsToDelete.
 *
 * Safe to run unconditionally in cloudPushProduct because
 * pushWouldLoseToRemote has already passed for the product as a whole by
 * the time this runs: this device's current photo set is the one that
 * wins, so anything the cloud still has outside it belongs to a photo
 * removed here. Pure and dependency-free; see PhotoSyncRemovalTest.js.
 */
function remotePhotoClientIdsToDelete(remoteClientIds, survivingClientIds) {
  const surviving = new Set(survivingClientIds);
  return remoteClientIds.filter((id) => !surviving.has(id));
}

/**
 * Local photo ids no longer matched by anything in remoteClientIds -- the
 * pull side of the same rule, so a photo removed on one device eventually
 * disappears everywhere, not just there. Mirrors CloudSyncManager.kt's
 * photosToRemoveLocally, id resolution included (a legacy-id photo's own
 * local id doubles as its cloud identity until a push assigns it a real
 * cloudClientId -- same reasoning as selectPullMatch above, for a photo).
 *
 * Only ever considers a photo whose cloudPushedSha256 is set (confirmed
 * pushed to the cloud at least once, the same marker cloudPushProduct's own
 * upload-skip check already uses): a photo just added locally, whose own
 * push hasn't run yet, has no way to appear in remoteClientIds either, and
 * this must never read that as "removed elsewhere" and delete a photo the
 * owner only just added. Only called, in cloudPullAll, once the remote row
 * has already been established as newer than the local one.
 */
function photosToRemoveLocally(localPhotos, remoteClientIds) {
  const remote = new Set(remoteClientIds);
  return localPhotos
    .filter((p) => p.cloudPushedSha256)
    .filter((p) => !remote.has(p.cloudClientId || p.id))
    .map((p) => p.id);
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
  // Active rows are processed before deleted ones, regardless of the
  // server's own updated_at order: a deleted row can carry an OLDER
  // timestamp than an active row for the same underlying product -- e.g. a
  // duplicate marked deleted after a newer, still-active row already
  // existed for the same real-world product. Processing the deleted row
  // first can strong-match (by id or cloudClientId) and remove a local
  // product before the active row ever gets the chance to rebind that
  // identity away from the deleted row, in this same pull. sort() is
  // stable, so this only reorders deleted vs. not, preserving the ascending
  // updated_at order within each group. Mirrors CloudSyncManager.kt's
  // pullAll.
  remoteProducts.sort((a, b) => Number(Boolean(a.deleted_at)) - Number(Boolean(b.deleted_at)));
  const localAll = await listProducts();
  const findLocalProduct = (rp) => selectPullMatch(rp.client_id, rp.brand, rp.name, rp.model, localAll);

  let pulled = 0;
  const failed = [];
  for (let i = 0; i < remoteProducts.length; i++) {
    const rp = remoteProducts[i];
    onProgress?.(i, remoteProducts.length, rp);
    if (!rp.client_id) continue; // a row nothing here created yet; no local id to match

    try {
      const localProduct = findLocalProduct(rp);
      // The row a legacy-id product owns locally keeps its real id -- never
      // rename it to the cloud's generated UUID underneath the owner.
      const localId = localProduct ? localProduct.id : rp.client_id;

      if (rp.deleted_at) {
        // A content-only match (no id or cloudClientId match -- see
        // selectPullMatch) is too weak to trust with a delete: unlike
        // applying data, where a false positive just means an extra update,
        // deleting the wrong local row is unrecoverable (PC hard-deletes).
        // A duplicate or orphaned remote row sharing brand/name/model with
        // a genuinely live local product must never take it down with it
        // when the duplicate itself gets cleaned up. See CloudSyncManager.
        // kt's pullProduct for the same fix on Android.
        if (localProduct && (localProduct.id === rp.client_id || localProduct.cloudClientId === rp.client_id)) {
          await deleteProduct(localId);
        }
        continue;
      }

      const remoteUpdatedAt = new Date(rp.updated_at).getTime();
      if (localProduct && localProduct.updatedAt >= remoteUpdatedAt) {
        // A match found only by content (no id-based match yet) still needs
        // the discovered cloud identity recorded now, even though local data
        // wins and nothing else about the row changes here -- otherwise this
        // PC never learns it, and the next time it pushes this same product,
        // resolveCloudClientId sees no known cloudClientId and mints a brand
        // new one, creating a duplicate remote row instead of updating this
        // one. This is the actual bug behind the "Havells Stealth Air"
        // duplicate -- see CloudSyncManager.kt's pullProduct for the same
        // fix on Android.
        if (rp.client_id !== localId && localProduct.cloudClientId !== rp.client_id) {
          localProduct.cloudClientId = rp.client_id;
          await putProduct(localProduct);
        }
        continue; // local wins, unchanged
      }

      const remotePhotos = await cloudRest('GET', 'product_photos', undefined, {
        headers,
        query: `product_id=eq.${rp.id}&select=*&order=sort_order.asc`
      });
      const remoteClientIds = remotePhotos.filter((p) => p.client_id).map((p) => p.client_id);
      const existingPhotos = await photosFor(localId);
      // Same idea for photos: a legacy-id photo's client_id (its cloudClientId)
      // does not equal its local id, so map through both to find what is
      // already here -- and to resolve the cover photo below.
      const localPhotoIdByCloudId = new Map();
      existingPhotos.forEach((p) => {
        localPhotoIdByCloudId.set(p.id, p.id);
        if (p.cloudClientId) localPhotoIdByCloudId.set(p.cloudClientId, p.id);
      });
      // A photo removed on whichever device pushed this (newer) row must
      // stop existing here too -- see photosToRemoveLocally's own comment
      // for the cloudPushedSha256 guard that keeps this from ever touching
      // a photo just added on this PC that hasn't had a chance to push yet.
      const staleLocalPhotoIds = photosToRemoveLocally(existingPhotos, remoteClientIds);

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

      let coverPhotoId = staleLocalPhotoIds.includes(localProduct?.coverPhotoId)
        ? null
        : localProduct?.coverPhotoId ?? null;
      if (rp.cover_photo_id != null) {
        const coverRemote = remotePhotos.find((x) => x.id === rp.cover_photo_id);
        if (coverRemote?.client_id) {
          coverPhotoId = localPhotoIdByCloudId.get(coverRemote.client_id) || coverPhotoId;
        }
      }

      const product = {
        id: localId,
        // Whatever path found this row, this PC now knows its cloud
        // identity: undefined when the local id already is that identity
        // (the common case, and a brand new local row), otherwise the
        // client_id itself -- including a match found only by content just
        // now, so the next pull (or push) uses it directly instead of
        // falling back again. Mirrors CloudSyncManager.kt's pullProduct.
        cloudClientId: rp.client_id !== localId ? rp.client_id : localProduct?.cloudClientId,
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
      for (const staleId of staleLocalPhotoIds) await deletePhoto(staleId);
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
