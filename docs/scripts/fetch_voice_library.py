"""Adds complete Mandarin libraries, related operators, and voiced outfits to a curated catalog."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import io
import http.cookiejar
import json
from pathlib import Path
import urllib.parse
import urllib.request
import threading
import time
import wave
from fetch_companions import request, CDN, COMPANION_TITLES

BASE = "https://raw.githubusercontent.com/Kengxxiao/ArknightsGameData/master/zh_CN/gamedata/excel/"
SESSION = threading.local()


def audio_request(url):
    if not hasattr(SESSION, "opener"):
        SESSION.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    with SESSION.opener.open(urllib.request.Request(url, headers={"User-Agent": "ArkPets-Companion/0.1", "Referer": "https://prts.wiki/"}), timeout=35) as response:
        return response.read()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--metadata", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    args.metadata.mkdir(parents=True, exist_ok=True)
    tables, provenance = {}, []
    for name in ("charword_table", "character_table", "skin_table", "char_meta_table"):
        file = args.metadata / (name + ".json")
        if not file.exists():
            file.write_bytes(request(BASE + file.name))
        tables[name] = json.loads(file.read_text(encoding="utf-8"))
        provenance.append({"url": BASE + file.name, "sha256": hashlib.sha256(file.read_bytes()).hexdigest()})
    words = tables["charword_table"]
    operators = tables["character_table"]
    skins = tables["skin_table"]["charSkins"]
    groups = tables["char_meta_table"]["spCharGroups"]
    catalog = json.loads(args.catalog.read_text(encoding="utf-8"))
    receipts, errors = [], []

    def fetch(word, source, selected):
        asset = word.get("voiceAsset") or source["id"] + "/" + word["voiceId"]
        url = CDN + "audio/voice_cn/" + urllib.parse.quote(asset.lower().replace("#", "__"), safe="/") + ".wav"
        folder = source["id"].replace("#", "_").replace("@", "_")
        relative = "voices/" + folder + "/" + word["voiceId"] + ".wav"
        target = args.output / relative
        previous = args.catalog.parent / relative
        raw = None
        for attempt in range(3):
            try:
                raw = target.read_bytes() if target.exists() else previous.read_bytes() if previous.exists() else audio_request(url)
                with wave.open(io.BytesIO(raw)) as stream:
                    seconds = stream.getnframes() / stream.getframerate()
                break
            except Exception:
                if attempt == 2:
                    raise
                time.sleep(1 + attempt)
        target.parent.mkdir(parents=True, exist_ok=True)
        if not target.exists():
            target.write_bytes(raw)
        assert target.read_bytes() == raw
        receipt = {"path": relative, "url": url, "sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw), "duration": seconds}
        voice = {"id": source["id"] + "/" + word["voiceId"], "legacyId": word["voiceId"],
                 "title": word["voiceTitle"], "text": word.get("voiceText", ""), "file": relative,
                 "sourceId": source["id"], "sourceName": source["name"], "speakerName": source["speakerName"],
                 "defaultSelected": selected and word["voiceTitle"] in COMPANION_TITLES, "duration": seconds}
        return voice, receipt

    with ThreadPoolExecutor(max_workers=4) as pool:
        for character in catalog["characters"]:
            cid = character["id"]
            related = next((ids for ids in groups.values() if cid in ids), [cid])
            sources = []
            for rid in [cid] + [rid for rid in related if rid != cid]:
                name = operators.get(rid, {}).get("name", character["name"])
                lang = words["voiceLangDict"].get(rid, {}).get("dict", {}).get("CN_MANDARIN")
                if lang:
                    relation = "默认" if rid == cid else "本体" if rid == related[0] else "异格"
                    sources.append({"id": lang["wordkey"], "name": name + " · " + relation,
                                    "speakerName": name, "kind": "default" if rid == cid else "related", "operatorId": rid})
                    for skin in skins.values():
                        if skin["charId"] == rid and skin.get("voiceId"):
                            sources.append({"id": skin["voiceId"], "name": name + " · " + skin["displaySkin"]["skinName"],
                                            "speakerName": name, "kind": "skin", "operatorId": rid, "skinId": skin["skinId"]})
            voices, jobs = [], {}
            for source in sources:
                candidates = [word for word in words["charWords"].values() if word["wordKey"] == source["id"]]
                source["expectedCount"] = len(candidates)
                for word in candidates:
                    jobs[pool.submit(fetch, word, source, source["kind"] == "default")] = (source, word)
            for job in as_completed(jobs):
                source, word = jobs[job]
                try:
                    voice, receipt = job.result()
                    voices.append(voice); receipts.append(receipt)
                except Exception as error:
                    errors.append({"character": character["name"], "source": source["name"], "voice": word["voiceTitle"], "error": str(error)})
            order = {source["id"]: i for i, source in enumerate(sources)}
            voices.sort(key=lambda v: (order[v["sourceId"]], v["legacyId"]))
            for source in sources:
                source["availableCount"] = sum(v["sourceId"] == source["id"] for v in voices)
            character["voices"], character["voiceSources"] = voices, sources
            print(character["name"], len(voices), [(s["name"], s["availableCount"]) for s in sources], flush=True)
    catalog["version"] = 2
    (args.output / "catalog.json").write_text(json.dumps(catalog, ensure_ascii=False, indent=2), encoding="utf-8")
    (args.output / "voice-library-receipts.json").write_text(json.dumps({"sources": provenance, "files": receipts, "errors": errors}, ensure_ascii=False, indent=2), encoding="utf-8")
    print("Downloaded", len(receipts), "WAV files; missing", len(errors), flush=True)


if __name__ == "__main__":
    main()
