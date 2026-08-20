/*
 * Local storage for the catalogue: IndexedDB, on this PC, nothing else.
 *
 * Product rows are small and kept in one store; photos are held as Blobs in a
 * second store so a few hundred products with images stay responsive and
 * survive a closed tab or a crashed browser.
 */

const DB_NAME = 'ee-lens-manager';
const DB_VERSION = 1;

const ROLE_SHOP = 'recognition';
const ROLE_CATALOGUE = 'display';

let dbPromise = null;

function openDb() {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains('products')) {
        db.createObjectStore('products', { keyPath: 'id' });
      }
      if (!db.objectStoreNames.contains('photos')) {
        const photos = db.createObjectStore('photos', { keyPath: 'id' });
        photos.createIndex('productId', 'productId');
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
  return dbPromise;
}

async function tx(stores, mode, run) {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const transaction = db.transaction(stores, mode);
    const result = run(...stores.map((s) => transaction.objectStore(s)));
    transaction.oncomplete = () => resolve(result?.value ?? result);
    transaction.onerror = () => reject(transaction.error);
    transaction.onabort = () => reject(transaction.error);
  });
}

function all(store) {
  return new Promise((resolve, reject) => {
    const request = store.getAll();
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function listProducts() {
  const db = await openDb();
  const products = await new Promise((resolve, reject) => {
    const request = db.transaction('products').objectStore('products').getAll();
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
  return products.sort((a, b) =>
    (a.brand || '').localeCompare(b.brand || '') || (a.name || '').localeCompare(b.name || '')
  );
}

async function photosFor(productId) {
  const db = await openDb();
  const rows = await new Promise((resolve, reject) => {
    const request = db.transaction('photos').objectStore('photos').index('productId').getAll(productId);
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
  return rows.sort((a, b) => a.sortOrder - b.sortOrder);
}

async function allPhotos() {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const request = db.transaction('photos').objectStore('photos').getAll();
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function putProduct(product) {
  return tx(['products'], 'readwrite', (products) => products.put(product));
}

async function putPhoto(photo) {
  return tx(['photos'], 'readwrite', (photos) => photos.put(photo));
}

async function putPhotos(list) {
  return tx(['photos'], 'readwrite', (photos) => list.forEach((p) => photos.put(p)));
}

async function deletePhoto(photoId) {
  return tx(['photos'], 'readwrite', (photos) => photos.delete(photoId));
}

async function deleteProduct(productId) {
  const photos = await photosFor(productId);
  await tx(['products', 'photos'], 'readwrite', (productStore, photoStore) => {
    productStore.delete(productId);
    photos.forEach((p) => photoStore.delete(p.id));
  });
}

async function clearAll() {
  return tx(['products', 'photos'], 'readwrite', (products, photos) => {
    products.clear();
    photos.clear();
  });
}

async function counts() {
  const [products, photos] = await Promise.all([listProducts(), allPhotos()]);
  const bytes = photos.reduce((sum, p) => sum + (p.bytes || 0), 0);
  return { products: products.length, photos: photos.length, bytes };
}
