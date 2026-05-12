"""ai-mood-pet cloud server — упрощённый WS-сервер для AI-питомца.

Все вычисления в облаке (Gemini Flash-Lite audio+vision+chat + edge-tts).
История разговора в RAM (N пар на user_id), без БД — лёгкий ephemeral сервис.

Персонажи описываются в JSON-файлах (см. CHARACTERS_DIR). Каждый persona
несёт system_prompt, voice (rate/pitch для edge-tts), опциональные
vision_keywords и флаг prefer_uncensored для будущего OSS-роутинга
на abliterated-модель.

ENV: см. .env.example рядом с этим файлом.
"""
from __future__ import annotations

import asyncio
import base64
import io
import json
import os
import re
import time
import wave
from collections import deque
from contextlib import asynccontextmanager, suppress
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import httpx
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from loguru import logger
from pydantic import BaseModel, ConfigDict, Field, ValidationError


# ---------- config (env) ----------

PORT = int(os.getenv("PORT", "8350"))
AUTH_TOKEN = os.getenv("AUTH_TOKEN", "")

GOOGLE_API_KEY = os.getenv("GOOGLE_API_KEY", "")
GEMINI_MODEL = os.getenv("GEMINI_MODEL") or os.getenv("GEMINI_MODEL_LITE", "gemini-2.5-flash-lite")

OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "")
OPENAI_WHISPER_MODEL = os.getenv("OPENAI_WHISPER_MODEL", "whisper-1")

CHARACTERS_DIR = os.getenv("CHARACTERS_DIR", "./characters")
DEFAULT_CHARACTER = os.getenv("DEFAULT_CHARACTER", "baby_robot")
OWNER_NAME = os.getenv("OWNER_NAME", "хозяин")

TTS_BASE_VOICE = os.getenv("TTS_BASE_VOICE", "ru-RU-DmitryNeural")

USE_UNCENSORED = os.getenv("USE_UNCENSORED", "false").strip().lower() in {"1", "true", "yes", "on"}
UNCENSORED_MODEL = os.getenv("UNCENSORED_MODEL", "gemini-2.5-flash-lite")
UNCENSORED_BASE_URL = os.getenv("UNCENSORED_BASE_URL", "")

try:
    MAX_HISTORY_PAIRS = int(os.getenv("HISTORY_PAIRS", "5"))
except ValueError:
    MAX_HISTORY_PAIRS = 5


# ---------- character spec (pydantic) ----------

# Дефолтные ключевые слова для vision-режима (русские). Используются как
# fallback, если в character spec поле vision_keywords не задано.
DEFAULT_VISION_KEYWORDS: tuple[str, ...] = (
    "что в руках", "что у меня в руках", "что держу", "как выгляжу",
    "что в кадре", "что видишь", "посмотри", "глянь", "видишь",
    "что у меня", "что я держу",
)


class CharacterVoice(BaseModel):
    """Параметры голоса для edge-tts."""
    model_config = ConfigDict(extra="ignore")

    base: str = Field(default=TTS_BASE_VOICE, description="edge-tts voice id")
    rate: str = Field(default="+0%", description="например '+10%' или '-5%'")
    pitch: str = Field(default="+0Hz", description="например '+80Hz' или '-30Hz'")
    # ffmpeg-эффекты не применяются на сервере (TTS уходит как mp3),
    # но поле допускается в JSON для совместимости с клиентскими пайплайнами.
    ffmpeg: str | None = None


class CharacterSpec(BaseModel):
    """JSON-спецификация персонажа."""
    model_config = ConfigDict(extra="ignore")

    id: str
    label: str | None = None
    bio: str | None = None
    voice: CharacterVoice = Field(default_factory=CharacterVoice)
    system_prompt: str
    vision_keywords: list[str] | None = None
    prefer_uncensored: bool = False
    # Остальные поля (banks, humor_genre, forbidden_topics, mirror_amplifier,
    # default_safe) допускаются, но сервером не используются — это для клиента.


_characters: dict[str, CharacterSpec] = {}


