/*
 * EE Lens PC Catalogue Manager.
 *
 * Everything lives in this browser's IndexedDB on this PC. The only thing that
 * ever leaves is the .eelens file you export deliberately.
 */

const $ = (id) => document.getElementById(id);
const ROLES = [ROLE_SHOP, ROLE_CATALOGUE];

let products = [];
let current = null;      // { product, photos: [...] }
let filter = '';
let categoryFilter = '';
let dragging = null;

/* ---------- money, matched to Android ---------- */

function parsePriceToMinor(text) {
  const clean = String(text || '').trim().replace(/,/g, '').replace(/^₹/, '').trim();
  if (!clean) return null;
  const parts = clean.split('.');
  if (parts.length > 2) return null;
  const whole = Number.parseInt(parts[0], 10);
  if (!Number.isFinite(whole)) return null;
  if (parts.length === 1) return whole * 100;
  const fraction = Number.parseInt(parts[1].padEnd(2, '0').slice(0, 2), 10);
  if (!Number.isFinite(fraction)) return null;
  return whole * 100 + fraction;
}

function formatPrice(minor) {
  if (minor == null) return '';
  const whole = Math.floor(Math.abs(minor) / 100);
  const fraction = Math.abs(minor) % 100;
  const digits = String(whole);
  let grouped = digits;
  if (digits.length > 3) {
    const head = digits.slice(0, -3);
    const tail = digits.slice(-3);
    const pairs = [];
    let i = head.length;
    while (i > 2) { pairs.unshift(head.slice(i - 2, i)); i -= 2; }
    if (i > 0) pairs.unshift(head.slice(0, i));
    grouped = `${pairs.join(',')},${tail}`;
  }
  return fraction === 0 ? grouped : `${grouped}.${String(fraction).padStart(2, '0')}`;
}

function slugify(brand, name, fallback) {
  const slug = `${brand} ${name}`.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
  return slug || fallback;
}

const uuid = () =>
  (crypto.randomUUID ? crypto.randomUUID()
    : 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
        const r = (Math.random() * 16) | 0;
        return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
      }));

/* ---------- chrome ---------- */

let toastTimer = null;
function toast(message, bad = false) {
  const el = $('toast');
  el.textContent = message;
  el.classList.toggle('bad', bad);
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.hidden = true; }, bad ? 7000 : 3200);
}

function busy(on, text = 'Working…') {
  $('busy-text').textContent = text;
  $('busy').hidden = !on;
}

/**
 * Nothing should be able to strand the overlay. Every async entry point already
 * clears it in a `finally`, and these catch anything that escapes entirely —
 * a bug in a handler should show a message, not a spinner that never stops.
 */
function installFailSafes() {
  const recover = (message) => {
    busy(false);
    toast(message, true);
  };
  window.addEventListener('error', (e) => recover(e.message || 'Something went wrong.'));
  window.addEventListener('unhandledrejection', (e) =>
    recover(e.reason?.message || 'Something went wrong.')
  );
}

async function refreshStats() {
  const c = await counts();
  const mb = (c.bytes / (1024 * 1024)).toFixed(1);
  $('stats').textContent = `${c.products} products · ${c.photos} photos · ${mb} MB`;
}

/* ---------- product list ---------- */

function matches(product, needle) {
  if (categoryFilter &&
      (product.category || '').trim().toLowerCase() !== categoryFilter.trim().toLowerCase()) {
    return false;
  }
  if (!needle) return true;
  const hay = [
    product.brand, product.name, product.model, product.category, product.colour,
    product.description, ...Object.entries(product.specs || {}).flat()
  ].join(' ').toLowerCase();
  return hay.includes(needle);
}

/**
 * Categories are whatever has been typed, never a fixed list — fans, lights,
 * bulbs, switches, wire, heaters, and anything the shop stocks next.
 */
function knownCategories() {
  // Case differences are the same shelf: "ceiling fan" and "Ceiling Fan" must
  // not appear as two entries. The standard spelling wins where one exists.
  const canonical = new Map();
  for (const name of STANDARD_CATEGORIES) canonical.set(name.toLowerCase(), name);
  for (const product of products) {
    const raw = (product.category || '').trim();
    if (!raw) continue;
    if (!canonical.has(raw.toLowerCase())) canonical.set(raw.toLowerCase(), raw);
  }
  return [...canonical.values()].sort((a, b) => a.localeCompare(b));
}

/** Only the categories that actually have something in them. */
function usedCategories() {
  const used = new Set(products.map((p) => (p.category || '').trim().toLowerCase()).filter(Boolean));
  return knownCategories().filter((name) => used.has(name.toLowerCase()));
}

function refreshCategoryChoices() {
  const categories = knownCategories();
  const used = new Set(usedCategories().map((c) => c.toLowerCase()));

  const select = $('category-filter');
  const chosen = categoryFilter;
  select.textContent = '';
  const any = document.createElement('option');
  any.value = '';
  any.textContent = categories.length ? 'All categories' : 'All categories (none set yet)';
  select.append(any);
  for (const category of categories) {
    const option = document.createElement('option');
    option.value = category;
    const count = products.filter(
      (p) => (p.category || '').trim().toLowerCase() === category.toLowerCase()
    ).length;
    option.textContent = used.has(category.toLowerCase()) ? `${category} (${count})` : `${category} — none yet`;
    select.append(option);
  }
  select.value = categories.includes(chosen) ? chosen : '';
  if (select.value !== chosen) categoryFilter = select.value;
}

async function renderList() {
  products = await listProducts();
  refreshCategoryChoices();
  const needle = filter.trim().toLowerCase();
  const shown = products.filter((p) => matches(p, needle));

  const list = $('product-list');
  list.textContent = '';
  for (const product of shown) {
    const li = document.createElement('li');
    li.setAttribute('role', 'option');
    li.setAttribute('aria-selected', String(current?.product.id === product.id));
    li.tabIndex = 0;

    const photos = await photosFor(product.id);
    const cover = photos.find((p) => p.id === product.coverPhotoId)
      || photos.find((p) => p.role === ROLE_CATALOGUE)
      || photos[0];

    if (cover) {
      const img = document.createElement('img');
      img.src = URL.createObjectURL(cover.thumb || cover.blob);
      img.alt = '';
      img.onload = () => URL.revokeObjectURL(img.src);
      li.append(img);
    } else {
      const box = document.createElement('div');
      box.className = 'noimg';
      li.append(box);
    }

    const text = document.createElement('div');
    text.className = 'pl-text';
    const name = document.createElement('div');
    name.className = 'pl-name';
    name.textContent = [product.brand, product.name].filter(Boolean).join(' ') || 'Untitled';
    const sub = document.createElement('div');
    sub.className = 'pl-sub';
    sub.textContent = product.model || product.category || '—';
    text.append(name, sub);

    const badge = document.createElement('span');
    badge.className = 'pl-badge';
    const shop = photos.filter((p) => p.role === ROLE_SHOP).length;
    const cat = photos.filter((p) => p.role === ROLE_CATALOGUE).length;
    badge.textContent = `${shop}/${cat}`;
    badge.title = `${shop} shop, ${cat} catalogue`;

    li.append(text, badge);
    li.addEventListener('click', () => selectProduct(product.id));
    li.addEventListener('keydown', (e) => { if (e.key === 'Enter') selectProduct(product.id); });
    list.append(li);
  }

  $('list-empty').hidden = shown.length > 0;
  $('list-empty').textContent = products.length === 0
    ? 'No products yet. Create one, or import a catalogue.'
    : categoryFilter
      ? `No products in "${categoryFilter}" match that search.`
      : 'No products match that search.';
  await refreshStats();
}

