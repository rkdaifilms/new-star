export type PrivateSearchResult = {
  ok: true;
  results: Array<{ title: string; url: string; snippet: string; fetchedAt: number; source: string }>;
} | { ok: false; status: number; error: string };

const MAX_WORKER_RESPONSE_BYTES = 1_048_576;

function base(value: string): string | null {
  try {
    const url = new URL(value.trim());
    if (url.protocol !== "https:" || url.username || url.password || url.search || url.hash) return null;
    return url.toString().replace(/\/$/, "");
  } catch {
    return null;
  }
}

/**
 * Read and parse the worker response before clearing the abort timer.
 * The old implementation returned Response and cleared its timer as soon as
 * headers arrived, leaving response.json() unbounded and allowing huge bodies.
 */
async function request(
  baseUrl: string,
  key: string,
  path: string,
  body: unknown,
): Promise<{ response: Response; data: Record<string, unknown> }> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 25_000);
  try {
    const response = await fetch(baseUrl + path, {
      method: "POST",
      redirect: "error",
      signal: controller.signal,
      headers: {
        Authorization: "Bearer " + key,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
    });

    const declaredLength = Number(response.headers.get("content-length") || 0);
    if (Number.isFinite(declaredLength) && declaredLength > MAX_WORKER_RESPONSE_BYTES) {
      await response.body?.cancel();
      throw new Error("Private search worker response exceeded the 1 MiB limit.");
    }

    if (!response.body) return { response, data: {} };
    const reader = response.body.getReader();
    const chunks: Uint8Array[] = [];
    let total = 0;
    try {
      while (true) {
        const part = await reader.read();
        if (part.done) break;
        total += part.value.byteLength;
        if (total > MAX_WORKER_RESPONSE_BYTES) {
          await reader.cancel();
          throw new Error("Private search worker response exceeded the 1 MiB limit.");
        }
        chunks.push(part.value);
      }
    } finally {
      reader.releaseLock();
    }

    const bytes = new Uint8Array(total);
    let offset = 0;
    for (const chunk of chunks) {
      bytes.set(chunk, offset);
      offset += chunk.byteLength;
    }

    let data: Record<string, unknown> = {};
    try {
      const parsed: unknown = JSON.parse(new TextDecoder().decode(bytes));
      if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
        data = parsed as Record<string, unknown>;
      }
    } catch {
      // Callers report an invalid response shape without leaking worker output.
    }
    return { response, data };
  } finally {
    clearTimeout(timer);
  }
}

function workerFailure(error: unknown, fallback: string): { ok: false; status: number; error: string } {
  if (error instanceof Error && error.name === "AbortError") {
    return { ok: false, status: 503, error: "Private search worker timed out." };
  }
  if (error instanceof Error && error.message.includes("exceeded the 1 MiB limit")) {
    return { ok: false, status: 502, error: error.message };
  }
  return { ok: false, status: 503, error: fallback };
}

export async function privateSearch(
  baseUrl: string,
  key: string,
  query: string,
  limit: number,
): Promise<PrivateSearchResult> {
  const endpoint = base(baseUrl);
  if (!endpoint || key.trim().length < 32) {
    return { ok: false, status: 503, error: "Private search HTTPS endpoint or API key is not configured." };
  }
  try {
    const { response, data } = await request(endpoint, key, "/v1/search", { query, limit });
    if (!response.ok) {
      return {
        ok: false,
        status: response.status,
        error: typeof data.detail === "string" ? data.detail.slice(0, 300) : "Private search worker rejected the query.",
      };
    }
    if (!Array.isArray(data.results)) {
      return { ok: false, status: 502, error: "Private search worker returned an invalid result set." };
    }
    const results = data.results
      .filter((item: any) =>
        typeof item?.title === "string" &&
        typeof item?.url === "string" &&
        typeof item?.snippet === "string" &&
        /^https?:\/\//i.test(item.url)
      )
      .slice(0, limit)
      .map((item: any) => ({
        title: item.title.slice(0, 500),
        url: item.url.slice(0, 2048),
        snippet: item.snippet.slice(0, 1500),
        fetchedAt: Number(item.fetchedAt) || 0,
        source: "new_star_private_index",
      }));
    return { ok: true, results };
  } catch (error) {
    return workerFailure(error, "Private search worker is unreachable.");
  }
}

export async function privateCrawl(
  baseUrl: string,
  key: string,
  url: string,
): Promise<
  | { ok: true; title: string; url: string; charactersIndexed: number; pagesIndexed: number }
  | { ok: false; status: number; error: string }
> {
  const endpoint = base(baseUrl);
  if (!endpoint || key.trim().length < 32) {
    return { ok: false, status: 503, error: "Private search HTTPS endpoint or API key is not configured." };
  }
  try {
    const { response, data } = await request(endpoint, key, "/v1/crawl", { url });
    if (!response.ok) {
      return {
        ok: false,
        status: response.status,
        error: typeof data.detail === "string" ? data.detail.slice(0, 300) : "Private crawler rejected the URL.",
      };
    }
    if (
      typeof data.title !== "string" ||
      typeof data.url !== "string" ||
      !Number.isFinite(data.characters_indexed)
    ) {
      return { ok: false, status: 502, error: "Private crawler returned an invalid result." };
    }
    return {
      ok: true,
      title: data.title,
      url: data.url,
      charactersIndexed: Number(data.characters_indexed),
      pagesIndexed: Number(data.pages_indexed) || 1,
    };
  } catch (error) {
    return workerFailure(error, "Private search worker is unreachable.");
  }
}