def load_characters(dir_path: str) -> dict[str, CharacterSpec]:
    """Загружает все *.json из dir_path как CharacterSpec. Битые файлы
    логируются и пропускаются."""
    out: dict[str, CharacterSpec] = {}
    root = Path(dir_path)
    if not root.is_dir():
        logger.warning(f"characters dir not found: {root}")
        return out
    for jf in sorted(root.glob("*.json")):
        try:
            data = json.loads(jf.read_text(encoding="utf-8"))
            spec = CharacterSpec.model_validate(data)
        except (json.JSONDecodeError, ValidationError, OSError) as e:
            logger.error(f"character load failed: {jf.name}: {e}")
            continue
        if spec.id in out:
            logger.warning(f"duplicate character id={spec.id} in {jf.name}, overwriting")
        out[spec.id] = spec
    return out


def get_character(style: str | None) -> CharacterSpec | None:
    """Возвращает CharacterSpec по id; если не найден — DEFAULT_CHARACTER;
    если и того нет — None."""
    if style and style in _characters:
        return _characters[style]
    if DEFAULT_CHARACTER in _characters:
        return _characters[DEFAULT_CHARACTER]
    if _characters:
        # Хоть какой-то — лишь бы не вернуть None.
        return next(iter(_characters.values()))
    return None


def build_system_prompt(spec: CharacterSpec) -> str:
    """Подставляет {owner} в system_prompt из env OWNER_NAME."""
    return spec.system_prompt.replace("{owner}", OWNER_NAME)


def vision_keywords_for(spec: CharacterSpec) -> tuple[str, ...]:
    if spec.vision_keywords:
        return tuple(kw.lower() for kw in spec.vision_keywords if kw.strip())
    return DEFAULT_VISION_KEYWORDS


# ---------- Gemini client ----------

try:
    from google import genai
    from google.genai import types as genai_types
    _GENAI_OK = True
except Exception as e:
    genai = None  # type: ignore[assignment]
    genai_types = None  # type: ignore[assignment]
    _GENAI_OK = False
    logger.warning(f"google-genai missing: {e}")


_gemini_client: Any = None
if _GENAI_OK and GOOGLE_API_KEY:
    try:
        _gemini_client = genai.Client(api_key=GOOGLE_API_KEY)
        logger.info(f"gemini client ready (model={GEMINI_MODEL})")
    except Exception as e:
        logger.error(f"gemini client init fail: {e}")


async def gemini_chat(
    system: str,
    history: list[dict[str, str]],
    user_text: str,
    *,
    jpeg: bytes | None = None,
    max_tokens: int = 200,
    temperature: float = 0.85,
    model: str | None = None,
) -> str:
    if not _gemini_client:
        return ""
    contents: list[Any] = []
    for h in history:
        role = "user" if h["role"] == "user" else "model"
        c = h.get("content", "").strip()
        if not c:
            continue
        contents.append(genai_types.Content(
            role=role, parts=[genai_types.Part.from_text(text=c)],
        ))
    parts: list[Any] = []
    if jpeg:
        parts.append(genai_types.Part.from_bytes(data=jpeg, mime_type="image/jpeg"))
    parts.append(genai_types.Part.from_text(text=user_text))
    contents.append(genai_types.Content(role="user", parts=parts))
    cfg = genai_types.GenerateContentConfig(
        system_instruction=system,
        max_output_tokens=max_tokens,
        temperature=temperature,
    )
    try:
        resp = await _gemini_client.aio.models.generate_content(
            model=model or GEMINI_MODEL,
            contents=contents,
            config=cfg,
        )
    except Exception as e:
        logger.error(f"gemini generate fail: {e}")
        return ""
    text_parts: list[str] = []
    for cand in (getattr(resp, "candidates", None) or []):
        content = getattr(cand, "content", None)
        if content is None:
            continue
        for part in (getattr(content, "parts", None) or []):
            t = getattr(part, "text", None)
            if isinstance(t, str) and t.strip():
                text_parts.append(t)
    return "".join(text_parts).strip()


# ---------- Uncensored route (scaffold) ----------

