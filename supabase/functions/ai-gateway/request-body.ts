export type JsonBodyResult =
  | { ok: true; value: unknown }
  | { ok: false; status: 400 | 413 | 415; error: string };

export async function readBoundedJson(
  request: Request,
  maxBytes: number,
): Promise<JsonBodyResult> {
  const contentType = (request.headers.get("content-type") || "")
    .split(";")[0]
    .trim()
    .toLowerCase();
  if (contentType !== "application/json") {
    return { ok: false, status: 415, error: "Content-Type must be application/json." };
  }

  if (request.headers.has("transfer-encoding")) {
    return { ok: false, status: 400, error: "Transfer-Encoding is not accepted." };
  }
  const rawLength = request.headers.get("content-length");
  if (rawLength !== null) {
    if (!/^\d+$/.test(rawLength.trim())) {
      return { ok: false, status: 400, error: "Invalid Content-Length header." };
    }
    if (Number(rawLength) > maxBytes) {
      return { ok: false, status: 413, error: "Request body is too large." };
    }
  }
  if (!request.body) {
    return { ok: false, status: 400, error: "Request body is empty." };
  }

  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > maxBytes) {
        try {
          await reader.cancel("Request body exceeds configured limit.");
        } catch {
          // The size limit remains authoritative even if the stream cannot be cancelled.
        }
        return { ok: false, status: 413, error: "Request body is too large." };
      }
      chunks.push(value);
    }
  } catch {
    return { ok: false, status: 400, error: "Request body could not be read." };
  }

  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  try {
    return { ok: true, value: JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes)) };
  } catch {
    return { ok: false, status: 400, error: "Invalid JSON request body." };
  }
}
