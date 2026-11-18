"""
ocr.py – Texterkennung für Bilder (Screenshots) und PDF-Dateien über Tesseract.

* Bilder: der erkannte Text wird nur für die Suche verwendet, das Bild bleibt unverändert.
* PDFs: Seiten ohne eigenen Text (Scans) werden gerendert, per Tesseract erkannt und als Seite mit
  unsichtbarer Textebene neu zusammengesetzt. Seiten, die bereits Text enthalten, bleiben
  unverändert (kein Qualitätsverlust, keine Rasterung). Das Ergebnis ersetzt das Original.

Systemvoraussetzung: das Programm `tesseract` (mit Sprachdaten, z. B. `deu` und `eng`).
Python-Pakete: pypdf; zum Rendern pypdfium2 – ersatzweise wird `pdftoppm` (poppler-utils) genutzt.
Umgebungsvariablen: NOTIZEN_OCR_LANGS (Standard "deu+eng"), NOTIZEN_TESSERACT (Pfad),
NOTIZEN_OCR_DPI (Standard 300).
"""
from __future__ import annotations

import io
import os
import shutil
import subprocess
import tempfile
import time
from pathlib import Path

MIN_WORD_CONF = 50          # Wörter mit geringerer Erkennungssicherheit werden nicht indexiert
PAGE_TEXT_MIN_CHARS = 20    # ab so vielen Buchstaben/Ziffern gilt eine PDF-Seite als „hat Text“
MAX_PIXELS_SIDE = 4500      # längste Kante beim Rendern einer PDF-Seite
MAX_OCR_CHARS = 400_000
TESSERACT_TIMEOUT = 240
RENDER_TIMEOUT = 120
DEFAULT_LANGS = "deu+eng"


class OcrError(Exception):
    """Die Datei kann nicht verarbeitet werden (dauerhaft, kein erneuter Versuch)."""


class OcrUnavailable(OcrError):
    """Tesseract ist nicht installiert."""


class OcrAborted(Exception):
    """Der Server wird beendet; die Arbeit wird beim nächsten Start wiederholt."""


# --------------------------------------------------------------------------- #
# Tesseract
# --------------------------------------------------------------------------- #
_exe_cache: tuple[float, str | None] = (0.0, None)
_langs_cache: tuple[str, str] | None = None


def tesseract_path() -> str | None:
    """Pfad zu tesseract (alle 30 s neu geprüft, damit eine spätere Installation ohne Neustart greift)."""
    global _exe_cache
    t, exe = _exe_cache
    if time.monotonic() - t < 30 and (exe or t):
        return exe
    exe = os.environ.get("NOTIZEN_TESSERACT") or shutil.which("tesseract")
    _exe_cache = (time.monotonic(), exe)
    return exe


def available() -> bool:
    return tesseract_path() is not None


def languages() -> str:
    """Gewünschte Sprachen (Umgebungsvariable) gefiltert auf tatsächlich installierte Sprachdaten."""
    global _langs_cache
    exe = tesseract_path()
    if not exe:
        raise OcrUnavailable("Tesseract ist nicht installiert.")
    wanted = os.environ.get("NOTIZEN_OCR_LANGS", DEFAULT_LANGS)
    if _langs_cache and _langs_cache[0] == wanted + exe:
        return _langs_cache[1]
    try:
        out = subprocess.run([exe, "--list-langs"], capture_output=True, text=True, timeout=30).stdout
    except (OSError, subprocess.SubprocessError) as exc:
        raise OcrUnavailable(f"Tesseract lässt sich nicht starten: {exc}") from exc
    installed = {ln.strip() for ln in out.splitlines()[1:] if ln.strip()}
    chosen = [lang for lang in wanted.split("+") if lang in installed]
    if not chosen:
        fallback = sorted(installed - {"osd"})
        if not fallback:
            raise OcrUnavailable("Für Tesseract sind keine Sprachdaten installiert.")
        chosen = [fallback[0]]
    result = "+".join(chosen)
    _langs_cache = (wanted + exe, result)
    return result


def _run_tesseract(args: list[str]) -> subprocess.CompletedProcess:
    exe = tesseract_path()
    if not exe:
        raise OcrUnavailable("Tesseract ist nicht installiert.")
    env = {**os.environ, "OMP_THREAD_LIMIT": os.environ.get("NOTIZEN_OCR_THREADS", "2")}
    try:
        proc = subprocess.run([exe, *args], capture_output=True, timeout=TESSERACT_TIMEOUT, env=env)
    except subprocess.TimeoutExpired as exc:
        raise OcrError("Die Texterkennung hat zu lange gedauert.") from exc
    except OSError as exc:
        raise OcrUnavailable(f"Tesseract lässt sich nicht starten: {exc}") from exc
    if proc.returncode != 0:
        raise OcrError("Tesseract-Fehler: " + proc.stderr.decode("utf-8", "replace")[-300:])
    return proc