/* ---------- editor ---------- */

function blankProduct() {
  const now = Date.now();
  return {
    id: uuid(), slug: '', brand: '', name: '', model: '',
    category: '', colour: '', sizeSweepMm: null,
    priceMinor: null,   // what the shop charges
    mrpMinor: null,     // the list price it is discounted from
    currency: 'INR',
    description: '', specs: {}, coverPhotoId: null, source: 'user',
    createdAt: now, updatedAt: now
  };
}

/** Keeps the read-only discount box in step with the two price boxes. */
function refreshEditorDiscount() {
  const pct = discountPercent(parsePriceToMinor($('f-mrp').value), parsePriceToMinor($('f-price').value));
  $('f-discount').value = pct == null ? '—' : `${pct}% off`;
}

function showEditor(show) {
  $('editor').hidden = !show;
  $('placeholder').hidden = show;
}

async function selectProduct(id) {
  const product = products.find((p) => p.id === id);
  if (!product) return;
  current = { product: { ...product, specs: { ...(product.specs || {}) } }, photos: await photosFor(id) };
  fillForm();
  showEditor(true);
  await renderList();
}

function newProduct() {
  current = { product: blankProduct(), photos: [] };
  fillForm();
  showEditor(true);
  clearListSelection();
  $('f-brand').focus();
}

/** Nothing is being edited, so nothing in the list should look selected. */
function clearListSelection() {
  $('product-list').querySelectorAll('li[aria-selected="true"]').forEach((li) => {
    li.setAttribute('aria-selected', 'false');
  });
}

function fillForm() {
  const p = current.product;
  $('editor-title').textContent = p.brand || p.name ? `${p.brand} ${p.name}`.trim() : 'New product';
  $('f-brand').value = p.brand || '';
  $('f-name').value = p.name || '';
  $('f-model').value = p.model || '';
  fillCategorySelect($('f-category-select'), $('f-category'), p.category || '');
  refreshSizeOptions(p.category || '');
  $('f-colour').value = p.colour || '';
  $('f-sweep').value = p.sizeSweepMm ?? '';
  $('f-wattage').value = (p.specs || {}).Wattage || '';
  $('f-mrp').value = formatPrice(p.mrpMinor);
  $('f-price').value = formatPrice(p.priceMinor);
  refreshEditorDiscount();
  $('f-description').value = p.description || '';
  $('delete-btn').hidden = !products.some((x) => x.id === p.id);
  refreshSpecSuggestion();
  renderSpecs();
  ROLES.forEach(renderTiles);
}

function renderSpecs() {
  const host = $('spec-rows');
  host.textContent = '';
  const entries = Object.entries(current.product.specs || {});
  if (entries.length === 0) entries.push(['', '']);
  entries.forEach(([key, value], index) => {
    const row = document.createElement('div');
    row.className = 'spec-row';
    const k = document.createElement('input');
    k.type = 'text'; k.placeholder = 'Name'; k.value = key;
    const v = document.createElement('input');
    v.type = 'text'; v.placeholder = 'Value'; v.value = value;
    const rm = document.createElement('button');
    rm.className = 'btn ghost small'; rm.type = 'button'; rm.textContent = 'Remove';
    rm.addEventListener('click', () => { collectSpecs(); const e = Object.entries(current.product.specs); e.splice(index, 1); current.product.specs = Object.fromEntries(e); renderSpecs(); });
    row.append(k, v, rm);
    host.append(row);
  });
}

function collectSpecs() {
  const specs = {};
  $('spec-rows').querySelectorAll('.spec-row').forEach((row) => {
    const [k, v] = row.querySelectorAll('input');
    if (k.value.trim()) specs[k.value.trim()] = v.value.trim();
  });
  current.product.specs = specs;
}

/**
 * Folds the dedicated Wattage field into specs, mirroring how fillForm()
 * reads it back out of specs.Wattage. Only ever sets it when the owner
 * typed something here -- leaving it blank never deletes a Wattage value
 * entered as its own spec row instead (e.g. via "Add usual ... fields");
 * that row's own Remove button is what clears it.
 */
function applyWattageField(fieldId) {
  const value = $(fieldId).value.trim();
  if (!value) return;
  current.product.specs = { ...current.product.specs, Wattage: value };
}

function collectForm() {
  const p = current.product;
  p.brand = $('f-brand').value.trim();
  p.name = $('f-name').value.trim();
  p.model = $('f-model').value.trim();
  p.category = categoryValue($('f-category-select'), $('f-category'));
  p.colour = $('f-colour').value.trim();
  const sweep = Number.parseInt($('f-sweep').value, 10);
  p.sizeSweepMm = Number.isFinite(sweep) ? sweep : null;
  p.priceMinor = parsePriceToMinor($('f-price').value);
  p.mrpMinor = parsePriceToMinor($('f-mrp').value);
  p.description = $('f-description').value.trim();
  collectSpecs();
  applyWattageField('f-wattage');
}

/* ---------- photo tiles ---------- */

function effectiveCover() {
  const p = current.product;
  const gallery = current.photos.filter((x) => x.role === ROLE_CATALOGUE);
  const pool = gallery.length ? gallery : current.photos;
  return pool.find((x) => x.id === p.coverPhotoId)?.id || pool[0]?.id || null;
}

function renderTiles(role) {
  const host = $(role === ROLE_SHOP ? 'tiles-recognition' : 'tiles-display');
  host.textContent = '';
  const list = current.photos.filter((p) => p.role === role).sort((a, b) => a.sortOrder - b.sortOrder);
  const cover = effectiveCover();

  for (const photo of list) {
    const tile = document.createElement('div');
    tile.className = 'tile';
    tile.draggable = true;
    tile.dataset.id = photo.id;

    const img = document.createElement('img');
    img.src = URL.createObjectURL(photo.thumb || photo.blob);
    img.alt = '';
    img.onload = () => URL.revokeObjectURL(img.src);

    const bar = document.createElement('div');
    bar.className = 'tile-bar';

    const coverBtn = document.createElement('button');
    coverBtn.type = 'button';
    coverBtn.textContent = 'Cover';
    if (photo.id === cover) coverBtn.classList.add('is-cover');
    coverBtn.addEventListener('click', () => {
      current.product.coverPhotoId = photo.id;
      ROLES.forEach(renderTiles);
    });

    const moveBtn = document.createElement('button');
    moveBtn.type = 'button';
    moveBtn.textContent = 'Move';
    moveBtn.title = role === ROLE_SHOP ? 'Move to catalogue photos' : 'Move to shop photos';
    moveBtn.addEventListener('click', () => {
      photo.role = role === ROLE_SHOP ? ROLE_CATALOGUE : ROLE_SHOP;
      resequence();
      ROLES.forEach(renderTiles);
      updateCounts();
    });

    const rm = document.createElement('button');
    rm.type = 'button'; rm.className = 'rm'; rm.textContent = 'Remove';
    rm.addEventListener('click', async () => {
      current.photos = current.photos.filter((x) => x.id !== photo.id);
      if (current.product.coverPhotoId === photo.id) current.product.coverPhotoId = null;
      await deletePhoto(photo.id);
      resequence();
      ROLES.forEach(renderTiles);
      updateCounts();
    });

    bar.append(coverBtn, moveBtn, rm);

    const meta = document.createElement('div');
    meta.className = 'tile-meta';
    meta.textContent = `${photo.width}×${photo.height} · ${Math.round(photo.bytes / 1024)} KB`;

    tile.append(img, bar, meta);

    tile.addEventListener('dragstart', (e) => {
      dragging = photo.id;
      tile.classList.add('dragging');
      e.dataTransfer.effectAllowed = 'move';
      e.dataTransfer.setData('text/plain', photo.id);
    });
    tile.addEventListener('dragend', () => { dragging = null; tile.classList.remove('dragging'); });
    tile.addEventListener('dragover', (e) => {
      if (!dragging || dragging === photo.id) return;
      e.preventDefault();
      tile.classList.add('drop-target');
    });
    tile.addEventListener('dragleave', () => tile.classList.remove('drop-target'));
    tile.addEventListener('drop', (e) => {
      e.preventDefault();
      e.stopPropagation();
      tile.classList.remove('drop-target');
      if (!dragging || dragging === photo.id) return;
      reorder(dragging, photo.id, role);
    });

    host.append(tile);
  }
  updateCounts();
}

