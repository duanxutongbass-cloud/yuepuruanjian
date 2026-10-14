"""Strict structural PDF validation, in a cancellable, time-bounded worker.

Does not render, sanitize, split or establish distribution permission.
Original PDF bytes are never rewritten. Geometry describes original source pages.
"""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time

from .errors import checkpoint, require

PDF_LIMIT = 256 * 1024 * 1024
MAX_PAGES = 10_000


def inspect_pdf(data, cancel=None, timeout=30):
    require(0 < len(data) <= PDF_LIMIT, "PDF size outside supported limits")
    checkpoint(cancel)
    with tempfile.TemporaryDirectory(prefix="score-pdf-") as temp:
        path = Path(temp) / "source.pdf"
        path.write_bytes(data)
        # Use the selected interpreter and this exact worker, never a shell.
        worker = Path(__file__).with_name("pdf_worker.py")
        process = subprocess.Popen([sys.executable, str(worker), str(path)],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        started = time.monotonic()
        try:
            while True:
                checkpoint(cancel)
                require(time.monotonic() - started < timeout, "PDF validation timed out")
                try:
                    out, err = process.communicate(timeout=0.05)
                    break
                except subprocess.TimeoutExpired:
                    continue
            require(process.returncode == 0, "PDF rejected: " + err.decode("utf-8", "replace")[-600:])
            checkpoint(cancel)
            pages = json.loads(out)
            require(0 < len(pages) <= MAX_PAGES, "Invalid PDF page count")
            return {"sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data),
                    "pageCount": len(pages), "pages": pages}
        finally:
            if process.poll() is None:
                process.kill()
            process.communicate()


def read_pdf(path, cancel=None):
    data = bytearray()
    with Path(path).open("rb") as stream:
        while True:
            checkpoint(cancel)
            block = stream.read(65536)
            if not block:
                break
            require(len(data) + len(block) <= PDF_LIMIT, "PDF too large")
            data.extend(block)
    return bytes(data)