async def uncensored_chat(
    system: str,
    history: list[dict[str, str]],
    user_text: str,
    *,
    jpeg: bytes | None = None,
    max_tokens: int = 200,
    temperature: float = 0.85,
) -> str:
    """Заглушка под OpenAI-compatible endpoint (например, ollama-proxy с
    abliterated-моделью). Реализация клиента — TODO; сейчас всегда возвращает
    пустую строку, чтобы вызывающая сторона ушла на fallback к Gemini."""
    if not UNCENSORED_BASE_URL:
        logger.warning("uncensored requested but UNCENSORED_BASE_URL not set; falling back to gemini")
        return ""
    logger.warning(
        f"uncensored route to {UNCENSORED_BASE_URL} (model={UNCENSORED_MODEL}) "
        "not implemented yet; falling back to gemini"
    )
    return ""


async def chat_for_character(
    spec: CharacterSpec,
    history: list[dict[str, str]],
    user_text: str,
    *,
    jpeg: bytes | None = None,
    max_tokens: int = 200,
    temperature: float = 0.85,
) -> tuple[str, str]:
    """Возвращает (reply, model_label). Маршрутизирует на uncensored
    endpoint если включено и персонаж того требует; иначе — обычный Gemini.
    При пустом uncensored-ответе делает прозрачный fallback на Gemini."""
    system = build_system_prompt(spec)
    if USE_UNCENSORED and spec.prefer_uncensored:
        reply = await uncensored_chat(
            system, history, user_text,
            jpeg=jpeg, max_tokens=max_tokens, temperature=temperature,
        )
        if reply:
            return reply, UNCENSORED_MODEL
    reply = await gemini_chat(
        system, history, user_text,
        jpeg=jpeg, max_tokens=max_tokens, temperature=temperature,
    )
    return reply, GEMINI_MODEL


# ---------- STT (Gemini audio + optional Whisper fallback) ----------

OPENAI_AUDIO_URL = "https://api.openai.com/v1/audio/transcriptions"
_http: httpx.AsyncClient | None = None


async def get_http() -> httpx.AsyncClient:
    global _http
    if _http is None or _http.is_closed:
        _http = httpx.AsyncClient(timeout=45)
    return _http


def _pcm_to_wav(pcm: bytes, sample_rate: int = 16000) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sample_rate)
        w.writeframes(pcm)
    return buf.getvalue()


async def gemini_stt(pcm: bytes, sample_rate: int = 16000) -> str:
    """STT через Gemini Flash-Lite audio input — один API, тот же ключ что
    для chat. PCM → WAV → Gemini Part.from_bytes(mime=audio/wav)."""
    if not _gemini_client:
        return ""
    wav = _pcm_to_wav(pcm, sample_rate)
    contents = [genai_types.Content(role="user", parts=[
        genai_types.Part.from_bytes(data=wav, mime_type="audio/wav"),
        genai_types.Part.from_text(
            text="Transcribe this Russian audio. Reply with ONLY the transcribed text, no comments, no quotes, no labels."
        ),
    ])]
    cfg = genai_types.GenerateContentConfig(max_output_tokens=200, temperature=0.0)
    try:
        resp = await _gemini_client.aio.models.generate_content(
            model=GEMINI_MODEL,
            contents=contents,
            config=cfg,
        )
    except Exception as e:
        logger.warning(f"gemini-stt fail: {e}")
        return ""
    parts: list[str] = []
    for cand in (getattr(resp, "candidates", None) or []):
        content = getattr(cand, "content", None)
        if content is None:
            continue
        for part in (getattr(content, "parts", None) or []):
            t = getattr(part, "text", None)
            if isinstance(t, str) and t.strip():
                parts.append(t)
    text = "".join(parts).strip().strip('"').strip("«»")
    return text


async def whisper_stt(pcm: bytes, sample_rate: int = 16000) -> str:
    """OpenAI Whisper fallback. Используется только если задан OPENAI_API_KEY
    и Gemini STT вернул пусто."""
    if not OPENAI_API_KEY:
        return ""
    wav = _pcm_to_wav(pcm, sample_rate)
    cli = await get_http()
    try:
        r = await cli.post(
            OPENAI_AUDIO_URL,
            files={"file": ("speech.wav", wav, "audio/wav")},
            data={"model": OPENAI_WHISPER_MODEL, "language": "ru", "response_format": "json"},
            headers={"Authorization": f"Bearer {OPENAI_API_KEY}"},
        )
    except Exception as e:
        logger.warning(f"whisper http fail: {e}")
        return ""
    if r.status_code != 200:
        logger.warning(f"whisper http={r.status_code}: {r.text[:200]}")
        return ""
    try:
        return (r.json().get("text") or "").strip()
    except Exception:
        return ""


