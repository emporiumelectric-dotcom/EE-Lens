#!/usr/bin/env python3
"""
Real-evidence investigation for "use OCR to catch brand/model confusion".
Runs in GitHub Actions (this sandbox's own egress to arbitrary domains,
including Supabase itself, is blocked -- confirmed via a direct curl
returning a 403 from the egress proxy).

Two parts:
  1. Real photos: downloads the actual 8 "shop" (recognition-role) photos
     already captured in the live catalogue for product id=5, "Velora
     Prime Storage Water Heater 10L (Geyser)" -- the exact product from
     the reported failure case -- and runs OCR against them as-is. This is
     the single most representative evidence available: these are real
     on-device recognition photos this app's own users already captured,
     not stock photography.
  2. Synthetic degradation grid: since a real V-GUARD photo isn't in this
     catalogue (it's the WRONG brand that got matched, not something the
     shop owns), and to characterise OCR's sensitivity to real shop
     conditions (camera angle, blur, embossed-plastic low contrast)
     systematically rather than anecdotally, renders "HAVELLS", "V-GUARD"
     and "VELORA PRIME" as text at a grid of rotation/blur/contrast
     combinations and OCRs each one.

Uses Tesseract (apt-installed on the runner), not ML Kit itself -- ML Kit's
on-device text recognizer only runs inside an Android process (Google Play
Services), which this sandbox cannot execute. Tesseract is a reasonable,
honestly-labelled PROXY for "how hard is reading printed/embossed text off
a real product under real conditions", not a numerically exact stand-in --
this script's own report says so plainly rather than implying otherwise.
"""
import json
import subprocess
import urllib.request
from pathlib import Path

SUPABASE_URL = "https://buzidwccluskdkccidev.supabase.co"
SUPABASE_ANON_KEY = "sb_publishable_Zm5PI1gxB8ZU6_m4Dydirw_THsgZR7x"
BUCKET = "ee-lens-photos"

REAL_PHOTOS = [
    "64bd289f-4d23-41d0-b515-0afd1f75ed2b/bced7c99-7945-40b8-bfe1-4fa39c328ce4.jpg",
    "64bd289f-4d23-41d0-b515-0afd1f75ed2b/cb6e73f8-c4e2-4c15-aade-999e0fdc6fcb.jpg",
    "64bd289f-4d23-41d0-b515-0afd1f75ed2b/b5f7f140-1ef2-4a1f-b21e-9fa0458e5bcd.jpg",
    "64bd289f-4d23-41d0-b515-0afd1f75ed2b/b8809fc0-ea6e-4984-854f-22f94b3ebc99.jpg",
    "64bd289f-4d23-41d0-b515-0afd1f75ed2b/ff0e8be7-630f-41f9-adc4-0a3d379cfc18.jpg",
]

OUT_DIR = Path("ocr_investigation_out")
OUT_DIR.mkdir(exist_ok=True)


def download_real_photos():
    results = []
    for i, path in enumerate(REAL_PHOTOS):
        url = f"{SUPABASE_URL}/storage/v1/object/{BUCKET}/{path}"
        req = urllib.request.Request(url, headers={
            "apikey": SUPABASE_ANON_KEY,
            "Authorization": f"Bearer {SUPABASE_ANON_KEY}"
        })
        local = OUT_DIR / f"real_{i}.jpg"
        try:
            with urllib.request.urlopen(req, timeout=20) as resp:
                data = resp.read()
            local.write_bytes(data)
            results.append({"path": path, "local": str(local), "bytes": len(data), "ok": True})
        except Exception as e:
            results.append({"path": path, "ok": False, "error": str(e)})
    return results


def ocr(image_path):
    """Runs tesseract on an image, returns (raw_text, mean_confidence)."""
    text = subprocess.run(
        ["tesseract", str(image_path), "-", "--psm", "11"],
        capture_output=True, text=True, timeout=30
    ).stdout.strip()
    tsv = subprocess.run(
        ["tesseract", str(image_path), "-", "--psm", "11", "tsv"],
        capture_output=True, text=True, timeout=30
    ).stdout
    confs = []
    for line in tsv.splitlines()[1:]:
        cols = line.split("\t")
        if len(cols) >= 11:
            try:
                c = float(cols[10])
                if c >= 0:
                    confs.append(c)
            except ValueError:
                pass
    mean_conf = sum(confs) / len(confs) if confs else None
    return text, mean_conf