function reorder(movingId, targetId, role) {
  const list = current.photos.filter((p) => p.role === role).sort((a, b) => a.sortOrder - b.sortOrder);
  const moving = current.photos.find((p) => p.id === movingId);
  if (!moving) return;
  moving.role = role;
  const without = list.filter((p) => p.id !== movingId);
  const at = without.findIndex((p) => p.id === targetId);
  without.splice(at < 0 ? without.length : at, 0, moving);
  without.forEach((p, i) => { p.sortOrder = i; });
  resequence();
  ROLES.forEach(renderTiles);
}

/** Keeps sortOrder contiguous within each role. */
function resequence() {
  for (const role of ROLES) {
    current.photos.filter((p) => p.role === role)
      .sort((a, b) => a.sortOrder - b.sortOrder)
      .forEach((p, i) => { p.sortOrder = i; });
  }
}

function updateCounts() {
  const shop = current.photos.filter((p) => p.role === ROLE_SHOP).length;
  const cat = current.photos.filter((p) => p.role === ROLE_CATALOGUE).length;
  $('shop-count').textContent = shop === 0 ? 'none yet · 6–10 recommended'
    : shop < 6 ? `${shop} of 6–10 recommended` : `${shop} photos`;
  $('catalogue-count').textContent = cat === 0 ? 'none yet · optional' : `${cat} photos`;
}

/* ---------- adding photos ---------- */

async function addBlobs(blobs, role, origin) {
  if (!current) newProduct();
  let added = 0, duplicates = 0, failed = 0;
  busy(true, `Preparing ${blobs.length} photo${blobs.length === 1 ? '' : 's'}…`);
  try {
    for (const blob of blobs) {
      try {
        const prepared = await prepare(blob);
        if (current.photos.some((p) => p.sha256 === prepared.sha256)) { duplicates++; continue; }
        const photo = {
          id: uuid(),
          productId: current.product.id,
          role,
          origin,
          sortOrder: current.photos.filter((p) => p.role === role).length,
          sha256: prepared.sha256,
          width: prepared.width,
          height: prepared.height,
          bytes: prepared.bytes,
          fileName: '',
          blob: prepared.blob,
          thumb: prepared.thumb
        };
        photo.fileName = `${photo.id}.jpg`;
        current.photos.push(photo);
        await putPhoto(photo);   // survives a closed tab even before Save
        added++;
      } catch {
        failed++;
      }
    }
  } finally {
    busy(false);
  }
  resequence();
  ROLES.forEach(renderTiles);
  const parts = [];
  if (added) parts.push(`${added} added`);
  if (duplicates) parts.push(`${duplicates} already here`);
  if (failed) parts.push(`${failed} could not be read`);
  toast(parts.join(' · ') || 'Nothing added', failed > 0 && added === 0);
}

function imageFilesFrom(dataTransfer) {
  const files = [...(dataTransfer.files || [])];
  return files.filter((f) => f.type.startsWith('image/'));
}

/* ---------- save / delete ---------- */

/** Gates any action that changes the catalogue. Refreshes the session first. */
async function requireSignedIn(action) {
  await ensureFreshSession();
  if (isSignedIn()) return true;
  toast(`Sign in to ${action}.`, true);
  openAuthModal();
  return false;
}

async function saveProduct() {
  if (!(await requireSignedIn('save changes'))) return;
  collectForm();
  const p = current.product;
  if (!p.brand || !p.name) {
    toast('Enter both a brand and a product name.', true);
    ($('f-brand').value ? $('f-name') : $('f-brand')).focus();
    return;
  }
  p.slug = p.slug || slugify(p.brand, p.name, p.id);
  p.updatedAt = Date.now();
  p.coverPhotoId = effectiveCover();

  resequence();
  await putProduct(p);
  await putPhotos(current.photos);
  await renderList();
  cloudBackgroundPush(p);

  // Entering or updating a catalogue means one product after another, so a
  // save always clears the form ready for the next, whether this product was
  // new or an edit. It is safely in the list on the left — briefly
  // highlighted, so it is obvious where it went.
  const saved = `${p.brand} ${p.name}`.trim();
  newProduct();
  flashInList(p.id);
  toast(`Saved ${saved} — it is in the list on the left. Form ready for the next one.`);
}

/** Points at a row in the list so a just-saved product is easy to find. */
function flashInList(productId) {
  const rows = [...$('product-list').querySelectorAll('li')];
  const index = products.findIndex((p) => p.id === productId);
  const row = rows[index];
  if (!row) return;
  row.classList.add('just-saved');
  row.scrollIntoView({ block: 'nearest' });
  setTimeout(() => row.classList.remove('just-saved'), 2500);
}

async function removeCurrentProduct() {
  if (!(await requireSignedIn('delete products'))) return;
  const p = current.product;
  if (!confirm(`Delete "${p.brand} ${p.name}" and its photos from this PC?\n\nThis cannot be undone here.`)) return;
  await deleteProduct(p.id);
  current = null;
  showEditor(false);
  await renderList();
  toast('Product deleted');
  if (isSignedIn()) cloudDeleteProduct(p);
}

/* ---------- import / export ---------- */

async function exportCatalogue() {
  const all = await listProducts();
  if (all.length === 0) { toast('There is nothing to export yet.', true); return; }
  busy(true, 'Building catalogue file…');
  try {
    const photos = await allPhotos();
    const blob = await exportPackage(all, photos);
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = suggestFileName();
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 4000);
    toast(`Exported ${all.length} products · ${(blob.size / (1024 * 1024)).toFixed(1)} MB`);
  } catch (error) {
    toast(error.message || 'The catalogue could not be exported.', true);
  } finally {
    busy(false);
  }
}