# ---------- TTS (edge-tts) ----------

import edge_tts  # noqa: E402


async def tts_synth(text: str, spec: CharacterSpec | None) -> bytes:
    """Синтез mp3 через edge-tts с параметрами голоса из CharacterSpec.
    Если spec is None — синтез базовым голосом без модификаций."""
    if not text.strip():
        return b""
    if spec is not None:
        voice = spec.voice.base or TTS_BASE_VOICE
        rate = spec.voice.rate or "+0%"
        pitch = spec.voice.pitch or "+0Hz"
    else:
        voice = TTS_BASE_VOICE
        rate = "+0%"
        pitch = "+0Hz"
    try:
        comm = edge_tts.Communicate(text, voice, rate=rate, pitch=pitch)
        buf = bytearray()
        async for chunk in comm.stream():
            if chunk.get("type") == "audio":
                buf.extend(chunk.get("data") or b"")
        return bytes(buf)
    except Exception as e:
        logger.warning(f"edge-tts fail: {e}")
        return b""


# ---------- WS session state ----------

@dataclass
class Turn:
    role: str  # "user" | "assistant"
    content: str


# user_id → deque of turns (MAX_HISTORY_PAIRS пар = до 2*N turns).
_history: dict[str, deque[Turn]] = {}


def get_history(user_id: str) -> deque[Turn]:
    if user_id not in _history:
        _history[user_id] = deque(maxlen=MAX_HISTORY_PAIRS * 2)
    return _history[user_id]


# ---------- FastAPI ----------

@asynccontextmanager
async def lifespan(_: FastAPI):
    global _characters
    _characters = load_characters(CHARACTERS_DIR)
    ids = ", ".join(sorted(_characters.keys())) or "<none>"
    logger.info(
        f"characters loaded: {len(_characters)} from {CHARACTERS_DIR} [{ids}]; "
        f"default={DEFAULT_CHARACTER}"
    )
    logger.info(
        f"ai-mood-pet server ready: gemini={bool(_gemini_client)} "
        f"(model={GEMINI_MODEL}), openai_stt={bool(OPENAI_API_KEY)}, "
        f"uncensored={USE_UNCENSORED}"
    )
    yield
    if _http is not None and not _http.is_closed:
        await _http.aclose()


app = FastAPI(title="ai-mood-pet-server", version="0.2", lifespan=lifespan)


@app.get("/health")
async def health():
    return {
        "ok": True,
        "model": GEMINI_MODEL,
        "modes": ["default", "cloud_lite"],
        "gemini_available": bool(_gemini_client),
        "openai_available": bool(OPENAI_API_KEY),
        "characters": sorted(_characters.keys()),
        "default_character": DEFAULT_CHARACTER,
        "uncensored_enabled": USE_UNCENSORED,
    }


@app.get("/characters")
async def list_characters():
    return {
        "default": DEFAULT_CHARACTER,
        "items": [
            {
                "id": c.id,
                "label": c.label,
                "bio": c.bio,
                "prefer_uncensored": c.prefer_uncensored,
            }
            for c in _characters.values()
        ],
    }


