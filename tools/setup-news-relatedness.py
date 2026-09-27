"""Install pinned public ONNX relevance weights; no remote code execution."""
import hashlib
import json
from pathlib import Path
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
MODEL = "Xenova/ms-marco-MiniLM-L-6-v2"
REVISION = "a09144355adeed5f58c8ed011d209bf8ee5a1fec"
FILES = {
    "config.json": "e94e433dc05771d74530b4a5fdaf8f398da30cab",
    "tokenizer.json": "688882a79f44442ddc1f60d70334a7ff5df0fb47",
    "tokenizer_config.json": "75305659f7795d4549f0e23688b52fa20a32f925",
    "onnx/model_quantized.onnx": "e9d8ebf845c413e981c175bfe49a3bfa9b3dcce2a3ba54875ee5df5a58639fbe",
}


def main():
    folder = ROOT / "work/news-relatedness/model"
    folder.mkdir(parents=True, exist_ok=True)
    manifest = {"model": MODEL, "revision": REVISION, "files": {}}
    for remote, expected in FILES.items():
        local = folder / Path(remote).name
        if local.exists():
            data = local.read_bytes()
        else:
            with urlopen(f"https://huggingface.co/{MODEL}/resolve/{REVISION}/{remote}", timeout=60) as response:
                data = response.read()
        checksum = hashlib.sha256(data).hexdigest()
        verified = checksum if len(expected) == 64 else hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
        if verified != expected:
            raise ValueError("Model checksum mismatch: " + remote)
        if not local.exists():
            temp = local.with_suffix(local.suffix + ".part")
            temp.write_bytes(data)
            temp.replace(local)
        manifest["files"][local.name] = {"sha256": checksum, "bytes": len(data)}
        print(local.name, len(data), flush=True)
    (folder / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