async function importCatalogue(file) {
  busy(true, 'Reading catalogue file…');
  try {
    const parsed = await readPackage(await file.arrayBuffer());
    const existing = new Map((await listProducts()).map((p) => [p.id, p]));

    const incoming = parsed.products.length;
    const clashes = parsed.products.filter((p) => existing.has(p.id)).length;
    busy(false);

    let replace = false;
    if (clashes > 0) {
      replace = confirm(
        `This catalogue has ${incoming} products.\n` +
        `${clashes} already exist here.\n\n` +
        'OK  — replace the existing ones with the imported version\n' +
        'Cancel — keep what is here and add only the new ones'
      );
    }

    busy(true, 'Adding products…');
    let addedProducts = 0, skipped = 0, addedPhotos = 0;
    for (const product of parsed.products) {
      if (existing.has(product.id) && !replace) { skipped++; continue; }
      if (existing.has(product.id)) {
        for (const old of await photosFor(product.id)) await deletePhoto(old.id);
      }
      const { photos: _drop, ...fields } = product;
      await putProduct({ ...fields, specs: fields.specs || {} });
      addedProducts++;
    }
    for (const photo of parsed.photos) {
      const owner = parsed.products.find((p) => p.id === photo.productId);
      if (!owner) continue;
      if (existing.has(owner.id) && !replace) continue;
      const thumbSource = await prepare(photo.blob).catch(() => null);
      await putPhoto({ ...photo, thumb: thumbSource ? thumbSource.thumb : photo.blob, fileName: `${photo.id}.jpg` });
      addedPhotos++;
    }

    current = null;
    showEditor(false);
    await renderList();

    const notes = [`${addedProducts} products`, `${addedPhotos} photos`];
    if (skipped) notes.push(`${skipped} kept as they were`);
    if (parsed.missing.length) notes.push(`${parsed.missing.length} photos missing from the file`);
    if (parsed.corrupt.length) notes.push(`${parsed.corrupt.length} photos failed their checksum`);
    toast(`Imported ${notes.join(' · ')}`, parsed.missing.length + parsed.corrupt.length > 0);
  } catch (error) {
    toast(error.message || 'This catalogue could not be imported.', true);
  } finally {
    busy(false);
  }
}

/* ---------- suggested specification fields ---------- */

/**
 * Different categories need different fields — a mixer has wattage and jars, a
 * bulb has wattage and colour temperature, a wall fan has two sizes. These are
 * only a starting point: every key and value stays editable, and nothing here
 * is required.
 */
const SPEC_TEMPLATES = {
  fans: ['Sweep', 'Power', 'Speed', 'Air delivery', 'Motor', 'Star rating', 'Warranty'],
  'wall fans': ['Sweep', 'Mounting', 'Speed type', 'Power', 'Speeds', 'Warranty'],
  'pedestal fans': ['Sweep', 'Height', 'Power', 'Speeds', 'Warranty'],
  'exhaust fans': ['Sweep', 'Power', 'Air delivery', 'Mounting', 'Warranty'],
  mixers: ['Wattage', 'Number of jars', 'Jar capacity', 'Speeds', 'Motor', 'Warranty'],
  geysers: ['Capacity', 'Wattage', 'Type', 'Pressure rating', 'Star rating', 'Warranty'],
  ac: ['Capacity', 'Star rating', 'Type', 'Power', 'Refrigerant', 'Warranty'],
  leds: ['Wattage', 'Colour temperature', 'Base type', 'Lumens', 'Shape', 'Warranty'],
  'outdoor leds': ['Wattage', 'Colour temperature', 'IP rating', 'Lumens', 'Warranty'],
  'fancy lamps': ['Type', 'Holder', 'Material', 'Bulbs needed', 'Warranty'],
  'wires/cables': ['Cores', 'Cross section', 'Length', 'Insulation', 'Current rating', 'Warranty'],
  switches: ['Rating', 'Modules', 'Type', 'Finish', 'Series', 'Warranty'],
  'distribution box': ['Ways', 'Type', 'Rating', 'Mounting', 'Warranty'],
  stabilisers: ['Capacity', 'Input range', 'Output', 'Type', 'Warranty'],
  'induction cooktops': ['Wattage', 'Presets', 'Panel type', 'Controls', 'Warranty'],
  kettles: ['Capacity', 'Wattage', 'Material', 'Warranty'],
  'immersion rod heaters': ['Wattage', 'Length', 'Shock proof', 'Warranty'],
  irons: ['Wattage', 'Type', 'Soleplate', 'Steam', 'Warranty']
};

/** The categories Electric Emporium stocks. "Other" keeps it open-ended. */
const STANDARD_CATEGORIES = [
  'Fans',
  'Wall Fans',
  'Pedestal Fans',
  'Exhaust Fans',
  'Mixers',
  'Geysers',
  'AC',
  'LEDs',
  'Outdoor LEDs',
  'Fancy Lamps',
  'Wires/Cables',
  'Switches',
  'Distribution Box',
  'Stabilisers',
  'Induction Cooktops',
  'Kettles',
  'Immersion Rod Heaters',
  'Irons'
];

/** Sizes each category actually comes in, so the field is a pick not a guess. */
const SIZE_OPTIONS = {
  fans: [600, 900, 1050, 1200, 1400],
  'wall fans': [300, 400, 450],
  'pedestal fans': [400, 450],
  'exhaust fans': [150, 200, 250, 300],
  leds: [600, 1200],
  'immersion rod heaters': [1000, 1500, 2000]
};

function sizeOptionsFor(category) {
  const key = (category || '').trim().toLowerCase();
  if (!key) return [];
  if (SIZE_OPTIONS[key]) return SIZE_OPTIONS[key];
  const match = Object.keys(SIZE_OPTIONS).find((name) => key.includes(name));
  return match ? SIZE_OPTIONS[match] : [];
}

/**
 * Builds a category dropdown from the standard list plus anything already used,
 * with "Other" revealing a text box so a new kind of product is never blocked.
 */
function fillCategorySelect(select, textInput, value) {
  const used = knownCategories();
  const all = [...new Set([...STANDARD_CATEGORIES, ...used])]
    .sort((a, b) => a.localeCompare(b));

  select.textContent = '';
  const blank = document.createElement('option');
  blank.value = ''; blank.textContent = '— none —';
  select.append(blank);
  for (const name of all) {
    const option = document.createElement('option');
    option.value = name; option.textContent = name;
    select.append(option);
  }
  const other = document.createElement('option');
  other.value = '__other__'; other.textContent = 'Other…';
  select.append(other);

  const known = all.some((n) => n.toLowerCase() === (value || '').toLowerCase());
  if (value && !known) {
    select.value = '__other__';
    textInput.hidden = false;
    textInput.value = value;
  } else {
    select.value = all.find((n) => n.toLowerCase() === (value || '').toLowerCase()) || '';
    textInput.hidden = true;
    textInput.value = '';
  }
}

/** What the category boxes currently mean, whichever half is in use. */
function categoryValue(select, textInput) {
  return select.value === '__other__' ? textInput.value.trim() : select.value;
}

function refreshSizeOptions(category) {
  const list = $('size-options');
  list.textContent = '';
  for (const size of sizeOptionsFor(category)) {
    const option = document.createElement('option');
    option.value = String(size);
    list.append(option);
  }
}

function templateFor(category) {
  const key = (category || '').trim().toLowerCase();
  if (!key) return null;
  if (SPEC_TEMPLATES[key]) return SPEC_TEMPLATES[key];
  // "Ceiling Fans" and "Smart Ceiling Fan" should both find the fan template.
  const match = Object.keys(SPEC_TEMPLATES).find((name) => key.includes(name));
  return match ? SPEC_TEMPLATES[match] : null;
}

