# EE Lens — Local Catalogue Planning Brief

## Instruction

Inspect this repository and produce a detailed implementation plan only. Do not edit files, install dependencies, build the app, or begin implementation until the user approves the plan.

## Current product

EE Lens is a native Android app for Electric Emporium. It currently:

- Uses Kotlin, Jetpack Compose, CameraX, and MediaPipe Image Embedder.
- Runs recognition entirely on-device with no paid API or cloud service.
- Recognizes a small bundled catalogue of ceiling fans from reference photos packaged inside the APK.
- Shows a live camera, 1x/2x/3x zoom, recognition result, confidence, product list, and product details.
- Has a branded white/red/charcoal Electric Emporium interface.
- Stores no editable product database yet. Bundled catalogue changes currently require rebuilding the APK.

## Goal

Design the next version so a nontechnical shop owner can add, edit, delete, back up, import, and restore many products without Android Studio or a developer.

The system needs two catalogue-entry methods that work together:

1. An Add Product workflow inside the Android app.
2. A PC Catalogue Manager for faster bulk entry.

Everything must remain zero-cost, local-first, and usable without cloud services.

## Android requirements

- Add, edit, view, and delete products.
- Suggested fields: stable ID, brand, product name, model, category, colour, size/sweep, price, description, flexible specifications, created/updated timestamps.
- Add multiple reference photos from:
  - the phone camera;
  - Gallery;
  - Downloads/document picker.
- Display saved internet and shop photos inside EE Lens rather than using them only for recognition.
- Show a product thumbnail in the catalogue and a swipeable full-photo gallery in product details.
- Let the user choose or change the main/cover photo.
- Copy imported internet photos into app-private storage so the gallery continues working offline even if the original Download is moved or deleted.
- Let users add more photos to an existing product later.
- Clearly show photo count and simple guidance; target roughly 6–10 shop photos plus optional internet photos.
- Resize/compress large images and prevent accidental duplicate photos.
- Generate embeddings on-device when a product is saved or imported.
- Persist embeddings so they are not recalculated on every app launch.
- Recognition must use both the original bundled products and user-added products.
- Seed bundled products into the editable local catalogue once, without duplicating them after upgrades.
- Store metadata in a robust local database and photos in app-private storage. Evaluate Room for metadata and an appropriate versioned representation for embeddings.
- App uninstall will remove private data, so export/backup must be prominent and reliable.
- Use Android's Storage Access Framework for import/export. Do not request broad storage permission.
- Preserve the existing Scan and Products experience unless a change is necessary for the new workflow.
- Handle interrupted saves/imports safely and show clear progress and errors.

## PC Catalogue Manager requirements

- A simple Electric Emporium-branded local web tool that works in current Chrome on Windows.
- Prefer plain HTML/CSS/JavaScript or another genuinely zero-install approach.
- No server, login, paid API, cloud database, CDN dependency, or internet requirement.
- Add/edit/delete products quickly.
- Drag and drop multiple image files.
- Accept images pasted from the clipboard when supported.
- Show image previews, allow cover-photo selection, and allow reordering/removing photos before export.
- Import an existing EE Lens backup/catalogue.
- Export one portable versioned `.eelens` catalogue file for the phone.
- Be usable for hundreds of products without becoming slow or confusing.
- Do not perform AI recognition in the PC tool unless there is a strong technical reason. The phone may generate/rebuild embeddings after import.

## Portable catalogue format

Propose and document a versioned `.eelens` package format, likely a ZIP container with:

- a versioned JSON manifest;
- product metadata;
- image files in predictable product folders;
- hashes for integrity and duplicate detection;
- optional model/embedding version metadata.

The plan must decide whether embeddings should be exported or always regenerated after import. Prefer correctness across future model upgrades.

Import must validate the package before changing the live catalogue. Define safe handling for:

- corrupt or incomplete packages;
- unsupported future versions;
- duplicate product IDs;
- add/replace/skip conflicts;
- missing or invalid images;
- cancellation or low storage.

## Data and privacy constraints

- No cloud dependency.
- No paid APIs.
- Product data and images remain on the user's phone or PC.
- No analytics or user tracking.
- Avoid adding Android internet permission unless clearly required and approved.
- Backups must contain no unrelated phone files or secrets.

## Scale and performance

Plan for hundreds of products and several photos per product. Include:

- image size/compression limits;
- storage estimates;
- embedding caching and indexing strategy;
- catalogue loading and recognition performance;
- background work/progress strategy;
- database migrations and package format migrations.

## Explicitly out of scope for this phase

- Cloud synchronization.
- User accounts.
- Paid APIs.
- Automatic downloading from arbitrary image URLs.
- Exact fan-outline segmentation/tracing.
- Replacing the recognition model unless the repository review proves it is necessary for editable catalogue support.

## Required planning output

Return a concise but implementation-ready plan containing:

1. Repository findings with relevant file paths and current limitations.
2. Recommended architecture and data flow.
3. Local database, photo storage, and embedding schema.
4. A concrete `.eelens` package specification with example manifest JSON.
5. Phone user flows and PC user flows.
6. Import, export, backup, restore, conflict, and failure behaviour.
7. Phased implementation sequence with testable milestones.
8. Exact files expected to be added or modified; list any proposed deletion separately and do not delete without approval.
9. Dependency proposals with justification; prefer AndroidX and vendored/offline-capable PC assets.
10. Unit, integration, UI, package compatibility, and device test strategy.
11. Risks, open decisions, and the recommended default for each.
12. Acceptance criteria for the first usable release.

Keep the plan understandable to a nontechnical owner. End by asking for approval before making any changes.
