# EE Lens — PC Catalogue Manager

A local tool for building the Electric Emporium product catalogue on a PC, then
carrying it to the phone as a single `.eelens` file.

It covers everything the shop sells — fans, lights, bulbs, switches, wires,
appliances, water heaters, and whatever comes next. Category is free text, so a
new kind of product needs no change to the software.

Everything stays on this computer. There is no account, no cloud service, no
paid API, and nothing is uploaded anywhere. The only time the tool reaches the
internet is when you paste an image link and ask it to download that one image.

## Opening it

Double-click **`EE Lens Manager.bat`**.

That starts a small helper on this PC and opens the manager in your browser at
`http://127.0.0.1:8730`. Close the black window when you have finished.

You can also open `index.html` directly if you prefer. Everything works that way
except pasting image links, which the browser is not allowed to download by
itself for security reasons.

## Using it

**Products** — "New product" on the left, fill in brand and name (the only two
required fields), then price, description and any specifications. Search filters on every field including
specifications, and the category dropdown narrows the list to one kind of
product. Size is optional — it suits a fan sweep or a tube length, and is simply
left blank for a switch or a bulb.

Specifications are entirely yours to name: wattage and lumens for a bulb,
amperage for a switch, cores and length for wire, capacity for a heater.

### Import from a product URL

Paste a manufacturer or shop link and press **Fetch details**. Whatever the page
publishes — name, brand, model, price, description, specification table and
images — appears in an editable preview.

**Nothing is saved until you press Approve.** Correct anything that came through
wrong, untick images you do not want, and switch any image between catalogue and
shop before approving. Images from a website default to catalogue photos.

If a site blocks importing, or publishes nothing useful, press **Enter manually
instead**. Whatever was readable is carried across and you type the rest.

Only public websites can be imported. Addresses on your own network are refused,
and the page is never allowed to run any code — it is read for values only.

**Photos** come in two kinds, and the difference matters:

| Section | What goes here | What it does |
|---|---|---|
| **Shop photos** | Photos of the actual product on your shelf | What the phone camera matches against. Never shown to customers. Aim for 6–10. |
| **Catalogue photos** | Clean images from a catalogue or manufacturer | What customers see in the app, and what lets the phone recognise the product from a laptop or phone screen. |

Add photos by dragging them in, choosing files, pasting an image link, or
pressing Ctrl+V with an image on the clipboard. Drag a photo to reorder it,
click **Cover** to make it the main image, or **Move** to send it to the other
section.

Photos are copied into the catalogue, so deleting the original file later will
not break anything. Large images are scaled to 1024 px on the long edge and
saved as JPEG — the same as the phone does — so a photo prepared here and one
taken on the phone are stored identically.

## Moving the catalogue to the phone

**Export .eelens** writes one file to your Downloads folder. Copy that file to
the phone and import it in EE Lens.

**Import .eelens** reads a file back in. If a product already exists you are
asked once whether to replace it or keep what is here.

The file is an ordinary ZIP. If you ever need to rescue it by hand, rename it to
`.zip` and open it — inside are `manifest.json`, `products.json`, and a `photos`
folder.

Recognition fingerprints are deliberately **not** included. They only mean
anything for the exact recognition model that produced them, so the phone
regenerates them after import. That takes a few seconds and happens on its own.

## Where the data lives

In this browser's local database, for this PC and this browser profile only.

That means two things worth knowing. Clearing your browser's site data will
erase the catalogue, and the catalogue does not follow you to another browser or
another computer. **Export a `.eelens` file regularly** — it is the backup.

## Files

| File | Purpose |
|---|---|
| `EE Lens Manager.bat` | Launcher — starts the helper and opens the browser |
| `server.py` | The local helper: serves these files, downloads pasted image links, and fetches product pages |
| `index.html`, `styles.css` | The page |
| `app.js` | Screen logic |
| `storage.js` | Local database |
| `images.js` | Resizing, compression, checksums |
| `eelens.js` | Reading and writing the `.eelens` package |
| `import-url.js` | Reading product details out of a fetched page |
| `zip.js` | ZIP container |