@app.websocket("/ws/dialog")
async def ws_dialog(ws: WebSocket):
    await ws.accept()
    user_id = "default"
    style = DEFAULT_CHARACTER
    mode = "default"
    last_camera_frame: bytes | None = None
    pcm_buffer = bytearray()
    seq_seen = -1
    logger.info(f"ws connected from {ws.client}")
    try:
        while True:
            raw = await ws.receive_text()
            try:
                msg = json.loads(raw)
            except json.JSONDecodeError:
                await ws.send_json({"type": "error", "code": "bad_json"})
                continue
            mtype = msg.get("type")
            if mtype == "hello":
                if AUTH_TOKEN and msg.get("token") != AUTH_TOKEN:
                    await ws.send_json({"type": "error", "code": "bad_auth"})
                    await ws.close(code=1008, reason="bad token")
                    return
                user_id = msg.get("user_id") or "default"
                style = msg.get("style") or style
                mode = msg.get("mode") or mode
                logger.info(f"hello user={user_id} style={style} mode={mode}")
                await ws.send_json({
                    "type": "hello_ack",
                    "ok": True,
                    "mode": mode if mode in ("default", "cloud_lite") else "default",
                })
            elif mtype == "audio_start":
                pcm_buffer.clear()
                seq_seen = -1
            elif mtype == "camera_frame":
                try:
                    last_camera_frame = base64.b64decode(msg.get("b64") or "")
                except Exception:
                    last_camera_frame = None
            elif mtype == "audio_chunk":
                seq = msg.get("seq", 0)
                if seq <= seq_seen:
                    continue
                seq_seen = seq
                with suppress(Exception):
                    pcm_buffer.extend(base64.b64decode(msg.get("pcm_b64") or ""))
                if msg.get("is_final"):
                    frame = last_camera_frame
                    last_camera_frame = None
                    pcm = bytes(pcm_buffer)
                    pcm_buffer = bytearray()
                    asyncio.create_task(_process(ws, pcm, user_id, style, frame))
            elif mtype == "text_input":
                t = (msg.get("text") or "").strip()
                if t:
                    asyncio.create_task(_reply_for_text(ws, t, user_id, style))
            elif mtype == "say":
                t = (msg.get("text") or "").strip()
                if t:
                    spec = get_character(style)
                    asyncio.create_task(_send_reply_audio(ws, t, spec))
            elif mtype in ("object_seen", "gesture_seen"):
                # Контекстные подсказки от клиента (распознанные объекты/жесты).
                # Сейчас просто логируем — будущая фича: триггерить реакцию.
                logger.debug(f"{mtype}: {msg.get('label')}")
            elif mtype == "ambient_look":
                # Идиоматический «оглядеться по сторонам» — пока no-op.
                logger.debug("ambient_look")
            elif mtype == "photo_suggest":
                # Клиент предложил фото — отправим телеметрию подтверждения.
                logger.debug("photo_suggest")
            elif mtype == "ping":
                await ws.send_json({"type": "pong"})
            else:
                logger.debug(f"unknown msg type: {mtype}")
    except WebSocketDisconnect:
        logger.info(f"ws disconnected user={user_id}")
    except Exception as e:
        logger.exception(f"ws error: {e}")


def _matches_vision(text: str, spec: CharacterSpec | None) -> bool:
    vocab = vision_keywords_for(spec) if spec is not None else DEFAULT_VISION_KEYWORDS
    t = text.lower()
    return any(kw in t for kw in vocab)


# ---------- pipeline: STT → LLM → TTS ----------

async def _process(
    ws: WebSocket,
    pcm: bytes,
    user_id: str,
    style: str,
    frame: bytes | None,
):
    try:
        if len(pcm) < 1600 * 2:
            await ws.send_json({"type": "silent_ack", "reason": "pcm_too_short"})
            return
        await ws.send_json({"type": "stt_start"})
        t0 = time.monotonic()
        # Primary: Gemini audio input. Fallback: Whisper.
        text = await gemini_stt(pcm)
        stt_provider = "gemini"
        if not text and OPENAI_API_KEY:
            text = await whisper_stt(pcm)
            stt_provider = "openai"
        stt_ms = int((time.monotonic() - t0) * 1000)
        await ws.send_json({
            "type": "telemetry", "stage": "stt", "ms": stt_ms,
            "extra": {"pcm": len(pcm), "chars": len(text), "provider": stt_provider},
        })
        if not text:
            await ws.send_json({"type": "silent_ack", "reason": "empty_transcript"})
            return
        await ws.send_json({"type": "transcript", "text": text, "final": True})

        spec = get_character(style)
        if spec is None:
            await ws.send_json({"type": "error", "code": "no_characters"})
            return
        hist = get_history(user_id)
        use_vision = bool(frame) and _matches_vision(text, spec)
        t1 = time.monotonic()
        reply, model_used = await chat_for_character(
            spec,
            history=[{"role": x.role, "content": x.content} for x in hist],
            user_text=text,
            jpeg=frame if use_vision else None,
            max_tokens=200,
            temperature=0.85,
        )
        llm_ms = int((time.monotonic() - t1) * 1000)
        await ws.send_json({
            "type": "telemetry",
            "stage": "llm_vision" if use_vision else "llm",
            "ms": llm_ms,
            "extra": {"chars": len(reply), "model": model_used, "character": spec.id},
        })
        if not reply:
            reply = "плохая связь, повтори?"
        hist.append(Turn("user", text))
        hist.append(Turn("assistant", reply))
        await _send_reply_audio(ws, reply, spec)
    except Exception as e:
        logger.exception(f"process fail: {e}")


