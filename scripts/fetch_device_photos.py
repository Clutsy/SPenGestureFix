#!/usr/bin/env python3
"""Builds the Samsung Galaxy Note photo database for the SPGF desktop GUI.

For every Galaxy Note generation the script fetches the best *free* product
image it can find — a curated Wikimedia Commons file when one is known, a
scored Commons search as a fallback — then renders it into the two assets the
GUI uses:

    phone_<key>.png   transparent render of the device (trimmed, hi-res)
    photo_<key>.png   ready-made card: rounded panel, soft glow, drop shadow

Both are generated at 2x so the GUI stays crisp on DPI-scaled displays, and
``photo_credits.json`` keeps author, licence and source page for attribution.

Usage
-----
    python scripts/fetch_device_photos.py                 # refresh everything
    python scripts/fetch_device_photos.py --only note3    # one generation
    python scripts/fetch_device_photos.py --list          # show the database
    python scripts/fetch_device_photos.py --force         # ignore the cache

The fetch is best-effort: a device that cannot be downloaded keeps its previous
image, and if all else fails a clean vector-style silhouette is generated so the
GUI never shows an empty card. Nothing is fetched at GUI runtime, which is what
keeps the bundle portable and offline-friendly.
"""
from __future__ import annotations

import argparse
import io
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

try:  # Pillow is required here (build-time only), not by the GUI.
    from PIL import Image, ImageDraw, ImageFilter, ImageOps
except ImportError:  # pragma: no cover - dependency hint
    print("This tool needs Pillow:  python -m pip install pillow", file=sys.stderr)
    raise SystemExit(2)

if hasattr(sys.stdout, "reconfigure"):
    try:  # Windows consoles default to cp1252 and choke on non-ASCII titles.
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (OSError, ValueError):
        pass

OUT = Path(__file__).resolve().parent / "gui_assets"
OUT.mkdir(parents=True, exist_ok=True)

# 2x assets: the GUI asks customtkinter for 230x260 / 340x420 logical pixels.
CARD_W, CARD_H = 460, 520
HERO_H = 1120
LANCZOS = getattr(getattr(Image, "Resampling", Image), "LANCZOS")
UA = "SPenGestureFix-GUI/2.0 (device photo fetcher; one-off local build)"

# ------------------------------------------------------------------ database
# Each entry: display name, exact Commons files to try first (best-known clean
# product shots, in order) and the search terms used when none of them work.
DEVICES: "dict[str, dict[str, object]]" = {
    "note1": {
        "name": "Galaxy Note (2011)",
        "model": "GT-N7000",
        "files": ["File:Samsung Galaxy Note.png"],
        "search": ["Samsung Galaxy Note GT-N7000", "Samsung Galaxy Note N7000"],
    },
    "note2": {
        "name": "Galaxy Note II",
        "model": "GT-N7100",
        "files": ["File:Samsung Galaxy Note II.png"],
        "search": ["Samsung Galaxy Note II", "Samsung Galaxy Note 2 N7100"],
    },
    "note3": {
        "name": "Galaxy Note 3",
        "model": "SM-N900",
        "files": ["File:Samsung Galaxy Note 3.png"],
        "search": ["Samsung Galaxy Note 3", "Samsung Galaxy Note 3 SM-N900"],
    },
    "note4": {
        "name": "Galaxy Note 4",
        "model": "SM-N910",
        "files": ["File:Samsung Galaxy Note 4.png"],
        "search": ["Samsung Galaxy Note 4 SM-N910", "Samsung Galaxy Note 4"],
    },
    "noteedge": {
        "name": "Galaxy Note Edge",
        "model": "SM-N915",
        "files": ["File:Samsung Galaxy Note Edge.png"],
        "search": ["Samsung Galaxy Note Edge SM-N915", "Samsung Galaxy Note Edge front"],
        # "edge" is this device's name: it must not be scored as a wrong model.
        "allow": ["edge"],
    },
    "note5": {
        "name": "Galaxy Note 5",
        "model": "SM-N920",
        "files": [
            "File:Samsung Galaxy Note 5.png",
            "File:Samsung Galaxy Note5 (White Pearl) - Front.jpg",
        ],
        "search": ["Samsung Galaxy Note 5 SM-N920", "Samsung Galaxy Note 5"],
    },
    "note7": {
        "name": "Galaxy Note7",
        "model": "SM-N930",
        "files": [
            "File:Samsung Galaxy Note 7.png",
            "File:Samsung Galaxy Note 7 face 20161010.jpg",
        ],
        "search": ["Samsung Galaxy Note 7 SM-N930", "Samsung Galaxy Note 7 front"],
    },
    "note8": {
        "name": "Galaxy Note 8",
        "model": "SM-N950",
        "files": ["File:Samsung Galaxy Note 8.png"],
        "search": ["Samsung Galaxy Note 8 SM-N950", "Samsung Galaxy Note 8 2017"],
    },
    "note9": {
        "name": "Galaxy Note 9",
        "model": "SM-N960",
        "files": [
            "File:Samsung Galaxy Note 9.png",
            "File:SAMSUNG SAMSUNG GALXY NOTE 9 ASCENSOR ELEVADOR.jpg",
        ],
        "search": ["Samsung Galaxy Note 9 SM-N960", "Samsung Galaxy Note 9"],
    },
    "note10": {
        "name": "Galaxy Note 10 / 10+",
        "model": "SM-N970 / SM-N975",
        "files": [
            "File:Samsung Galaxy Note 10 Phone.png",
            "File:Samsung Galaxy Note 10+.png",
        ],
        "search": ["Samsung Galaxy Note 10 Plus SM-N975", "Samsung Galaxy Note 10"],
    },
    "note20": {
        "name": "Galaxy Note 20 / Ultra",
        "model": "SM-N980 / SM-N985",
        "files": [
            "File:Samsung Galaxy Note 20 Ultra.png",
            "File:Samsung Galaxy Note 20 front.png",
        ],
        "search": ["Samsung Galaxy Note 20 Ultra SM-N986", "Samsung Galaxy Note 20"],
    },
}