/** Offers the fields that category usually needs, without forcing any of them. */
function refreshSpecSuggestion() {
  const button = $('suggest-specs');
  const category = categoryValue($('f-category-select'), $('f-category'));
  const template = templateFor(category);
  if (!template) { button.hidden = true; return; }
  button.hidden = false;
  button.textContent = `Add usual ${category.trim().toLowerCase()} fields`;
  button.onclick = () => {
    collectSpecs();
    const specs = { ...current.product.specs };
    for (const key of template) if (!(key in specs)) specs[key] = '';
    current.product.specs = specs;
    renderSpecs();
    toast(`Added ${template.length} suggested fields — fill in what applies, remove the rest.`);
  };
}

/* ---------- import from a product URL ---------- */

let candidate = null;   // the extracted, still-unapproved product
let importQueue = [];   // every link pasted, in order
let queueIndex = 0;

function openUrlImport() {
  candidate = null;
  importQueue = [];
  queueIndex = 0;
  $('url-queue-bar').hidden = true;
  $('url-skip').hidden = true;
  $('url-modal').hidden = false;
  $('url-preview').hidden = true;
  $('url-error').hidden = true;
  $('url-approve').disabled = true;
  $('url-summary').textContent = '';
  $('url-source').value = '';
  $('url-source').focus();
}

function closeUrlImport() {
  $('url-modal').hidden = true;
  candidate = null;
}

function showUrlError(message) {
  const box = $('url-error');
  box.textContent = message;
  box.hidden = false;
}

