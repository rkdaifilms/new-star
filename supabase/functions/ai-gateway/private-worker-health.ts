import { fetchWithTimeout, type FetchImplementation } from "./fetch-with-timeout.ts";
import { readWorkerJson } from "./worker-response.ts";

export type PrivateWorkerHealth = {
  configured: boolean;
  reachable: boolean;
  healthy: boolean;
  modelLoaded: boolean | null;
  service: string | null;
  reason?: string;
};

function safeBaseUrl(value: string): string | null {
  try {
    const url = new URL(value.trim());
    if (url.protocol !== "https:" || url.username || url.password || url.search || url.hash) return null;
    return url.toString().replace(/\/$/, "");
  } catch {
    return null;
  }
}

export async function probePrivateWorker(
  baseUrl: string,
  apiKey: string,
  fetchImplementation: FetchImplementation = fetch,
): Promise<PrivateWorkerHealth> {
  const endpoint = safeBaseUrl(baseUrl);
  const configured = Boolean(endpoint && apiKey.trim().length >= 32);
  if (!endpoint) {
    return { configured: false, reachable: false, healthy: false, modelLoaded: null, service: null, reason: "A valid HTTPS worker URL is not configured." };
  }
  if (apiKey.trim().length < 32) {
    return { configured: false, reachable: false, healthy: false, modelLoaded: null, service: null, reason: "A worker API key of at least 32 characters is not configured." };
  }

  try {
    const response = await fetchWithTimeout(endpoint + "/health", {
      method: "GET",
      redirect: "error",
      headers: { Accept: "application/json" },
    }, 5000, fetchImplementation);
    const parsed = await readWorkerJson(response, 64 * 1024);
    if (!response.ok || !parsed.ok || parsed.value.ok !== true) {
      await response.body?.cancel().catch(() => {});
      return { configured, reachable: response.ok, healthy: false, modelLoaded: null, service: null, reason: "Worker health endpoint did not return a valid healthy response." };
    }
    const data = parsed.value;
    const modelLoaded = typeof data.model_loaded === "boolean"
      ? data.model_loaded
      : typeof data.stt_model_loaded === "boolean"
        ? data.stt_model_loaded
        : null;
    return {
      configured,
      reachable: true,
      healthy: true,
      modelLoaded,
      service: typeof data.service === "string" ? data.service.slice(0, 80) : null,
    };
  } catch (error) {
    return {
      configured,
      reachable: false,
      healthy: false,
      modelLoaded: null,
      service: null,
      reason: error instanceof Error && error.name === "AbortError" ? "Worker health check timed out." : "Worker health endpoint is unreachable.",
    };
  }
}
