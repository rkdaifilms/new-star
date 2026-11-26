import { readWorkerJson } from "./worker-response.ts";
export type PrivateImageResult =
  | { ok: true; image: string; mimeType: "image/png" }
  | { ok: false; status: number; error: string };

export function privateImageEndpoint(baseUrl: string): string | null {
  try {
    const endpoint = new URL(baseUrl.trim());
    if (endpoint.protocol !== "https:" || endpoint.username || endpoint.password || endpoint.search || endpoint.hash) return null;
    return endpoint.toString().replace(/\/$/, "") + "/v1/images/generations";
  } catch {
    return null;
  }
}

export async function privateImageGeneration(
  baseUrl: string,
  apiKey: string,
  prompt: string,
  aspectRatio: string,
  imageSize: string,
): Promise<PrivateImageResult> {
  const endpoint = privateImageEndpoint(baseUrl);
  if (!endpoint || apiKey.trim().length < 32) {
    return { ok: false, status: 503, error: "Private image worker HTTPS endpoint or API key is not configured." };
  }
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 120000);
  try {
    const response = await fetch(endpoint, {
      method: "POST",
      redirect: "error",
      signal: controller.signal,
      headers: { Authorization: "Bearer " + apiKey, "Content-Type": "application/json" },
      body: JSON.stringify({ prompt, aspect_ratio: aspectRatio, image_size: imageSize }),
    });
    const parsed = await readWorkerJson(response, 16 * 1024 * 1024);
    const data = parsed.ok ? parsed.value : {};
    if (!response.ok) {
      const detail = typeof data?.detail === "string" ? data.detail : "Private image worker rejected the request.";
      return { ok: false, status: response.status >= 400 && response.status < 600 ? response.status : 502, error: detail.slice(0, 300) };
    }
    if (typeof data?.image !== "string" || data.image.length < 100 || data.image.length > 12000000) {
      return { ok: false, status: 502, error: "Private image worker returned invalid image data." };
    }
    return { ok: true, image: data.image, mimeType: "image/png" };
  } catch (error) {
    return { ok: false, status: 503, error: error instanceof Error && error.name === "AbortError" ? "Private image generation timed out." : "Private image worker is unreachable." };
  } finally {
    clearTimeout(timer);
  }
}