/** Fetches every pasted link, then hands them over one at a time. */
async function runUrlImport() {
  const urls = $('url-source').value
    .split(/[\s,]+/)
    .map((u) => u.trim())
    .filter((u) => /^https?:\/\//i.test(u));

  if (urls.length === 0) {
    showUrlError('Paste at least one link starting with http:// or https://');
    return;
  }

  $('url-error').hidden = true;
  importQueue = [];
  queueIndex = 0;

  for (let i = 0; i < urls.length; i++) {
    busy(true, urls.length === 1
      ? 'Reading the product page…'
      : `Reading page ${i + 1} of ${urls.length}…`);
    try {
      const { finalUrl, html } = await fetchProductPage(urls[i]);
      importQueue.push({ url: urls[i], candidate: extractProduct(html, finalUrl) });
    } catch (error) {
      importQueue.push({ url: urls[i], error: error.message });
    }
  }
  busy(false);

  const failures = importQueue.filter((item) => item.error);
  if (failures.length === importQueue.length) {
    $('url-preview').hidden = true;
    $('url-approve').disabled = true;
    showUrlError(
      `${failures[0].error}\n\nYou can still add the product by hand — press "Enter manually instead".`
    );
    return;
  }
  if (failures.length > 0) {
    showUrlError(
      `${failures.length} of ${importQueue.length} links could not be read; ` +
      `the rest are ready to review.\n${failures.map((f) => `• ${f.url}`).join('\n')}`
    );
  }
  showQueueItem(0);
}

/** Moves the review dialog to the next link that actually fetched. */
function showQueueItem(from) {
  const next = importQueue.findIndex((item, i) => i >= from && item.candidate);
  if (next < 0) {
    finishQueue();
    return;
  }
  queueIndex = next;
  candidate = importQueue[next].candidate;
  renderCandidate();
  renderQueueBar();
}

function renderQueueBar() {
  const bar = $('url-queue-bar');
  const total = importQueue.filter((i) => i.candidate).length;
  const skip = $('url-skip');
  if (total <= 1) {
    bar.hidden = true;
    skip.hidden = true;
    $('url-approve').textContent = 'Approve and save';
    return;
  }
  const position = importQueue.slice(0, queueIndex + 1).filter((i) => i.candidate).length;
  bar.hidden = false;
  bar.textContent = `Reviewing ${position} of ${total} — check this one, then approve or skip it.`;
  skip.hidden = false;
  $('url-approve').textContent = position < total ? 'Approve and next' : 'Approve and finish';
}

function skipQueueItem() {
  importQueue[queueIndex].skipped = true;
  showQueueItem(queueIndex + 1);
}

function finishQueue() {
  const done = importQueue.filter((i) => i.approved).length;
  const skipped = importQueue.filter((i) => i.skipped).length;
  const failed = importQueue.filter((i) => i.error).length;
  closeUrlImport();
  if (done + skipped + failed > 0) {
    const parts = [`${done} added`];
    if (skipped) parts.push(`${skipped} skipped`);
    if (failed) parts.push(`${failed} could not be read`);
    toast(parts.join(' · '), failed > 0 && done === 0);
  }
}

function renderCandidate() {
  const c = candidate;
  $('url-source-note').textContent =
    `From ${c.sourceUrl}` +
    (c.adapter && c.adapter !== 'generic'
      ? ` · read from the site's own product data (${c.adapter})`
      : c.hadStructuredData
        ? ' · the page published structured product data'
        : ' · no structured data — blanks below mean the page did not say');

  $('u-brand').value = c.brand;
  $('u-name').value = c.name;
  $('u-model').value = c.model;
  fillCategorySelect($('u-category-select'), $('u-category'), c.category || '');
  refreshSizeOptions(c.category || '');
  $('u-colour').value = c.colour;
  $('u-sweep').value = c.sizeSweepMm ?? '';
  $('u-wattage').value = c.specs.Wattage || readSpecsFromText(c).Wattage || '';
  $('u-mrp').value = formatPrice(c.mrpMinor);
  $('u-price').value = formatPrice(c.priceMinor);
  syncReviewDiscount('prices');
  $('u-description').value = c.description;

  // Variant picker, only when the page actually sells more than one.
  const variantRow = $('u-variant-row');
  const picker = $('u-variant');
  if (c.variants && c.variants.length > 1) {
    picker.textContent = '';
    c.variants.forEach((v, i) => {
      const option = document.createElement('option');
      option.value = String(i);
      const price = v.priceMinor ? ` — ₹${formatPrice(v.priceMinor)}` : '';
      option.textContent = `${v.sku} · ${v.label}${price}`;
      picker.append(option);
    });
    picker.value = String(c.selectedVariant ?? 0);
    variantRow.hidden = false;
  } else {
    variantRow.hidden = true;
  }

  // Specs: what the page published, plus anything readable from its own title
  // and description, plus blank rows for the fields this category usually needs.
  const specHost = $('u-specs');
  specHost.textContent = '';
  const merged = { ...c.specs, ...readSpecsFromText(c) };
  for (const key of templateFor(c.category) || []) {
    if (!(key in merged)) merged[key] = '';
  }
  delete merged.Wattage;   // it has its own field above
  const entries = Object.entries(merged);
  if (entries.length === 0) {
    const none = document.createElement('p');
    none.className = 'modal-note';
    none.textContent = 'None found on the page. Add them after saving if you want them.';
    specHost.append(none);
  }
  entries.forEach(([key, value]) => {
    const row = document.createElement('div');
    row.className = 'spec-row';
    const k = document.createElement('input'); k.type = 'text'; k.value = key;
    const v = document.createElement('input'); v.type = 'text'; v.value = value;
    const rm = document.createElement('button');
    rm.className = 'btn ghost small'; rm.type = 'button'; rm.textContent = 'Remove';
    rm.addEventListener('click', () => row.remove());
    row.append(k, v, rm);
    specHost.append(row);
  });

  // image candidates, previewed through the helper so nothing is downloaded twice
  const imageHost = $('u-images');
  imageHost.textContent = '';
  c.images.forEach((src, index) => {
    const tile = document.createElement('div');
    tile.className = 'tile cand';
    tile.dataset.src = src;

    const img = document.createElement('img');
    img.src = `/fetch?url=${encodeURIComponent(src)}`;
    img.alt = '';
    img.loading = 'lazy';
    // A link that will not load is not a candidate; drop it quietly.
    img.addEventListener('error', () => { tile.classList.add('bad'); updateCandidateSummary(); });

    const bar = document.createElement('div');
    bar.className = 'cand-bar';
    const keepLabel = document.createElement('label');
    const keep = document.createElement('input');
    keep.type = 'checkbox';
    keep.checked = index < 6;
    keep.addEventListener('change', () => {
      tile.classList.toggle('off', !keep.checked);
      updateCandidateSummary();
    });
    keepLabel.append(keep, document.createTextNode('Keep'));

    const role = document.createElement('select');
    for (const [value, text] of [[ROLE_CATALOGUE, 'Catalogue'], [ROLE_SHOP, 'Shop']]) {
      const option = document.createElement('option');
      option.value = value; option.textContent = text;
      role.append(option);
    }
    bar.append(keepLabel, role);
    tile.classList.toggle('off', !keep.checked);
    tile.append(img, bar);
    imageHost.append(tile);
  });

  $('u-image-count').textContent = c.images.length ? `${c.images.length} found` : 'none found';
  $('url-preview').hidden = false;
  $('url-approve').disabled = false;
  updateCandidateSummary();
}

/**
 * Keeps MRP, selling price and discount consistent while all three stay editable.
 * Editing a price recalculates the discount; typing a discount moves the price.
 */
function syncReviewDiscount(changed) {
  const mrp = parsePriceToMinor($('u-mrp').value);
  if (changed === 'discount') {
    const pct = Number.parseFloat($('u-discount').value.replace(/[^0-9.]/g, ''));
    if (mrp && Number.isFinite(pct) && pct >= 0 && pct < 100) {
      $('u-price').value = formatPrice(Math.round(mrp * (1 - pct / 100)));
    }
    return;
  }
  const pct = discountPercent(mrp, parsePriceToMinor($('u-price').value));
  $('u-discount').value = pct == null ? '' : String(pct);
}

/** Swaps the preview over to another SKU on the same page. */
function selectVariant(index) {
  const variant = candidate?.variants?.[index];
  if (!variant) return;
  candidate.selectedVariant = index;
  candidate.name = variant.name || candidate.name;
  candidate.model = variant.sku;
  candidate.colour = variant.colour;
  candidate.sizeSweepMm = variant.sizeSweepMm;
  candidate.priceMinor = variant.priceMinor;
  candidate.mrpMinor = variant.mrpMinor;
  if (variant.category) candidate.category = variant.category;
  if (variant.images?.length) candidate.images = variant.images;
  renderCandidate();
}

function chosenCandidateImages() {
  return [...$('u-images').querySelectorAll('.tile.cand')]
    .filter((tile) => !tile.classList.contains('bad'))
    .filter((tile) => tile.querySelector('input[type="checkbox"]').checked)
    .map((tile) => ({ src: tile.dataset.src, role: tile.querySelector('select').value }));
}

function updateCandidateSummary() {
  const chosen = chosenCandidateImages();
  const shop = chosen.filter((c) => c.role === ROLE_SHOP).length;
  const cat = chosen.length - shop;
  $('url-summary').textContent = chosen.length
    ? `${chosen.length} images selected — ${cat} catalogue, ${shop} shop`
    : 'No images selected — details only';
}

/** Nothing reaches the catalogue until this runs. */
async function approveCandidate() {
  if (!(await requireSignedIn('save imported products'))) return;
  const brand = $('u-brand').value.trim();
  const name = $('u-name').value.trim();
  if (!brand || !name) {
    showUrlError('Enter both a brand and a product name before approving.');
    return;
  }

  const specs = {};
  $('u-specs').querySelectorAll('.spec-row').forEach((row) => {
    const [k, v] = row.querySelectorAll('input');
    if (k.value.trim()) specs[k.value.trim()] = v.value.trim();
  });
  const wattage = $('u-wattage').value.trim();
  if (wattage) specs.Wattage = wattage; else delete specs.Wattage;

  const sweep = Number.parseInt($('u-sweep').value, 10);
  const now = Date.now();
  const product = {
    ...blankProduct(),
    brand, name,
    model: $('u-model').value.trim(),
    category: categoryValue($('u-category-select'), $('u-category')),
    colour: $('u-colour').value.trim(),
    sizeSweepMm: Number.isFinite(sweep) ? sweep : null,
    priceMinor: parsePriceToMinor($('u-price').value),
    mrpMinor: parsePriceToMinor($('u-mrp').value),
    description: $('u-description').value.trim(),
    specs,
    createdAt: now,
    updatedAt: now
  };
  product.slug = slugify(product.brand, product.name, product.id);

  const chosen = chosenCandidateImages();
  current = { product, photos: [] };

  busy(true, chosen.length ? `Downloading ${chosen.length} images…` : 'Saving…');
  let failed = 0;
  try {
    for (const item of chosen) {
      try {
        const blob = await fetchImageUrl(item.src);
        const prepared = await prepare(blob);
        if (current.photos.some((p) => p.sha256 === prepared.sha256)) continue;
        const id = uuid();
        const photo = {
          id, productId: product.id, role: item.role, origin: 'file',
          sortOrder: current.photos.filter((p) => p.role === item.role).length,
          sha256: prepared.sha256, width: prepared.width, height: prepared.height,
          bytes: prepared.bytes, fileName: `${id}.jpg`, blob: prepared.blob, thumb: prepared.thumb
        };
        current.photos.push(photo);
        await putPhoto(photo);
      } catch {
        failed++;
      }
    }
    resequence();
    product.coverPhotoId = effectiveCover();
    await putProduct(product);
    await putPhotos(current.photos);
  } finally {
    busy(false);
  }

  if (importQueue[queueIndex]) importQueue[queueIndex].approved = true;
  const photosAdded = current.photos.length
  await renderList();

  const remaining = importQueue.some((item, i) => i > queueIndex && item.candidate);
  if (remaining) {
    // Straight on to the next link; the saved one is already in the list.
    toast(
      `Saved ${product.brand} ${product.name} · ${photosAdded} photos` +
      (failed ? ` · ${failed} images failed` : '')
    );
    showQueueItem(queueIndex + 1);
    return;
  }

  closeUrlImport();
  await selectProduct(product.id);
  toast(
    `Imported ${product.brand} ${product.name} · ${photosAdded} photos` +
    (failed ? ` · ${failed} images could not be downloaded` : ''),
    failed > 0
  );
}

/* ---------- sign in ---------- */

function updateAuthUI() {
  const btn = $('auth-btn');
  btn.textContent = isSignedIn() ? `Sign out (${session.email})` : 'Sign in';
  // Keeps the Cloud sync panel's sign-in line correct even if it is left
  // open across a sign-in or sign-out.
  refreshCloudHints();
}

function openAuthModal() {
  $('auth-error').hidden = true;
  $('auth-status').textContent = '';
  $('auth-email').value = session?.email || '';
  $('auth-password').value = '';
  $('auth-modal').hidden = false;
  $('auth-email').focus();
}

function closeAuthModal() {
  $('auth-modal').hidden = true;
}

async function trySignIn() {
  const email = $('auth-email').value.trim();
  const password = $('auth-password').value;
  if (!email || !password) {
    $('auth-error').textContent = 'Enter both an email and a password.';
    $('auth-error').hidden = false;
    return;
  }
  busy(true, 'Signing in…');
  try {
    await signIn(email, password);
    busy(false);
    closeAuthModal();
    toast(`Signed in as ${email}`);
  } catch (error) {
    busy(false);
    $('auth-error').textContent = error.message;
    $('auth-error').hidden = false;
  }
}

async function toggleAuth() {
  if (isSignedIn()) {
    if (!confirm('Sign out? You can still browse the catalogue, but adding, editing or deleting will need signing in again.')) return;
    await signOut();
    toast('Signed out');
  } else {
    openAuthModal();
  }
}

/* ---------- phone sync over the shop network ---------- */

const whenLabel = (seconds) => {
  if (!seconds) return 'nothing shared yet';
  const mins = Math.round((Date.now() / 1000 - seconds) / 60);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins} minutes ago`;
  const hours = Math.round(mins / 60);
  return hours < 24 ? `${hours} hours ago` : new Date(seconds * 1000).toLocaleString();
};

async function syncInfo() {
  try {
    const response = await fetch('/sync/info');
    return response.ok ? await response.json() : null;
  } catch {
    return null;
  }
}

/** The Phone sync button only appears when the helper was started with sync on. */
async function refreshSyncAvailability() {
  const info = await syncInfo();
  $('sync-btn').hidden = !info?.enabled;
  return info;
}

async function openSync() {
  const info = await refreshSyncAvailability();
  if (!info?.enabled) return;
  $('sync-address').textContent = info.address;
  $('sync-code').textContent = info.code;
  $('sync-push-at').textContent = `Last shared with the phone: ${whenLabel(info.toPhoneAt)}`;
  $('sync-pull-at').textContent = info.fromPhone
    ? `The phone shared a catalogue ${whenLabel(info.fromPhoneAt)}`
    : 'The phone has not shared anything yet';
  $('sync-modal').hidden = false;
}

/** Hands the current catalogue to the helper for the phone to collect. */
async function pushToPhone() {
  const all = await listProducts();
  if (all.length === 0) { toast('There is nothing to share yet.', true); return; }
  busy(true, 'Preparing the catalogue…');
  try {
    const blob = await exportPackage(all, await allPhotos());
    const response = await fetch('/sync/pc', { method: 'PUT', body: blob });
    if (!response.ok) throw new Error(await response.text());
    toast(`Shared ${all.length} products with the phone — collect it there`);
    openSync();
  } catch (error) {
    toast(error.message || 'That could not be shared.', true);
  } finally {
    busy(false);
  }
}

/** Collects whatever the phone last sent and imports it. */
async function pullFromPhone() {
  busy(true, 'Collecting from the phone…');
  try {
    const response = await fetch('/sync/phone');
    if (response.status === 404) throw new Error('The phone has not shared anything yet.');
    if (!response.ok) throw new Error(await response.text());
    const file = new File([await response.blob()], 'from-phone.eelens');
    $('sync-modal').hidden = true;
    busy(false);
    await importCatalogue(file);
  } catch (error) {
    toast(error.message || 'Nothing could be collected.', true);
  } finally {
    busy(false);
  }
}

/* ---------- cloud sync ---------- */

function cloudWhenLabel(ms) {
  if (!ms) return 'never';
  const mins = Math.round((Date.now() - ms) / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins} minutes ago`;
  const hours = Math.round(mins / 60);
  return hours < 24 ? `${hours} hours ago` : new Date(ms).toLocaleString();
}