def run_real_photo_ocr():
    downloads = download_real_photos()
    report = []
    for d in downloads:
        if not d["ok"]:
            report.append(d)
            continue
        text, conf = ocr(d["local"])
        report.append({**d, "ocr_text": text, "ocr_mean_word_conf": conf})
    return report


# ---------------- synthetic degradation grid ----------------

def make_synthetic_grid():
    from PIL import Image, ImageDraw, ImageFont, ImageFilter
    import random

    words = ["HAVELLS", "V-GUARD", "VELORA PRIME"]
    rotations = [0, 5, 15, 25]
    blurs = [0, 1, 2, 4]
    contrasts = ["high", "low"]  # high = black on white, low = light-grey embossed-plastic look

    font_path = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
    font = ImageFont.truetype(font_path, 60)

    cases = []
    for word in words:
        for rot in rotations:
            for blur in blurs:
                for contrast in contrasts:
                    img = Image.new("RGB", (700, 220), "white")
                    draw = ImageDraw.Draw(img)
                    fill = (40, 40, 40) if contrast == "high" else (170, 170, 165)
                    bg = (255, 255, 255) if contrast == "high" else (205, 205, 200)
                    img = Image.new("RGB", (700, 220), bg)
                    draw = ImageDraw.Draw(img)
                    draw.text((40, 70), word, fill=fill, font=font)
                    if rot:
                        img = img.rotate(rot, expand=True, fillcolor=bg)
                    if blur:
                        img = img.filter(ImageFilter.GaussianBlur(blur))
                    name = f"syn_{word.replace(' ', '_').replace('-', '')}_{rot}deg_{blur}blur_{contrast}.png"
                    path = OUT_DIR / name
                    img.save(path)
                    cases.append({"word": word, "rotation": rot, "blur": blur, "contrast": contrast, "local": str(path)})
    return cases


def normalize(s):
    return "".join(ch for ch in s.upper() if ch.isalnum())


def run_synthetic_ocr():
    cases = make_synthetic_grid()
    report = []
    for c in cases:
        text, conf = ocr(c["local"])
        expected_norm = normalize(c["word"])
        found_norm = normalize(text)
        exact_hit = expected_norm in found_norm
        report.append({**c, "ocr_text": text.replace("\n", " | "), "ocr_mean_word_conf": conf, "exact_substring_match": exact_hit})
    return report


if __name__ == "__main__":
    print("=== REAL PHOTOS: product id=5, Havells Velora Prime Storage Water Heater ===")
    real_report = run_real_photo_ocr()
    for r in real_report:
        print(json.dumps(r, indent=None))

    print("\n=== SYNTHETIC DEGRADATION GRID ===")
    syn_report = run_synthetic_ocr()
    for r in syn_report:
        print(json.dumps(r, indent=None))

    Path("real_photo_ocr_report.json").write_text(json.dumps(real_report, indent=2))
    Path("synthetic_ocr_report.json").write_text(json.dumps(syn_report, indent=2))

    # Quick summary stats for synthetic grid, by degradation dimension.
    print("\n=== SYNTHETIC SUMMARY ===")
    for word in ["HAVELLS", "V-GUARD", "VELORA PRIME"]:
        subset = [r for r in syn_report if r["word"] == word]
        hits = sum(1 for r in subset if r["exact_substring_match"])
        print(f"{word}: {hits}/{len(subset)} exact substring matches across all rotation/blur/contrast combos")
    for blur in [0, 1, 2, 4]:
        subset = [r for r in syn_report if r["blur"] == blur]
        hits = sum(1 for r in subset if r["exact_substring_match"])
        print(f"blur={blur}px: {hits}/{len(subset)} exact matches")
    for rot in [0, 5, 15, 25]:
        subset = [r for r in syn_report if r["rotation"] == rot]
        hits = sum(1 for r in subset if r["exact_substring_match"])
        print(f"rotation={rot}deg: {hits}/{len(subset)} exact matches")
    for contrast in ["high", "low"]:
        subset = [r for r in syn_report if r["contrast"] == contrast]
        hits = sum(1 for r in subset if r["exact_substring_match"])
        print(f"contrast={contrast}: {hits}/{len(subset)} exact matches")
