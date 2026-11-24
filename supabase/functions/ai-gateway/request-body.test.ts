import { readBoundedJson } from "./request-body.ts";

Deno.test("reads a valid JSON request with the required content type", async () => {
  const request = new Request("https://example.test", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ action: "chat", messages: [] }),
  });
  const result = await readBoundedJson(request, 1024);
  if (!result.ok || (result.value as { action?: string }).action !== "chat") {
    throw new Error("Valid JSON request was not parsed");
  }
});

Deno.test("rejects non-JSON content types", async () => {
  const request = new Request("https://example.test", {
    method: "POST",
    headers: { "content-type": "text/plain" },
    body: "{}",
  });
  const result = await readBoundedJson(request, 1024);
  if (result.ok || result.status !== 415) throw new Error("Non-JSON request was accepted");
});

Deno.test("rejects oversized declared and streamed bodies", async () => {
  const declared = new Request("https://example.test", {
    method: "POST",
    headers: { "content-type": "application/json", "content-length": "100" },
    body: "{}",
  });
  const declaredResult = await readBoundedJson(declared, 10);
  if (declaredResult.ok || declaredResult.status !== 413) {
    throw new Error("Oversized declared body was accepted");
  }

  const streamed = new Request("https://example.test", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ text: "x".repeat(100) }),
  });
  const streamedResult = await readBoundedJson(streamed, 10);
  if (streamedResult.ok || streamedResult.status !== 413) {
    throw new Error("Oversized streamed body was accepted");
  }
});

Deno.test("rejects malformed JSON", async () => {
  const request = new Request("https://example.test", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: "{",
  });
  const result = await readBoundedJson(request, 1024);
  if (result.ok || result.status !== 400) throw new Error("Malformed JSON was accepted");
});

Deno.test("rejects invalid content length and transfer encoding", async () => {
  const invalidLength = new Request("https://example.test", {
    method: "POST",
    headers: { "content-type": "application/json", "content-length": "not-a-number" },
    body: "{}",
  });
  const invalidResult = await readBoundedJson(invalidLength, 1024);
  if (invalidResult.ok || invalidResult.status !== 400) {
    throw new Error("Invalid Content-Length was accepted");
  }

  const transferEncoded = new Request("https://example.test", {
    method: "POST",
    headers: { "content-type": "application/json", "transfer-encoding": "chunked" },
    body: "{}",
  });
  const transferResult = await readBoundedJson(transferEncoded, 1024);
  if (transferResult.ok || transferResult.status !== 400) {
    throw new Error("Transfer-Encoding request was accepted");
  }
});
