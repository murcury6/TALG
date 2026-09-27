"""Download pinned BGE-large ONNX weights; verify upstream checksums, no remote code."""
import hashlib
import json
from pathlib import Path
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
MODEL = "Xenova/bge-large-en-v1.5"
REVISION = "dfeef6070b90658e1b391a6940efdb0925c1de6f"
FILES = {
    "config.json": "69b09cfd83efe36a439af75e2b77d6518e6d0a55",
    "tokenizer.json": "688882a79f44442ddc1f60d70334a7ff5df0fb47",
    "tokenizer_config.json": "37fca74771bc76a8e01178ce3a6055a0995f8093",
    "onnx/model_quantized.onnx": "4842b56e233be1cc74770f57f63b1ebb6cf357cca3dd73fcdec35c019f8a5d6e",
}


def main():
    folder = ROOT / "work/news-encoder-v2/bge-large"
    folder.mkdir(parents=True, exist_ok=True)
    manifest = {"model": MODEL, "revision": REVISION, "files": {}}
    for remote, expected in FILES.items():
        local = folder / Path(remote).name
        if not local.exists():
            temporary = local.with_suffix(local.suffix + ".part")
            with urlopen(f"https://huggingface.co/{MODEL}/resolve/{REVISION}/{remote}", timeout=60) as response, temporary.open("wb") as out:
                while block := response.read(1024 * 1024):
                    out.write(block)
            data = temporary.read_bytes()
        else:
            temporary = None
            data = local.read_bytes()
        checksum = hashlib.sha256(data).hexdigest()
        check = checksum if len(expected) == 64 else hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
        if check != expected:
            raise ValueError("Checksum mismatch: " + remote)
        if temporary is not None:
            temporary.replace(local)
        manifest["files"][local.name] = {"sha256": checksum, "bytes": len(data)}
        print(local.name, len(data), flush=True)
    (folder / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