# Words that usually mean "not a clean product shot of this exact phone".
BAD_WORDS = (
    "unit_mm", "animated", "1px", "edit this", "logo", "icon", "map", "chart",
    "screenshot", "screenshots", "home screen", "wallpaper", "ui", "benchmark",
    "box", "boxart", "packaging", "unboxing", "charger", "cable", "connector",
    "case", "cover", "keyboard", "keychain", "battery", "teardown", "internal",
    "board", "antenna", "spen", "s pen", "s-pen", "stylus", "对比", "背面",
)
BAD_MODEL_WORDS = ("neo", "edge", "lite", "fe", "tab", "8.0", "10.1", "12.2", "redmi", "tablet")


def say(text: str) -> None:
    print(text, flush=True)


# Wikimedia answers 429 when a batch of API + image requests lands too fast.
# A tiny global throttle plus bounded backoff makes a full refresh reliable.
THROTTLE_SECONDS = 1.1
_RETRY_DELAYS = (6, 18, 45)
_last_request = 0.0


def _request(url: str) -> bytes:
    global _last_request
    last_error: Exception = OSError("request never attempted")
    for attempt, delay in enumerate((0,) + _RETRY_DELAYS):
        wait = THROTTLE_SECONDS - (time.monotonic() - _last_request) + delay
        if wait > 0:
            time.sleep(wait)
        try:
            request = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(request, timeout=30) as response:
                data = response.read()
            _last_request = time.monotonic()
            return data
        except urllib.error.HTTPError as error:
            _last_request = time.monotonic()
            last_error = error
            if error.code != 429:
                raise
            if attempt == len(_RETRY_DELAYS):
                break
        except (OSError, urllib.error.URLError) as error:
            _last_request = time.monotonic()
            last_error = error
            if attempt == len(_RETRY_DELAYS):
                break
    raise last_error


def commons(params: dict) -> dict:
    url = "https://commons.wikimedia.org/w/api.php?" + urllib.parse.urlencode(params)
    return json.loads(_request(url).decode("utf-8"))


def strip_html(value: str) -> str:
    out, in_tag = [], False
    for ch in value or "":
        if ch == "<":
            in_tag = True
        elif ch == ">":
            in_tag = False
        elif not in_tag:
            out.append(ch)
    return " ".join("".join(out).split())


def image_info(title: str, width: int) -> "dict | None":
    """Metadata + a downloadable URL for one Commons file."""
    try:
        data = commons(
            {
                "action": "query",
                "format": "json",
                "titles": title,
                "prop": "imageinfo",
                "iiprop": "url|extmetadata|size|mime",
                "iiurlwidth": str(width),
            }
        )
    except (OSError, ValueError, urllib.error.URLError):
        return None
    pages = (data.get("query") or {}).get("pages") or {}
    for page in pages.values():
        info = (page.get("imageinfo") or [None])[0]
        if not info:
            continue
        meta = info.get("extmetadata") or {}
        licence = strip_html((meta.get("LicenseShortName") or {}).get("value", ""))
        if not licence or "fair use" in licence.lower():
            continue
        mime = info.get("mime") or ""
        if mime in ("image/svg+xml",):
            continue
        return {
            "title": page.get("title", title),
            "url": info.get("thumburl") or info.get("url"),
            "width": info.get("width") or 0,
            "height": info.get("height") or 0,
            "mime": mime,
            "author": strip_html((meta.get("Artist") or {}).get("value", "")),
            "licence": licence,
            "licence_url": strip_html((meta.get("LicenseUrl") or {}).get("value", "")),
            "description_url": info.get("descriptionurl") or "",
        }
    return None


