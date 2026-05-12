#!/usr/bin/env python3
"""Батч-генератор pre-recorded voice banks из character JSON-spec.

Читает server/characters/<name>.json, для каждой фразы в `banks.*`
вызывает edge-tts с параметрами `voice.*`, кодирует в ogg/vorbis с
ffmpeg-фильтром, сохраняет в server/voice_banks/<name>/<category>_NN.ogg.

Usage:
    python generate.py sage           # один персонаж
    python generate.py --all          # все из characters/
    python generate.py sage --force   # пересоздать если файл существует

Требует:
    pip install edge-tts
    ffmpeg в PATH
"""
from __future__ import annotations

import argparse
import asyncio
import json
import subprocess
from pathlib import Path

import edge_tts


HERE = Path(__file__).resolve().parent
CHARACTERS_DIR = HERE.parent / "characters"
OUTPUT_DIR = HERE


async def synth_one(text: str, voice_cfg: dict, out_path: Path):
    """edge-tts → mp3 в памяти → ffmpeg фильтр → ogg/vorbis на диск."""
    comm = edge_tts.Communicate(
        text,
        voice_cfg["base"],
        rate=voice_cfg["rate"],
        pitch=voice_cfg["pitch"],
    )
    buf = bytearray()
    async for chunk in comm.stream():
        if chunk.get("type") == "audio":
            buf.extend(chunk.get("data") or b"")
    mp3 = bytes(buf)
    if not mp3:
        raise RuntimeError("edge-tts returned empty")
    ffmpeg_filter = voice_cfg.get("ffmpeg", "")
    if not ffmpeg_filter:
        # просто переупаковка
        proc = subprocess.run(
            ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
             "-i", "pipe:0", "-f", "ogg", "-c:a", "libvorbis", "-q:a", "5",
             str(out_path)],
            input=mp3, capture_output=True, check=False,
        )
    else:
        proc = subprocess.run(
            ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
             "-i", "pipe:0", "-af", ffmpeg_filter,
             "-f", "ogg", "-c:a", "libvorbis", "-q:a", "5", str(out_path)],
            input=mp3, capture_output=True, check=False,
        )
    if proc.returncode != 0:
        raise RuntimeError(f"ffmpeg rc={proc.returncode}: {proc.stderr.decode(errors='ignore')[:200]}")


async def generate_character(spec_path: Path, force: bool = False):
    spec = json.loads(spec_path.read_text(encoding="utf-8"))
    char_id = spec["id"]
    voice = spec["voice"]
    banks = spec.get("banks", {})
    char_dir = OUTPUT_DIR / char_id
    char_dir.mkdir(parents=True, exist_ok=True)
    manifest_lines: list[str] = []
    total = 0
    skipped = 0
    for category, phrases in banks.items():
        for idx, phrase in enumerate(phrases, 1):
            out = char_dir / f"{category}_{idx:02d}.ogg"
            tsv_line = f"{category}_{idx:02d}\t{category}\t{phrase}"
            manifest_lines.append(tsv_line)
            if out.exists() and not force:
                skipped += 1
                continue
            print(f"  [{char_id}/{category}_{idx:02d}] {phrase[:50]}")
            try:
                await synth_one(phrase, voice, out)
            except Exception as e:
                print(f"    ! failed: {e}")
                continue
            total += 1
    manifest = char_dir / "manifest.tsv"
    manifest.write_text("\n".join(manifest_lines) + "\n", encoding="utf-8")
    print(f"  ↳ {char_id}: generated {total}, skipped {skipped}, manifest {len(manifest_lines)} lines")


async def main():
    p = argparse.ArgumentParser()
    p.add_argument("character", nargs="?", help="character id (e.g. sage) or --all")
    p.add_argument("--all", action="store_true", help="generate all characters from server/characters/")
    p.add_argument("--force", action="store_true", help="regenerate existing files")
    args = p.parse_args()

    if args.all or args.character == "--all":
        specs = sorted(CHARACTERS_DIR.glob("*.json"))
    elif args.character:
        spec = CHARACTERS_DIR / f"{args.character}.json"
        if not spec.exists():
            raise SystemExit(f"character spec not found: {spec}")
        specs = [spec]
    else:
        raise SystemExit("usage: generate.py <character_id> | --all [--force]")

    for spec_path in specs:
        print(f"=== {spec_path.stem} ===")
        await generate_character(spec_path, force=args.force)


if __name__ == "__main__":
    asyncio.run(main())