def text_from_tsv(tsv: str, min_conf: int = MIN_WORD_CONF) -> str:
    """Baut Fließtext (zeilenweise) aus Tesseract-TSV; unsichere Treffer (z. B. aus Fotos) entfallen."""
    lines: dict[tuple, list[str]] = {}
    for row in tsv.splitlines()[1:]:
        cols = row.split("\t", 11)
        if len(cols) < 12 or cols[0] != "5":
            continue
        try:
            conf = float(cols[10])
        except ValueError:
            continue
        word = cols[11].strip()
        if conf < min_conf or not word or not any(ch.isalnum() for ch in word):
            continue
        lines.setdefault((cols[1], cols[2], cols[3], cols[4]), []).append(word)
    return "\n".join(" ".join(words) for words in lines.values())


# --------------------------------------------------------------------------- #
# Bilder
# --------------------------------------------------------------------------- #
def _prepare_image(data: bytes):
    from PIL import Image, ImageOps, ImageStat
    Image.MAX_IMAGE_PIXELS = 60_000_000
    im = Image.open(io.BytesIO(data))
    im.load()
    im = ImageOps.exif_transpose(im)
    if im.mode in ("RGBA", "LA", "PA") or (im.mode == "P" and "transparency" in im.info):
        rgba = im.convert("RGBA")
        bg = Image.new("RGB", rgba.size, (255, 255, 255))
        bg.paste(rgba, mask=rgba.split()[-1])
        im = bg
    im = im.convert("L")
    if max(im.size) < 1200:  # kleine Screenshots hochskalieren – Tesseract braucht ausreichend Zeichenhöhe
        im = im.resize((im.width * 2, im.height * 2), Image.LANCZOS)
    if ImageStat.Stat(im).mean[0] < 110:  # heller Text auf dunklem Grund (Dark-Mode-Screenshots)
        im = ImageOps.invert(im)
    return im


def ocr_image(data: bytes) -> str:
    """Erkennt Text in einem Bild (leerer String, wenn keiner gefunden wurde)."""
    try:
        im = _prepare_image(data)
    except Exception as exc:  # noqa: BLE001
        raise OcrError("Das Bild kann nicht gelesen werden.") from exc
    with tempfile.TemporaryDirectory(prefix="notizen-ocr-") as tmp:
        src = Path(tmp) / "in.png"
        im.save(src, "PNG")
        proc = _run_tesseract([str(src), "stdout", "-l", languages(), "tsv"])
    return text_from_tsv(proc.stdout.decode("utf-8", "replace"))[:MAX_OCR_CHARS]


# --------------------------------------------------------------------------- #
# PDF
# --------------------------------------------------------------------------- #
class _Renderer:
    """Rendert PDF-Seiten zu PIL-Bildern: pypdfium2 bevorzugt, sonst pdftoppm (poppler)."""

    def __init__(self, data: bytes, tmp: Path):
        self._doc = None
        self._tmp = tmp
        try:
            import pypdfium2 as pdfium
            self._doc = pdfium.PdfDocument(data)
        except ImportError:
            exe = shutil.which("pdftoppm")
            if not exe:
                raise OcrUnavailable("Zum Rendern von PDFs wird pypdfium2 (pip) oder pdftoppm (poppler) benötigt.")
            self._pdftoppm = exe
            self._file = tmp / "in.pdf"
            self._file.write_bytes(data)
        except Exception as exc:  # noqa: BLE001
            raise OcrError("Die PDF-Datei kann nicht gerendert werden.") from exc

    def render(self, index: int, dpi: float):
        from PIL import Image
        if self._doc is not None:
            try:
                bitmap = self._doc[index].render(scale=dpi / 72)
                return bitmap.to_pil().convert("RGB")
            except Exception as exc:  # noqa: BLE001
                raise OcrError(f"Seite {index + 1} kann nicht gerendert werden.") from exc
        base = self._tmp / f"page{index}"
        try:
            subprocess.run([self._pdftoppm, "-singlefile", "-png", "-r", str(int(dpi)),
                            "-f", str(index + 1), "-l", str(index + 1), str(self._file), str(base)],
                           capture_output=True, timeout=RENDER_TIMEOUT, check=True)
            with Image.open(str(base) + ".png") as im:
                return im.convert("RGB")
        except (subprocess.SubprocessError, OSError) as exc:
            raise OcrError(f"Seite {index + 1} kann nicht gerendert werden.") from exc

    def close(self) -> None:
        if self._doc is not None:
            try:
                self._doc.close()
            except Exception:  # noqa: BLE001
                pass


