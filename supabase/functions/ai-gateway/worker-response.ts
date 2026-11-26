export type WorkerJsonResult =
  | { ok: true; value: Record<string, unknown> }
  | { ok: false; error: "invalid_json" | "response_too_large" | "response_unreadable" };

export async function readWorkerJson(
  response: Response,
  maxBytes: number,
): Promise<WorkerJsonResult> {
  if (!Number.isSafeInteger(maxBytes) || maxBytes < 1) {
    throw new RangeError("maxBytes must be a positive safe integer");
  }

  const declaredLength = response.headers.get("content-length");
  if (declaredLength !== null) {
    if (!/^[0-9]+$/.test(declaredLength.trim())) {
      return { ok: false, error: "invalid_json" };
    }
    if (Number(declaredLength) > maxBytes) {
      try {
        await response.body?.cancel("Worker response exceeds configured limit.");
      } catch {
        // The response remains rejected even when cancellation is unavailable.
      }
      return { ok: false, error: "response_too_large" };
    }
  }

  if (!response.body) return { ok: false, error: "response_unreadable" };

  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > maxBytes) {
        try {
          await reader.cancel("Worker response exceeds configured limit.");
        } catch {
          // Keep the configured limit authoritative.
        }
        return { ok: false, error: "response_too_large" };
      }
      chunks.push(value);
    }
  } catch {
    return { ok: false, error: "response_unreadable" };
  }

  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }

  try {
    const parsed: unknown = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return { ok: false, error: "invalid_json" };
    }
    return { ok: true, value: parsed as Record<string, unknown> };
  } catch {
    return { ok: false, error: "invalid_json" };
  }
}

export async function readWorkerBytes(
  response: Response,
  maxBytes: number,
): Promise<{ ok: true; bytes: Uint8Array } | { ok: false; error: "response_too_large" | "response_unreadable" }> {
  if (!Number.isSafeInteger(maxBytes) || maxBytes < 1) {
    throw new RangeError("maxBytes must be a positive safe integer");
  }
  const declaredLength = response.headers.get("content-length");
  if (declaredLength !== null) {
    if (!/^[0-9]+$/.test(declaredLength.trim())) {
      return { ok: false, error: "response_unreadable" };
    }
    if (Number(declaredLength) > maxBytes) {
      try {
        await response.body?.cancel("Worker response exceeds configured limit.");
      } catch {
        // Keep the configured limit authoritative.
      }
      return { ok: false, error: "response_too_large" };
    }
  }
  if (!response.body) return { ok: false, error: "response_unreadable" };
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > maxBytes) {
        try {
          await reader.cancel("Worker response exceeds configured limit.");
        } catch {
          // Keep the configured limit authoritative.
        }
        return { ok: false, error: "response_too_large" };
      }
      chunks.push(value);
    }
  } catch {
    return { ok: false, error: "response_unreadable" };
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return { ok: true, bytes };
}