def score(candidate: dict, terms: list[str], wanted: str, allow: tuple = ()) -> float:
    """Heuristic rank for "is this a clean front render of THIS phone?"."""
    title = candidate["title"].lower()
    width, height = candidate["width"] or 1, candidate["height"] or 1
    points = 0.0

    # Portrait framing is the single strongest signal for a phone shot.
    ratio = height / max(width, 1)
    if 1.25 <= ratio <= 2.6:
        points += 40
    elif ratio >= 1.05:
        points += 18
    else:
        points -= 25

    # Frozen renders (transparent PNG) beat snapshots on a desk.
    if candidate["mime"] == "image/png":
        points += 22
    points += min(width * height, 4_000_000) / 400_000  # resolution (max +10)
    if min(width, height) < 320:
        points -= 30

    if any(bad in title for bad in BAD_WORDS):
        points -= 60
    if any(bad in title for bad in BAD_MODEL_WORDS if bad not in allow):
        points -= 45
    if wanted and wanted in title.replace(" ", ""):
        points += 18
    for term in terms:
        needles = [part for part in term.lower().split() if len(part) > 3]
        hits = sum(1 for needle in needles if needle in title)
        points += 3 * hits
    if "samsung" in title:
        points += 8
    return points


def search_candidates(terms: list[str], wanted: str, allow: tuple = (), limit: int = 20) -> list[dict]:
    seen: dict[str, dict] = {}
    for term in terms:
        try:
            data = commons(
                {
                    "action": "query",
                    "format": "json",
                    "generator": "search",
                    "gsrsearch": f"{term} filetype:bitmap",
                    "gsrnamespace": "6",
                    "gsrlimit": str(limit),
                    "prop": "imageinfo",
                    "iiprop": "url|extmetadata|size|mime",
                    "iiurlwidth": "1200",
                }
            )
        except (OSError, ValueError, urllib.error.URLError):
            continue
        for page in ((data.get("query") or {}).get("pages") or {}).values():
            info = (page.get("imageinfo") or [None])[0]
            if not info:
                continue
            meta = info.get("extmetadata") or {}
            licence = strip_html((meta.get("LicenseShortName") or {}).get("value", ""))
            if not licence or "fair use" in licence.lower():
                continue
            if not (info.get("thumburl") or info.get("url")):
                continue
            title = page.get("title", "")
            seen.setdefault(
                title,
                {
                    "title": title,
                    "url": info.get("thumburl") or info.get("url"),
                    "width": info.get("width") or 0,
                    "height": info.get("height") or 0,
                    "mime": info.get("mime") or "",
                    "author": strip_html((meta.get("Artist") or {}).get("value", "")),
                    "licence": licence,
                    "licence_url": strip_html((meta.get("LicenseUrl") or {}).get("value", "")),
                    "description_url": info.get("descriptionurl") or "",
                },
            )
    ranked = sorted(seen.values(), key=lambda c: score(c, terms, wanted, allow), reverse=True)
    return [c for c in ranked if score(c, terms, wanted, allow) > 40]


def pick_candidate(key: str, device: dict) -> "dict | None":
    """Curated files first, then the best-scoring Commons search result."""
    wanted = key.replace("note", "note ")
    for title in device["files"]:  # type: ignore[union-attr]
        info = image_info(title, 1200)
        if info:
            return info
    allow = tuple(device.get("allow") or ())  # type: ignore[union-attr]
    candidates = search_candidates(list(device["search"]), wanted, allow)  # type: ignore[arg-type]
    return candidates[0] if candidates else None