def _alnum_count(text: str) -> int:
    return sum(1 for ch in text if ch.isalnum())


def check_pdf(data: bytes) -> int:
    """Prüft beim Hochladen schnell: gültig, nicht verschlüsselt, Seitenzahl im Rahmen. Gibt die Seitenzahl zurück."""
    import core
    if b"%PDF-" not in data[:1024]:
        raise OcrError("Die Datei ist keine gültige PDF-Datei.")
    try:
        from pypdf import PdfReader
        reader = PdfReader(io.BytesIO(data))
        if reader.is_encrypted:
            raise OcrError("Passwortgeschützte PDF-Dateien werden nicht unterstützt.")
        pages = len(reader.pages)
    except OcrError:
        raise
    except ImportError as exc:
        raise OcrError("Für PDF-Dateien wird das Paket 'pypdf' benötigt (pip install pypdf).") from exc
    except Exception as exc:  # noqa: BLE001
        raise OcrError("Die Datei ist keine gültige PDF-Datei.") from exc
    if pages < 1:
        raise OcrError("Die PDF-Datei enthält keine Seiten.")
    if pages > core.MAX_PDF_PAGES:
        raise OcrError(f"Die PDF-Datei hat zu viele Seiten (max. {core.MAX_PDF_PAGES}).")
    return pages


def ocr_pdf(data: bytes, should_stop=lambda: False) -> tuple[bytes, str, int, int]:
    """Gibt (neue PDF-Daten, Volltext, Seitenzahl, Anzahl erkannter Scan-Seiten) zurück."""
    from pypdf import PdfReader, PdfWriter
    pages_total = check_pdf(data)
    reader = PdfReader(io.BytesIO(data))
    writer = PdfWriter()
    texts: list[str] = []
    scanned = 0
    langs = languages()
    with tempfile.TemporaryDirectory(prefix="notizen-ocr-") as tmpdir:
        tmp = Path(tmpdir)
        renderer = None
        try:
            for i, page in enumerate(reader.pages):
                if should_stop():
                    raise OcrAborted()
                try:
                    existing = (page.extract_text() or "").strip()
                except Exception:  # noqa: BLE001
                    existing = ""
                if _alnum_count(existing) >= PAGE_TEXT_MIN_CHARS:
                    writer.add_page(page)          # Seite hat bereits Text: unverändert übernehmen
                    texts.append(existing)
                    continue
                if renderer is None:
                    renderer = _Renderer(data, tmp)
                try:
                    longest_in = max(float(page.mediabox.width), float(page.mediabox.height)) / 72
                except Exception:  # noqa: BLE001
                    longest_in = 11.7
                dpi = float(os.environ.get("NOTIZEN_OCR_DPI", "300"))
                if longest_in > 0:
                    dpi = max(50.0, min(dpi, MAX_PIXELS_SIDE / longest_in))
                im = renderer.render(i, dpi)
                src, base = tmp / f"p{i}.jpg", tmp / f"p{i}"
                im.save(src, "JPEG", quality=82, dpi=(round(dpi), round(dpi)))
                _run_tesseract([str(src), str(base), "-l", langs, "pdf", "tsv"])
                try:
                    one = PdfReader(io.BytesIO((tmp / f"p{i}.pdf").read_bytes()))
                    writer.add_page(one.pages[0])
                    texts.append(text_from_tsv((tmp / f"p{i}.tsv").read_text("utf-8", "replace")))
                except Exception as exc:  # noqa: BLE001
                    raise OcrError(f"Seite {i + 1}: Ergebnis der Texterkennung unbrauchbar.") from exc
                scanned += 1
                for f in (src, tmp / f"p{i}.pdf", tmp / f"p{i}.tsv"):
                    f.unlink(missing_ok=True)
        finally:
            if renderer is not None:
                renderer.close()
        out = io.BytesIO()
        writer.write(out)
    return out.getvalue(), "\n\n".join(t for t in texts if t)[:MAX_OCR_CHARS], pages_total, scanned