function refreshCloudHints() {
  $('cloud-auth-status').textContent = isSignedIn() ? `Signed in as ${session.email}` : 'Not signed in';
  $('cloud-push-at').textContent = `Last pushed: ${cloudWhenLabel(cloudLastSyncAt('push'))}`;
  $('cloud-pull-at').textContent = `Last pulled: ${cloudWhenLabel(cloudLastSyncAt('pull'))}`;
}

function openCloudModal() {
  refreshCloudHints();
  $('cloud-modal').hidden = false;
}

/**
 * Runs the same pull logic the old "Pull from cloud" button used to trigger,
 * automatically on every page load (a plain refresh included -- there is
 * nothing special to detect, this file just runs again from the top). Never
 * blocks the rest of startup and never throws; a failure is a toast, since
 * whatever is already on this PC is unaffected either way.
 */
async function cloudAutoPull() {
  const progressEl = $('cloud-progress');
  progressEl.hidden = false;
  progressEl.textContent = 'Checking the cloud for updates…';
  try {
    const summary = await cloudPullAll((done, total) => {
      progressEl.textContent = `Checking the cloud for updates — ${done} of ${total}…`;
    });
    if (summary.failed) {
      toast(`Pulled from the cloud, but ${summary.failed} product(s) failed — see the browser console`, true);
    }
    if (summary.pulled > 0) await renderList();
  } catch (error) {
    console.error('Auto pull failed', error);
    toast(`Could not check the cloud for updates: ${error.message}`, true);
  } finally {
    progressEl.hidden = true;
    refreshCloudHints();
  }
}

/* ---------- housekeeping ---------- */

/** Photos left behind by a product that was never saved. */
async function sweepOrphans() {
  const known = new Set((await listProducts()).map((p) => p.id));
  const orphans = (await allPhotos()).filter((p) => !known.has(p.productId));
  for (const photo of orphans) await deletePhoto(photo.id);
  return orphans.length;
}

/* ---------- wiring ---------- */

