import base64
import json
import os
import subprocess
import tempfile
import threading
from pathlib import Path
from typing import Optional

from fastapi import FastAPI, File, Header, HTTPException, UploadFile
from faster_whisper import WhisperModel
from pydantic import BaseModel, Field

app = FastAPI(title="New Star Private Speech Worker", docs_url=None, redoc_url=None)
MODEL_DIR = Path(os.getenv("NEW_STAR_SPEECH_MODEL_DIR", "/models"))
CACHE_DIR = Path(os.getenv("NEW_STAR_SPEECH_CACHE", "/tmp/new-star-speech"))
API_KEY = os.getenv("NEW_STAR_SPEECH_API_KEY", "")
WHISPER_MODEL = os.getenv("NEW_STAR_WHISPER_MODEL", "small")
WHISPER_DEVICE = os.getenv("NEW_STAR_WHISPER_DEVICE", "cuda")
WHISPER_COMPUTE = os.getenv("NEW_STAR_WHISPER_COMPUTE", "float16")
MAX_AUDIO_BYTES = int(os.getenv("NEW_STAR_MAX_AUDIO_BYTES", str(8 * 1024 * 1024)))
VOICE_MAP_RAW = os.getenv("NEW_STAR_PIPER_VOICES", "{}")
_model = None
_model_lock = threading.Lock()


def voice_map() -> dict:
    try:
        value = json.loads(VOICE_MAP_RAW)
    except json.JSONDecodeError as exc:
        raise RuntimeError("NEW_STAR_PIPER_VOICES must be a JSON object") from exc
    if not isinstance(value, dict):
        raise RuntimeError("NEW_STAR_PIPER_VOICES must be a JSON object")
    result = {}
    for language, path in value.items():
        if not isinstance(language, str) or not isinstance(path, str):
            raise RuntimeError("Voice map entries must be strings")
        candidate = (MODEL_DIR / path).resolve()
        if MODEL_DIR.resolve() not in candidate.parents or candidate.suffix != ".onnx":
            raise RuntimeError("Voice models must be .onnx files inside the models directory")
        result[language.lower()] = candidate
    return result


def require_auth(authorization: Optional[str]) -> None:
    if len(API_KEY) < 32:
        raise HTTPException(status_code=503, detail="Private speech worker API key is not configured")
    if authorization != "Bearer " + API_KEY:
        raise HTTPException(status_code=401, detail="Unauthorized")


def whisper():
    global _model
    with _model_lock:
        if _model is None:
            CACHE_DIR.mkdir(parents=True, exist_ok=True)
            try:
                _model = WhisperModel(WHISPER_MODEL, device=WHISPER_DEVICE, compute_type=WHISPER_COMPUTE, download_root=str(CACHE_DIR / "whisper"))
            except Exception as exc:
                raise HTTPException(status_code=503, detail="Private speech-recognition model could not be loaded") from exc
    return _model


class SpeechRequest(BaseModel):
    input: str = Field(min_length=1, max_length=3500)
    language: str = Field(default="auto", min_length=2, max_length=20)


@app.get("/health")
def health():
    return {"ok": True, "service": "new-star-private-speech", "stt_model_loaded": _model is not None}


@app.post("/v1/audio/transcriptions")
async def transcriptions(file: UploadFile = File(...), authorization: Optional[str] = Header(default=None)):
    require_auth(authorization)
    suffix = Path(file.filename or "recording.webm").suffix.lower()
    if suffix not in {".webm", ".ogg", ".mp4", ".m4a", ".mp3", ".wav", ".mpeg"}:
        raise HTTPException(status_code=415, detail="Unsupported audio file type")
    data = await file.read(MAX_AUDIO_BYTES + 1)
    if not data or len(data) > MAX_AUDIO_BYTES:
        raise HTTPException(status_code=413, detail="Audio file is empty or too large")
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    temp_path = None
    try:
        with tempfile.NamedTemporaryFile(prefix="ns-audio-", suffix=suffix, dir=str(CACHE_DIR), delete=False) as temp:
            temp.write(data)
            temp_path = temp.name
        model = whisper()
        segments, info = model.transcribe(temp_path, beam_size=5, vad_filter=True)
        transcript = " ".join(segment.text.strip() for segment in segments).strip()
        if not transcript:
            raise HTTPException(status_code=422, detail="No speech could be recognized")
        return {"text": transcript, "language": info.language, "language_probability": info.language_probability}
    finally:
        if temp_path:
            try:
                os.unlink(temp_path)
            except OSError:
                pass


@app.post("/v1/audio/speech")
def speech(request: SpeechRequest, authorization: Optional[str] = Header(default=None)):
    require_auth(authorization)
    voice_path = voice_map().get(request.language.lower())
    if voice_path is None:
        raise HTTPException(status_code=422, detail="No private Piper voice is configured for language: " + request.language)
    if not voice_path.is_file() or not Path(str(voice_path) + ".json").is_file():
        raise HTTPException(status_code=503, detail="Configured Piper voice model or JSON config is missing")
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    output_path = None
    try:
        with tempfile.NamedTemporaryFile(prefix="ns-voice-", suffix=".wav", dir=str(CACHE_DIR), delete=False) as temp:
            output_path = temp.name
        completed = subprocess.run(["piper", "--model", str(voice_path), "--output_file", output_path], input=request.input, text=True, capture_output=True, timeout=90, check=False)
        if completed.returncode != 0:
            raise HTTPException(status_code=502, detail="Private speech synthesis process failed")
        audio = Path(output_path).read_bytes()
        if len(audio) < 44 or len(audio) > 20 * 1024 * 1024:
            raise HTTPException(status_code=502, detail="Private speech synthesis returned invalid audio")
        return {"audio": base64.b64encode(audio).decode("ascii"), "mimeType": "audio/wav", "language": request.language.lower()}
    except subprocess.TimeoutExpired as exc:
        raise HTTPException(status_code=504, detail="Private speech synthesis timed out") from exc
    finally:
        if output_path:
            try:
                os.unlink(output_path)
            except OSError:
                pass
