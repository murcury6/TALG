"""Download the cited public papers and retain URL/version/checksum provenance."""
import hashlib
import json
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
from urllib.request import Request, urlopen

folder = Path(__file__).resolve().parent
sources = json.loads((folder / "sources.json").read_text(encoding="utf-8"))


def fetch(source):
    path = folder / source["filename"]
    download_url = source.get("download_url", source["pdf_url"])
    if path.exists():
        data = path.read_bytes()
        final_url = download_url
    else:
        request = Request(download_url, headers={"User-Agent": "TALG-academic-research/1.0"})
        with urlopen(request, timeout=45) as response:
            data = response.read()
            final_url = response.url
        if source.get("format", "pdf") == "pdf" and not data.startswith(b"%PDF-"):
            raise ValueError("Not a PDF: " + source["filename"])
        path.write_bytes(data)
    if source.get("format", "pdf") == "pdf" and not data.startswith(b"%PDF-"):
        raise ValueError("Invalid local PDF: " + str(path))
    return dict(source, resolved_url=final_url, bytes=len(data),
                sha256=hashlib.sha256(data).hexdigest(), verified_at=datetime.now(timezone.utc).isoformat())


if __name__ == "__main__":
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(fetch, sources))
    (folder / "manifest.json").write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
    for result in results:
        print(result["filename"], result["bytes"], result["sha256"])