function wire() {
  $('new-btn').addEventListener('click', newProduct);

  $('url-btn').addEventListener('click', openUrlImport);
  $('url-close').addEventListener('click', closeUrlImport);
  $('url-go').addEventListener('click', runUrlImport);
  $('url-approve').addEventListener('click', approveCandidate);
  $('url-manual').addEventListener('click', () => {
    // The fallback when a site blocks importing: keep whatever was readable.
    const prefill = candidate;
    closeUrlImport();
    newProduct();
    if (prefill) {
      $('f-brand').value = prefill.brand || '';
      $('f-name').value = prefill.name || '';
      $('f-model').value = prefill.model || '';
      $('f-category').value = prefill.category || '';
      $('f-description').value = prefill.description || '';
      $('f-price').value = formatPrice(prefill.priceMinor);
    }
    toast('Enter the details by hand, then add photos from your PC.');
  });
  // Enter and Shift+Enter must add a line — the box takes several links.
  // Ctrl+Enter (or Cmd+Enter) is what starts the fetch.
  $('url-source').addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); runUrlImport(); }
  });
  $('u-variant').addEventListener('change', (e) => selectVariant(Number(e.target.value)));
  $('u-mrp').addEventListener('input', () => syncReviewDiscount('prices'));
  $('u-price').addEventListener('input', () => syncReviewDiscount('prices'));
  $('u-discount').addEventListener('input', () => syncReviewDiscount('discount'));
  $('f-mrp').addEventListener('input', refreshEditorDiscount);
  $('f-price').addEventListener('input', refreshEditorDiscount);
  const onCategoryChange = (select, text) => {
    text.hidden = select.value !== '__other__';
    if (select.value === '__other__') text.focus();
    const value = categoryValue(select, text);
    refreshSizeOptions(value);
    if (select === $('f-category-select')) refreshSpecSuggestion();
  };
  $('f-category-select').addEventListener('change', () =>
    onCategoryChange($('f-category-select'), $('f-category')));
  $('f-category').addEventListener('input', () => {
    refreshSizeOptions($('f-category').value);
    refreshSpecSuggestion();
  });
  // Picking a category in the review adds that category's usual fields as blank
  // rows. Existing rows are left exactly as they are.
  const addTemplateRows = () => {
    const category = categoryValue($('u-category-select'), $('u-category'));
    refreshSizeOptions(category);
    const template = templateFor(category);
    if (!template) return;
    const host = $('u-specs');
    const present = new Set(
      [...host.querySelectorAll('.spec-row')].map((r) => r.querySelectorAll('input')[0].value.trim())
    );
    const missing = template.filter((key) => !present.has(key));
    if (missing.length === 0) return;
    host.querySelector('.modal-note')?.remove();
    for (const key of missing) {
      const row = document.createElement('div');
      row.className = 'spec-row';
      const k = document.createElement('input'); k.type = 'text'; k.value = key;
      const v = document.createElement('input'); v.type = 'text';
      const rm = document.createElement('button');
      rm.className = 'btn ghost small'; rm.type = 'button'; rm.textContent = 'Remove';
      rm.addEventListener('click', () => row.remove());
      row.append(k, v, rm);
      host.append(row);
    }
    toast(`Added ${missing.length} usual ${category.toLowerCase()} fields`);
  };
  $('u-category-select').addEventListener('change', () => {
    onCategoryChange($('u-category-select'), $('u-category'));
    addTemplateRows();
  });
  $('u-category').addEventListener('input', () => refreshSizeOptions($('u-category').value));
  $('u-category').addEventListener('change', addTemplateRows);
  $('url-skip').addEventListener('click', skipQueueItem);
  $('auth-btn').addEventListener('click', toggleAuth);
  $('auth-close').addEventListener('click', closeAuthModal);
  $('auth-go').addEventListener('click', trySignIn);
  $('auth-password').addEventListener('keydown', (e) => { if (e.key === 'Enter') trySignIn(); });
  $('auth-modal').addEventListener('click', (e) => { if (e.target === $('auth-modal')) closeAuthModal(); });
  onAuthChange(updateAuthUI);

  $('sync-btn').addEventListener('click', openSync);
  $('sync-close').addEventListener('click', () => { $('sync-modal').hidden = true; });
  $('sync-push').addEventListener('click', pushToPhone);
  $('sync-pull').addEventListener('click', pullFromPhone);

  $('cloud-btn').addEventListener('click', openCloudModal);
  $('cloud-close').addEventListener('click', () => { $('cloud-modal').hidden = true; });
  $('cloud-modal').addEventListener('click', (e) => {
    if (e.target === $('cloud-modal')) $('cloud-modal').hidden = true;
  });

  $('sync-modal').addEventListener('click', (e) => {
    if (e.target === $('sync-modal')) $('sync-modal').hidden = true;
  });
  $('url-modal').addEventListener('click', (e) => {
    if (e.target === $('url-modal')) closeUrlImport();
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !$('url-modal').hidden) closeUrlImport();
  });
  $('save-btn').addEventListener('click', saveProduct);
  $('delete-btn').addEventListener('click', removeCurrentProduct);
  $('add-spec').addEventListener('click', () => {
    collectSpecs();
    current.product.specs = { ...current.product.specs, '': '' };
    renderSpecs();
  });
  $('search').addEventListener('input', (e) => { filter = e.target.value; renderList(); });
  $('category-filter').addEventListener('change', (e) => { categoryFilter = e.target.value; renderList(); });

  $('export-btn').addEventListener('click', exportCatalogue);
  $('import-btn').addEventListener('click', () => $('import-file').click());
  $('import-file').addEventListener('change', (e) => {
    const file = e.target.files[0];
    e.target.value = '';
    if (file) importCatalogue(file);
  });

  let pickRole = ROLE_SHOP;
  $('file-input').addEventListener('change', (e) => {
    const files = [...e.target.files].filter((f) => f.type.startsWith('image/'));
    e.target.value = '';
    if (files.length) addBlobs(files, pickRole, 'file');
  });

  document.querySelectorAll('.pick').forEach((btn) =>
    btn.addEventListener('click', () => { pickRole = btn.dataset.role; $('file-input').click(); })
  );

  document.querySelectorAll('.url-add').forEach((btn) =>
    btn.addEventListener('click', async () => {
      const input = document.querySelector(`.url-input[data-role="${btn.dataset.role}"]`);
      const url = input.value.trim();
      if (!url) return;
      busy(true, 'Downloading image…');
      try {
        const blob = await fetchImageUrl(url);
        busy(false);
        await addBlobs([blob], btn.dataset.role, 'file');
        input.value = '';
      } catch (error) {
        toast(error.message, true);
      } finally {
        busy(false);
      }
    })
  );

  document.querySelectorAll('.url-input').forEach((input) =>
    input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        document.querySelector(`.url-add[data-role="${input.dataset.role}"]`).click();
      }
    })
  );

  document.querySelectorAll('.dropzone').forEach((zone) => {
    const role = zone.dataset.role;
    ['dragenter', 'dragover'].forEach((type) =>
      zone.addEventListener(type, (e) => { e.preventDefault(); zone.classList.add('over'); })
    );
    ['dragleave', 'drop'].forEach((type) =>
      zone.addEventListener(type, () => zone.classList.remove('over'))
    );
    zone.addEventListener('drop', (e) => {
      e.preventDefault();
      const files = imageFilesFrom(e.dataTransfer);
      if (files.length) addBlobs(files, role, 'file');
    });
  });

  // Dropping a catalogue file anywhere on the page imports it, so the phone's
  // file can be dragged straight out of Explorer without hunting for a button.
  const isCatalogueFile = (file) => /\.(eelens|zip)$/i.test(file?.name || '');
  window.addEventListener('dragover', (e) => {
    if ([...(e.dataTransfer?.items || [])].some((i) => i.kind === 'file')) e.preventDefault();
  });
  window.addEventListener('drop', (e) => {
    const file = [...(e.dataTransfer?.files || [])].find(isCatalogueFile);
    if (!file) return;          // image drops are handled by the photo sections
    e.preventDefault();
    e.stopPropagation();
    importCatalogue(file);
  });

  // Ctrl+V anywhere pastes into the shop section unless a catalogue field has focus.
  document.addEventListener('paste', (e) => {
    const blobs = [...(e.clipboardData?.items || [])]
      .filter((i) => i.type.startsWith('image/'))
      .map((i) => i.getAsFile())
      .filter(Boolean);
    if (!blobs.length || !current) return;
    e.preventDefault();
    const focused = document.activeElement?.dataset?.role;
    addBlobs(blobs, focused === ROLE_CATALOGUE ? ROLE_CATALOGUE : ROLE_SHOP, 'file');
  });

  window.addEventListener('beforeunload', (e) => {
    if (!current) return;
    const saved = products.find((p) => p.id === current.product.id);
    if (!saved) { e.preventDefault(); e.returnValue = ''; }
  });
}

(async function start() {
  installFailSafes();
  wire();
  updateAuthUI();
  await ensureFreshSession();
  updateAuthUI();
  const swept = await sweepOrphans();
  await refreshSyncAvailability();
  await renderList();
  showEditor(false);
  if (swept) toast(`Cleaned up ${swept} photos from an unsaved product`);
  // Not awaited: every load (a plain refresh included) checks the cloud for
  // updates on its own, but the rest of startup -- and the locally-saved
  // catalogue already rendered above -- must never wait on the network for it.
  cloudAutoPull();
})();
