export type FetchImplementation = typeof fetch;

export async function fetchWithTimeout(
  url: string | URL,
  init: RequestInit,
  timeoutMs = 16000,
  fetchImplementation: FetchImplementation = fetch,
): Promise<Response> {
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1) {
    throw new RangeError("timeoutMs must be a positive safe integer");
  }

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  let response: Response;
  try {
    response = await fetchImplementation(url, { ...init, signal: controller.signal });
  } catch (error) {
    clearTimeout(timer);
    throw error;
  }

  if (!response.body) {
    clearTimeout(timer);
    return response;
  }

  const reader = response.body.getReader();
  const body = new ReadableStream<Uint8Array>({
    async pull(streamController) {
      try {
        const { done, value } = await reader.read();
        if (done) {
          clearTimeout(timer);
          streamController.close();
          return;
        }
        streamController.enqueue(value);
      } catch (error) {
        clearTimeout(timer);
        streamController.error(error);
      }
    },
    async cancel(reason) {
      clearTimeout(timer);
      try {
        await reader.cancel(reason);
      } catch {
        // Cancellation is best-effort; the original request remains bounded by its timeout.
      }
    },
  });

  return new Response(body, {
    status: response.status,
    statusText: response.statusText,
    headers: response.headers,
  });
}
