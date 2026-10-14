"""Fetch only the selected characters; never download the full model collection."""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
from pathlib import Path
import re
import urllib.error
import urllib.request

CDN = "https://torappu.prts.wiki/assets/"
WORDS = "https://raw.githubusercontent.com/Kengxxiao/ArknightsGameData/master/zh_CN/gamedata/excel/charword_table.json"
MODES = {"基建": "building", "正面": "battle-front", "背面": "battle-back"}
COMPANION_TITLES = {"交谈1", "交谈2", "交谈3", "晋升后交谈1", "晋升后交谈2", "信赖提升后交谈1", "信赖提升后交谈2", "信赖提升后交谈3", "闲置", "问候"}


def request(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "ArkPets-Companion/0.1", "Referer": "https://prts.wiki/"})
    with urllib.request.urlopen(req, timeout=35) as response:
        return response.read()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--roster", type=Path, default=Path("docs/companion-roster.json"))
    parser.add_argument("--output", type=Path, default=Path("companion-data"))
    args = parser.parse_args()
    roster = json.loads(args.roster.read_text(encoding="utf-8"))
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=True)
    words = json.loads(request(WORDS))
    receipts: list[dict] = []
    errors: list[dict] = []

    def save(url: str, relative: str) -> bytes:
        target = (root / relative).resolve()
        if not target.is_relative_to(root):
            raise ValueError("Asset path escaped output directory")
        content = target.read_bytes() if target.exists() and target.stat().st_size else request(url)
        if content[:30].lstrip().startswith((b"<!DOCTYPE", b"<html")):
            raise ValueError("Received HTML instead of an asset: " + url)
        target.parent.mkdir(parents=True, exist_ok=True)
        if not target.exists():
            target.write_bytes(content)
        if target.read_bytes() != content:
            raise IOError("Readback mismatch: " + str(target))
        receipts.append({"path": relative, "url": url, "bytes": len(content), "sha256": hashlib.sha256(content).hexdigest()})
        return content

    def fetch_model(char_id: str, skin_key: str, mode: str, prefix: str, entry: dict) -> dict:
        stem = entry["file"].split("/")[-1]
        local_dir = f"models/{char_id}/{skin_key}/{mode}"
        remote = prefix + entry["file"]
        atlas = save(remote + ".atlas", local_dir + "/" + stem + ".atlas").decode("utf-8-sig")
        save(remote + ".skel", local_dir + "/" + stem + ".skel")
        textures = list(dict.fromkeys(re.findall(r"(?:^|\n)([^\r\n]+\.(?:png|jpg|webp))\r?(?=\n)", atlas)))
        if not textures:
            raise ValueError("No atlas textures: " + remote)
        for texture in textures:
            save(remote.rsplit("/", 1)[0] + "/" + texture, local_dir + "/" + texture)
        return {"asset": local_dir, "files": {".atlas": stem + ".atlas", ".skel": stem + ".skel", ".png": textures}}

    characters = []
    with ThreadPoolExecutor(max_workers=8) as pool:
        for selected in roster["characters"]:
            char_id, name = selected["id"], selected["name"]
            character = {"id": char_id, "name": name, "skins": [], "voices": [], "missing": []}
            try:
                meta = json.loads(save(CDN + f"char_spine/{char_id}/meta.json", f"sources/{char_id}.json"))
                for index, (skin_name, entries) in enumerate(meta["skin"].items()):
                    skin = {"id": "default" if skin_name == "默认" else f"skin-{index}", "name": skin_name, "models": {}}
                    jobs = {pool.submit(fetch_model, char_id, skin["id"], MODES[label], meta["prefix"], entry): label for label, entry in entries.items() if label in MODES}
                    for future in as_completed(jobs):
                        label = jobs[future]
                        try:
                            skin["models"][MODES[label]] = future.result()
                        except Exception as exc:
                            errors.append({"character": name, "skin": skin_name, "mode": label, "error": str(exc)})
                    if skin["models"]:
                        character["skins"].append(skin)
            except Exception as exc:
                character["missing"].append("模型来源不可用：" + str(exc))

            lang = words.get("voiceLangDict", {}).get(char_id, {}).get("dict", {}).get("CN_MANDARIN")
            if lang:
                wordkey = lang["wordkey"]
                candidates = [w for w in words["charWords"].values() if w.get("wordKey") == wordkey and w.get("voiceTitle") in COMPANION_TITLES]
                jobs = {}
                for word in candidates:
                    voice_id = word["voiceId"]
                    relative = f"voices/{char_id}/{voice_id}.wav"
                    url = CDN + "audio/voice_cn/" + word.get("voiceAsset", char_id + "/" + voice_id).lower() + ".wav"
                    jobs[pool.submit(save, url, relative)] = (word, relative)
                for future in as_completed(jobs):
                    word, relative = jobs[future]
                    try:
                        raw = future.result()
                        if raw[:4] != b"RIFF" or raw[8:12] != b"WAVE":
                            raise ValueError("Expected a WAV audio file")
                        character["voices"].append({"id": word["voiceId"], "title": word["voiceTitle"], "text": word.get("voiceText", ""), "file": relative})
                    except Exception as exc:
                        errors.append({"character": name, "voice": word["voiceTitle"], "error": str(exc)})
            if not character["voices"]:
                character["missing"].append("未获取到中文普通话陪伴语音")
            character["voices"].sort(key=lambda v: v["id"])
            characters.append(character)
            print(f"{name}: {len(character['skins'])} skins, {sum(len(s['models']) for s in character['skins'])} models, {len(character['voices'])} voices", flush=True)

    catalog = {"version": 1, "voiceLanguage": "CN_MANDARIN", "voiceMinSeconds": roster.get("voiceMinSeconds", 600), "voiceMaxSeconds": roster.get("voiceMaxSeconds", 1200), "characters": characters}
    (root / "catalog.json").write_text(json.dumps(catalog, ensure_ascii=False, indent=2), encoding="utf-8")
    (root / "asset-receipts.json").write_text(json.dumps({"sources": [WORDS, "https://prts.wiki/"], "files": receipts, "errors": errors}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Saved {len(characters)} characters, {len(receipts)} checked files; {len(errors)} missing assets. See {root / 'asset-receipts.json'}", flush=True)


if __name__ == "__main__":
    main()