# ------------------------------------------------------------- image pipeline
def isolate_subject(image: Image.Image) -> Image.Image:
    """Returns the device on transparency, trimming any uniform background."""
    image = ImageOps.exif_transpose(image).convert("RGBA")
    alpha = image.split()[3]
    if alpha.getextrema()[0] < 250:  # already has real transparency
        return image.crop(alpha.getbbox() or (0, 0, image.width, image.height))

    rgb = image.convert("RGB")
    sw, sh = rgb.size
    # The flood fill runs on a downscaled copy (16x less work), then the mask is
    # scaled back up: background edges are soft anyway after the blur.
    small = rgb.copy()
    small.thumbnail((420, 420), LANCZOS)
    w, h = small.size
    probes = [(1, 1), (w - 2, 1), (1, h - 2), (w - 2, h - 2)]
    corners = [small.getpixel(p) for p in probes]
    ref = corners[0]
    if not all(max(abs(a - b) for a, b in zip(ref, c)) < 26 for c in corners):
        return image
    try:
        from PIL import ImageChops

        flat = Image.new("RGB", small.size, ref)
        diff = ImageChops.difference(small, flat).convert("L").point(lambda v: 255 if v > 38 else 0)
        # Flood from the borders so a dark phone body is never erased.
        mask = Image.new("L", small.size, 0)
        pixels = diff.load()
        reach = mask.load()
        stack = [(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)]
        while stack:
            x, y = stack.pop()
            if x < 0 or y < 0 or x >= w or y >= h or reach[x, y] or pixels[x, y]:
                continue
            reach[x, y] = 255
            stack.append((x + 1, y))
            stack.append((x - 1, y))
            stack.append((x, y + 1))
            stack.append((x, y - 1))
        share = sum(1 for value in mask.getdata() if value) / (w * h)
        if not 0.06 < share < 0.93:
            return image
        cut = ImageOps.invert(mask.resize((sw, sh), LANCZOS))
        cut = cut.filter(ImageFilter.GaussianBlur(1.2)).point(lambda v: 255 if v > 128 else v * 2)
        image.putalpha(cut)
        return image.crop(cut.getbbox() or (0, 0, sw, sh))
    except Exception:
        return image


def fit(image: Image.Image, box: tuple[int, int]) -> Image.Image:
    copy = image.copy()
    copy.thumbnail(box, LANCZOS)
    return copy