async def _reply_for_text(ws: WebSocket, text: str, user_id: str, style: str):
    try:
        await ws.send_json({"type": "transcript", "text": text, "final": True})
        spec = get_character(style)
        if spec is None:
            await ws.send_json({"type": "error", "code": "no_characters"})
            return
        hist = get_history(user_id)
        reply, _ = await chat_for_character(
            spec,
            history=[{"role": x.role, "content": x.content} for x in hist],
            user_text=text,
            max_tokens=200,
            temperature=0.85,
        )
        if not reply:
            reply = "плохая связь, повтори?"
        hist.append(Turn("user", text))
        hist.append(Turn("assistant", reply))
        await _send_reply_audio(ws, reply, spec)
    except Exception as e:
        logger.exception(f"text-reply fail: {e}")


# ---------- streaming TTS ----------

_SENT_RE = re.compile(r"[^.!?…]+[.!?…]+|[^.!?…]+$")


def _split_for_streaming(text: str, min_chunk: int = 25, max_chunks: int = 4) -> list[str]:
    if len(text) < min_chunk * 2:
        return [text]
    sents = [s.strip() for s in _SENT_RE.findall(text) if s.strip()]
    if len(sents) <= 1:
        return [text]
    chunks: list[str] = []
    buf = ""
    for s in sents:
        if len(buf) + len(s) < min_chunk and buf:
            buf = (buf + " " + s).strip()
        elif buf:
            chunks.append(buf)
            buf = s
        else:
            buf = s
    if buf:
        chunks.append(buf)
    if len(chunks) > max_chunks:
        chunks = chunks[: max_chunks - 1] + [" ".join(chunks[max_chunks - 1 :])]
    return chunks


async def _send_reply_audio(ws: WebSocket, text: str, spec: CharacterSpec | None):
    """Streaming TTS: режем текст на 1-4 sentence-chunks, синтезируем их
    параллельно, шлём по готовности. Клиент queue'ит и проигрывает по порядку
    через поле seq."""
    try:
        await ws.send_json({"type": "reply_text", "text": text})
        # envelope сохраняем для совместимости с клиентами, использующими его
        # как единый «конверт» ответа.
        char_id = spec.id if spec is not None else "default"
        await ws.send_json({"type": "reply_envelope", "text": text, "character": char_id})
        if not text.strip():
            return
        chunks = _split_for_streaming(text)
        logger.info(f"[tts-stream] {len(chunks)} chunk(s) for {len(text)} chars character={char_id}")
        t0 = time.monotonic()
        tasks = [asyncio.create_task(tts_synth(c, spec)) for c in chunks]
        first_ms: int | None = None
        for seq, task in enumerate(tasks):
            try:
                audio = await task
            except Exception as e:
                logger.warning(f"[tts-stream] chunk {seq} failed: {e}")
                continue
            if not audio:
                continue
            dt_ms = int((time.monotonic() - t0) * 1000)
            if first_ms is None:
                first_ms = dt_ms
            is_final = seq == len(tasks) - 1
            b64 = base64.b64encode(audio).decode("ascii")
            await ws.send_json({
                "type": "reply_audio",
                "ogg_b64": b64,
                "mime": "audio/mpeg",
                "seq": seq,
                "final": is_final,
            })
            logger.info(
                f"[tts-stream] seq={seq}/{len(tasks)-1} {len(audio)}b "
                f"chars={len(chunks[seq])} elapsed={dt_ms}ms final={is_final}"
            )
        total = int((time.monotonic() - t0) * 1000)
        await ws.send_json({
            "type": "telemetry", "stage": "tts", "ms": total,
            "extra": {
                "chars": len(text),
                "chunks": len(chunks),
                "first_ms": first_ms or 0,
                "character": char_id,
            },
        })
    except Exception as e:
        logger.warning(f"reply-audio send fail: {e}")


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("server_app:app", host="0.0.0.0", port=PORT, log_level="info")
