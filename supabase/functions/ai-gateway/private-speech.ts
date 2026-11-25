const AUDIO_MIME_EXTENSIONS: Record<string, string> = {
  "audio/webm": "webm",
  "audio/ogg": "ogg",
  "audio/mp4": "m4a",
  "audio/mpeg": "mp3",
  "audio/mp3": "mp3",
  "audio/wav": "wav",
  "audio/m4a": "m4a",
};

export function privateSpeechEndpoint(base: string, path: string): string | null {
  try {
    const url = new URL(base);
    if (url.protocol !== "https:" || url.username || url.password || url.search || url.hash) return null;
    url.pathname = url.pathname.replace(/\/$/, "") + path;
    return url.toString();
  } catch {
    return null;
  }
}

async function readJson(response: Response): Promise<Record<string, unknown>> {
  const value = await response.json().catch(() => ({}));
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {};
}

export async function privateSpeechSynthesis(
  base: string,
  key: string,
  text: string,
  language: string,
): Promise<{ ok: true; audio: string; mimeType: string } | { ok: false; status: number; error: string }> {
  const endpoint = privateSpeechEndpoint(base, "/v1/audio/speech");
  if (!endpoint || key.length < 32) return { ok: false, status: 503, error: "Private speech worker endpoint or key is not configured securely." };
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 95000);
  try {
    const response = await fetch(endpoint, {
      method: "POST",
      headers: { Authorization: "Bearer " + key, "Content-Type": "application/json" },
      body: JSON.stringify({ input: text, language }),
      signal: controller.signal,
    });
    const payload = await readJson(response);
    if (!response.ok) return { ok: false, status: response.status >= 500 ? 503 : 422, error: "Private speech worker rejected the synthesis request." };
    if (typeof payload.audio !== "string" || payload.audio.length < 100 || payload.audio.length > 28000000 ||
      typeof payload.mimeType !== "string" || !/^audio\/[a-z0-9.+-]+$/i.test(payload.mimeType)) {
      return { ok: false, status: 502, error: "Private speech worker returned invalid audio data." };
    }
    return { ok: true, audio: payload.audio, mimeType: payload.mimeType };
  } catch {
    return { ok: false, status: 503, error: "Private speech worker is unreachable or timed out." };
  } finally {
    clearTimeout(timeout);
  }
}

export async function privateSpeechTranscription(
  base: string,
  key: string,
  audio: string,
  mimeType: string,
): Promise<{ ok: true; transcript: string; language: string } | { ok: false; status: number; error: string }> {
  const endpoint = privateSpeechEndpoint(base, "/v1/audio/transcriptions");
  const extension = AUDIO_MIME_EXTENSIONS[mimeType];
  if (!endpoint || key.length < 32 || !extension) return { ok: false, status: 503, error: "Private speech worker endpoint, key, or audio format is not configured." };
  let bytes: Uint8Array;
  try {
    bytes = Uint8Array.from(atob(audio), (character) => character.charCodeAt(0));
  } catch {
    return { ok: false, status: 400, error: "Voice recording is not valid base64 audio." };
  }
  if (!bytes.length || bytes.length > 8 * 1024 * 1024) return { ok: false, status: 413, error: "Voice recording exceeds the private worker size limit." };
  const form = new FormData();
  const audioBuffer = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(audioBuffer).set(bytes);
  form.append("file", new File([audioBuffer], "new-star-voice." + extension, { type: mimeType }));
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 110000);
  try {
    const response = await fetch(endpoint, {
      method: "POST",
      headers: { Authorization: "Bearer " + key },
      body: form,
      signal: controller.signal,
    });
    const payload = await readJson(response);
    if (!response.ok) return { ok: false, status: response.status >= 500 ? 503 : 422, error: "Private speech worker rejected the transcription request." };
    if (typeof payload.text !== "string" || !payload.text.trim() ||
      typeof payload.language !== "string" || !/^[a-z]{2,3}(-[a-z0-9]+)?$/i.test(payload.language)) {
      return { ok: false, status: 502, error: "Private speech worker returned an invalid transcript or language." };
    }
    return { ok: true, transcript: payload.text.trim(), language: payload.language.toLowerCase() };
  } catch {
    return { ok: false, status: 503, error: "Private speech worker is unreachable or timed out." };
  } finally {
    clearTimeout(timeout);
  }
}
