import { readWorkerBytes, readWorkerJson } from "./worker-response.ts";
export type PrivateVideoStartResult =
  | { ok: true; jobId: string; status: string; model: string }
  | { ok: false; status: number; error: string };

export type PrivateVideoStatusResult =
  | { ok: true; status: "queued" | "in_progress" | "completed" | "failed"; model: string; failureCode?: string; videoBytes?: Uint8Array }
  | { ok: false; status: number; error: string };

function privateVideoBase(baseUrl: string): string | null {
  try {
    const endpoint = new URL(baseUrl.trim());
    if (endpoint.protocol !== "https:" || endpoint.username || endpoint.password || endpoint.search || endpoint.hash) return null;
    return endpoint.toString().replace(/\/$/, "");
  } catch {
    return null;
  }
}

function validJobId(jobId: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(jobId);
}

export async function privateVideoStart(
  baseUrl: string,
  apiKey: string,
  prompt: string,
  aspectRatio: string,
  resolution: string,
  duration: string,
): Promise<PrivateVideoStartResult> {
  const base = privateVideoBase(baseUrl);
  if (!base || apiKey.trim().length < 32) return { ok: false, status: 503, error: "Private video worker HTTPS endpoint or API key is not configured." };
  if (!["16:9", "9:16"].includes(aspectRatio) || resolution !== "480p" || duration !== "6s") {
    return { ok: false, status: 422, error: "The private video worker currently supports 480p output and 6-second clips only." };
  }
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 15000);
  try {
    const response = await fetch(base + "/v1/videos/generations", {
      method: "POST",
      redirect: "error",
      signal: controller.signal,
      headers: { Authorization: "Bearer " + apiKey, "Content-Type": "application/json" },
      body: JSON.stringify({ prompt, aspect_ratio: aspectRatio, resolution, duration }),
    });
    const parsed = await readWorkerJson(response, 64 * 1024);
    const data = parsed.ok ? parsed.value : {};
    if (!response.ok) return { ok: false, status: response.status, error: typeof data?.detail === "string" ? data.detail.slice(0, 300) : "Private video worker rejected the request." };
    if (typeof data?.job_id !== "string" || !validJobId(data.job_id)) return { ok: false, status: 502, error: "Private video worker returned an invalid job ID." };
    return { ok: true, jobId: data.job_id, status: String(data.status || "queued"), model: String(data.model || "New Star private video model") };
  } catch (error) {
    return { ok: false, status: 503, error: error instanceof Error && error.name === "AbortError" ? "Private video worker request timed out." : "Private video worker is unreachable." };
  } finally {
    clearTimeout(timer);
  }
}

export async function privateVideoStatus(
  baseUrl: string,
  apiKey: string,
  jobId: string,
): Promise<PrivateVideoStatusResult> {
  const base = privateVideoBase(baseUrl);
  if (!base || apiKey.trim().length < 32 || !validJobId(jobId)) return { ok: false, status: 503, error: "Private video worker configuration or job ID is invalid." };
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 20000);
  try {
    const response = await fetch(base + "/v1/videos/jobs/" + jobId, {
      method: "GET",
      redirect: "error",
      signal: controller.signal,
      headers: { Authorization: "Bearer " + apiKey },
    });
    const parsed = await readWorkerJson(response, 64 * 1024);
    const data = parsed.ok ? parsed.value : {};
    if (!response.ok) return { ok: false, status: response.status, error: typeof data?.detail === "string" ? data.detail.slice(0, 300) : "Could not read private video job status." };
    const states = new Set(["queued", "in_progress", "completed", "failed"]);
    if (typeof data?.status !== "string" || !states.has(data.status)) return { ok: false, status: 502, error: "Private video worker returned an invalid job state." };
    if (data.status !== "completed") return { ok: true, status: data.status, model: String(data.model || "New Star private video model"), failureCode: typeof data.failure_code === "string" ? data.failure_code : undefined };
    const outputController = new AbortController();
    const outputTimer = setTimeout(() => outputController.abort(), 90000);
    try {
      const output = await fetch(base + "/v1/videos/jobs/" + jobId + "/output", {
        method: "GET",
        redirect: "error",
        signal: outputController.signal,
        headers: { Authorization: "Bearer " + apiKey },
      });
      if (!output.ok) return { ok: false, status: output.status, error: "Private video worker could not provide the completed MP4." };
      const mime = (output.headers.get("content-type") || "").split(";")[0].trim().toLowerCase();
      if (mime !== "video/mp4") return { ok: false, status: 502, error: "Private video worker returned an unsupported output format." };
      const outputBody = await readWorkerBytes(output, 50 * 1024 * 1024);
      if (!outputBody.ok || !outputBody.bytes.byteLength) return { ok: false, status: 502, error: "Private video output is empty, unreadable, or exceeds 50 MiB." };
      const bytes = outputBody.bytes;
      return { ok: true, status: "completed", model: String(data.model || "New Star private video model"), videoBytes: bytes };
    } finally {
      clearTimeout(outputTimer);
    }
  } catch (error) {
    return { ok: false, status: 503, error: error instanceof Error && error.name === "AbortError" ? "Private video status request timed out." : "Private video worker is unreachable." };
  } finally {
    clearTimeout(timer);
  }
}