def radial_glow(size: tuple[int, int], color: tuple[int, int, int], strength: int) -> Image.Image:
    w, h = size
    small = Image.new("L", (w // 8 or 1, h // 8 or 1), 0)
    draw = ImageDraw.Draw(small)
    draw.ellipse((2, 6, small.width - 2, small.height - 4), fill=strength)
    small = small.filter(ImageFilter.GaussianBlur(9)).resize((w, h), LANCZOS)
    glow = Image.new("RGBA", (w, h), color + (0,))
    glow.putalpha(small)
    return glow


def rounded_mask(size: tuple[int, int], radius: int) -> Image.Image:
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius, fill=255)
    return mask


def compose_card(subject: Image.Image) -> Image.Image:
    """Rounded panel + accent glow + shadowed device: the GUI's card image."""
    w, h = CARD_W, CARD_H
    panel = Image.new("RGBA", (w, h), (18, 22, 29, 255))
    top = Image.new("RGBA", (w, h), (31, 37, 48, 255))
    gradient = Image.new("L", (1, h))
    for y in range(h):
        gradient.putpixel((0, y), int(255 * (y / h) ** 1.2))
    panel = Image.composite(top, panel, gradient.resize((w, h)))

    device = fit(subject, (w - 92, h - 92))
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    x = (w - device.width) // 2
    y = (h - device.height) // 2
    layer.alpha_composite(radial_glow((w, h), (59, 130, 246), 44))
    layer.alpha_composite(pad_shadow(device, layer.size, x, y))
    layer.alpha_composite(device, (x, y))

    mask = rounded_mask((w, h), 36)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out.paste(panel, (0, 0))
    out.alpha_composite(layer)
    stroke = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(stroke).rounded_rectangle(
        (0, 0, w - 1, h - 1), 36, outline=(58, 68, 88, 255), width=2
    )
    out.alpha_composite(stroke)
    out.putalpha(mask)
    return out


def pad_shadow(device: Image.Image, size: tuple[int, int], x: int, y: int) -> Image.Image:
    """Blurred silhouette of the device, drawn behind it."""
    canvas = Image.new("L", size, 0)
    canvas.paste(device.split()[3], (x, y))
    shadow = Image.new("RGBA", size, (0, 0, 0, 0))
    shadow.putalpha(canvas.point(lambda v: int(v * 0.55)))
    return shadow.filter(ImageFilter.GaussianBlur(18)).transform(
        size, Image.AFFINE, (1, 0, 0, 0, 1, -12)
    )


def fallback_render(name: str) -> Image.Image:
    """Vector-style phone silhouette: the card is never empty, even offline."""
    w, h = 300, 640
    image = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((6, 6, w - 6, h - 6), 46, fill=(233, 237, 244, 255))
    draw.rounded_rectangle((20, 26, w - 20, h - 26), 34, fill=(16, 19, 24, 255))
    draw.rounded_rectangle((24, 30, w - 24, h - 30), 30, fill=(24, 31, 42, 255))
    draw.rounded_rectangle((w // 2 - 26, 40, w // 2 + 26, 50), 5, fill=(70, 80, 96, 255))
    draw.ellipse((w - 56, 58, w - 40, 74), fill=(48, 58, 74, 255))
    draw.rounded_rectangle((44, 150, w - 44, 176), 8, fill=(59, 130, 246, 220))
    draw.rounded_rectangle((44, 196, w - 90, 216), 8, fill=(90, 100, 120, 200))
    draw.rounded_rectangle((44, 232, w - 120, 252), 8, fill=(90, 100, 120, 160))
    return image


# ------------------------------------------------------------------ the build
def build(key: str, device: dict, force: bool, offline: bool) -> bool:
    photo_path = OUT / f"photo_{key}.png"
    hero_path = OUT / f"phone_{key}.png"
    if not force and photo_path.exists() and hero_path.exists():
        say(f"  = {key}: cached (use --force to refresh)")
        return True

    candidate = None if offline else pick_candidate(key, device)
    subject = None
    if candidate and candidate.get("url"):
        try:
            raw = _request(candidate["url"])
            subject = isolate_subject(Image.open(io.BytesIO(raw)))
            say(
                f"  + {key}: {candidate['title']} "
                f"({candidate['width']}x{candidate['height']}, {candidate['licence']})"
            )
        except (OSError, ValueError, urllib.error.URLError) as error:
            say(f"  ! {key}: download failed ({error}); using fallback")
            candidate = None
    elif not offline:
        say(f"  ! {key}: no suitable free image found; using fallback")

    if subject is None or min(subject.size) < 40:
        subject = fallback_render(str(device.get("name", key)))

    hero = fit(subject, (HERO_H, HERO_H))
    hero.save(hero_path)
    compose_card(subject).save(photo_path)

    if candidate:
        credit = {
            "name": device.get("name", key),
            "model": device.get("model", ""),
            "title": candidate["title"],
            "credit": f"{candidate['author']} — {candidate['licence']}".strip(" —"),
            "author": candidate["author"],
            "license": candidate["licence"],
            "license_url": candidate["licence_url"],
            "source": candidate["description_url"],
            "file": Path(candidate["url"]).name,
            "fetched": time.strftime("%Y-%m-%d"),
        }
    else:
        credit = {
            "name": device.get("name", key),
            "model": device.get("model", ""),
            "title": "",
            "credit": "Generated placeholder (no free photo available)",
            "author": "",
            "license": "",
            "license_url": "",
            "source": "",
            "file": "",
            "fallback": True,
            "fetched": time.strftime("%Y-%m-%d"),
        }
    credits = load_credits()
    credits[key] = credit
    save_credits(credits)
    return candidate is not None


def load_credits() -> dict:
    path = OUT / "photo_credits.json"
    if path.exists():
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return {}
    return {}


def save_credits(credits: dict) -> None:
    (OUT / "photo_credits.json").write_text(
        json.dumps(credits, ensure_ascii=False, indent=2), encoding="utf-8"
    )


def main(argv: "list[str] | None" = None) -> int:
    parser = argparse.ArgumentParser(description="Refresh the SPGF device photo database.")
    parser.add_argument("--only", action="append", default=[], help="generation key (repeatable)")
    parser.add_argument("--force", action="store_true", help="re-download even if cached")
    parser.add_argument("--offline", action="store_true", help="rebuild placeholders only")
    parser.add_argument("--list", action="store_true", help="print the database and exit")
    args = parser.parse_args(argv)

    if args.list:
        for key, device in DEVICES.items():
            marker = "photo" if (OUT / f"photo_{key}.png").exists() else "  -  "
            say(f"{marker}  {key:<8} {device['name']}  ({device['model']})")
        return 0

    keys = args.only or list(DEVICES)
    unknown = [key for key in keys if key not in DEVICES]
    if unknown:
        say(f"unknown keys: {', '.join(unknown)}")
        return 2

    say(f"photo database -> {OUT}")
    missing = [key for key in keys if not build(key, DEVICES[key], args.force, args.offline)]
    generated = len(keys) - len(missing)
    say(f"done: {generated}/{len(keys)} real photos")
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
