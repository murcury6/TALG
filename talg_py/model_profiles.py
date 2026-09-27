"""Immutable model dependencies. Raw news observations are shared; ratings are isolated."""
import hashlib
import json
import re
import time
from pathlib import Path
from contextlib import contextmanager


def revision_folder(root, reference):
    if not isinstance(reference, str) or not re.fullmatch(r"[0-9a-f]{64}", reference):
        raise ValueError("Invalid model revision")
    return root / "work/model-profiles/revisions" / reference


def revision(root, reference, kind=None):
    raw = (revision_folder(root, reference) / "manifest.json").read_bytes()
    if hashlib.sha256(raw).hexdigest() != reference:
        raise ValueError("Pinned model revision was modified")
    value = json.loads(raw)
    if kind is not None and value.get("kind") != kind:
        raise ValueError("Incorrect model dependency type")
    return value


def news_reference(root, reference):
    value = revision(root, reference)
    ref = reference if value["kind"] == "news" else value.get("dependencies", {}).get("news")
    if ref is None:
        raise ValueError("Pin a News profile under More > Profile links")
    revision(root, ref, "news")
    return ref


def runtime(root, reference):
    return revision_folder(root, reference) / "runtime"


@contextmanager
def runtime_lock(folder):
    folder.mkdir(parents=True, exist_ok=True)
    with (folder / "worker.lock").open("a+b") as lock:
        lock.write(b"0"); lock.flush(); lock.seek(0)
        import os
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield


def refresh_news(root, reference, now=None):
    """Recalculate this exact revision on shared, retained encoded observations."""
    from .news_tensor import Store, snapshot, atomic_json, atomic_npz, digest, utc
    from .news_ratings import rate_items, event_snapshot
    from .news_symbols import evaluate_symbols
    from .model_language import Program
    value = revision(root, reference, "news"); contents = value["contents"]
    config = json.loads(contents["config"]); source = contents["source"]
    from .news_weighted_profile import is_weighted, refresh
    if is_weighted(config):
        return refresh(root, reference)
    custom = json.loads(contents.get("inputs", "{}"))
    version = digest(json.dumps(config["encoder"], sort_keys=True))
    base = root / "work/news-tensor/information.sqlite"
    if not base.exists(): raise ValueError("No encoded news archive yet; run the news encoder first")
    folder = runtime(root, reference); now = time.time() if now is None else now
    with runtime_lock(folder):
        store = Store(folder)
        try:
            # Only raw evidence is synchronized. Each revision owns its ratings, history and event cursor.
            store.db.execute("ATTACH DATABASE ? AS evidence", (str(base),))
            with store.db:
                store.db.execute("""INSERT OR REPLACE INTO information SELECT i.* FROM evidence.information i
                    LEFT JOIN information own ON own.id=i.id
                    WHERE own.id IS NULL OR own.content_hash IS NOT i.content_hash
                       OR own.encoder_version IS NOT i.encoder_version OR own.encoded_at IS NOT i.encoded_at
                       OR own.observed_at IS NOT i.observed_at""")
                for table in ("aliases", "associations", "observations"):
                    store.db.execute(f"INSERT OR IGNORE INTO {table} SELECT * FROM evidence.{table}")
            store.db.execute("DETACH DATABASE evidence")
            total = store.db.execute("SELECT count(*) FROM information").fetchone()[0]
            matching = store.db.execute("SELECT count(*) FROM information WHERE vector IS NOT NULL AND encoder_version=?", (version,)).fetchone()[0]
            if total and not matching: raise ValueError("This profile needs a different encoder; no compatible encoded observations")
            rating = dict(rate_items(store, config, now, version, source, custom), profile_revision=reference)
            atomic_json(folder / "rating-status.json", rating)
            if Program(source).symbol is not None:
                symbols = dict(evaluate_symbols(store.db, source, now=now), profile_revision=reference)
                atomic_json(folder / "symbol-ratings.json", symbols)
            events = dict(event_snapshot(store), profile_revision=reference)
            atomic_json(folder / "news-events.json", events)
            tensors, articles, effects = snapshot(store, config, now, version)
            atomic_npz(folder / "current.npz", **tensors)
            atomic_npz(folder / "information.npz", **articles)
            atomic_json(folder / "effects.json", {"as_of_utc": utc(now), "effects": effects, "profile_revision": reference})
            atomic_json(folder / "status.json", {"state": "LIVE", "as_of_utc": utc(now), "profile_revision": reference,
                "information_items": total, "encoded_items": matching, "pending_items": total-matching})
            return rating
        finally:
            store.db.close()


def active_news_revisions(root):
    refs = set(); visited = set()
    def visit(ref):
        if not ref or ref in visited: return
        visited.add(ref); value = revision(root, ref)
        if value["kind"] == "news": refs.add(ref)
        for dep in value.get("dependencies", {}).values(): visit(dep)
    index = root / "work/model-profiles/profiles.json"
    if index.exists():
        profiles = json.loads(index.read_text(encoding="utf-8-sig"))["profiles"]
        for kind, entries in profiles.items():
            for profile in entries.values():
                if kind == "news": visit(profile.get("head"))
                for ref in profile.get("dependencies", {}).values(): visit(ref)
    session = root / "work/rapid-paper/session.json"
    if session.exists():
        value = json.loads(session.read_text(encoding="utf-8-sig"))
        if value.get("phase") not in ("COMPLETE", "IDLE"):
            visit(value.get("profileRevision")); visit(value.get("newsProfileRevision"))
    return sorted(refs)


def refresh_active(root):
    from .news_tensor import atomic_json
    for ref in active_news_revisions(root):
        try:
            config = json.loads(revision(root, ref, "news")["contents"]["config"])
            if config.get("engine") == "symbol_weighted_tensor_v1":
                continue  # Explicit Run owns this expensive, on-demand information pipeline.
            refresh_news(root, ref)
        except (BlockingIOError, PermissionError): pass  # Another reader-requested refresh owns this revision.
        except Exception as error:
            atomic_json(runtime(root, ref) / "status.json", {"state": "ERROR", "error": str(error), "profile_revision": ref})
