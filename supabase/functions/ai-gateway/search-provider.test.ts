import { formatRetrievedContext, normalizeNewStarSearchResults } from "./search-provider.ts";

Deno.test("normalizes valid HTTPS results and caps untrusted fields", () => {
  const results = normalizeNewStarSearchResults([
    { title: "  New Star  ", url: "https://example.com/path", snippet: "s".repeat(1500), publishedAt: "2026-11-24" }
  ], 5);
  if (results.length !== 1) throw new Error("Expected one valid result");
  if (results[0].title !== "New Star") throw new Error("Title was not normalized");
  if (results[0].source !== "example.com") throw new Error("Source host was not derived from URL");
  if (results[0].snippet.length !== 1200) throw new Error("Snippet limit was not applied");
});

Deno.test("rejects non-HTTPS, credentialed, malformed, and incomplete result URLs", () => {
  const results = normalizeNewStarSearchResults([
    { title: "HTTP", url: "http://example.com" },
    { title: "Credentials", url: "https://user:pass@example.com" },
    { title: "Script", url: "javascript:alert(1)" },
    { title: "Missing URL" },
    { title: "Bad URL", url: "not a URL" }
  ]);
  if (results.length !== 0) throw new Error("Unsafe or incomplete result was accepted");
});

Deno.test("limits result count and rejects invalid limits", () => {
  const input = Array.from({ length: 12 }, (_, index) => ({
    title: "Result " + index, url: "https://example.com/" + index
  }));
  if (normalizeNewStarSearchResults(input, 3).length !== 3) throw new Error("Result count limit was not applied");
  if (normalizeNewStarSearchResults(input, 11).length !== 0) throw new Error("Invalid limit was accepted");
});

Deno.test("formats bounded retrieved search context with source URLs", () => {
  const context = formatRetrievedContext([
    { title: "  Nepal AI  ", url: "https://example.org/ai", snippet: "Useful excerpt" },
    { title: "Unsafe", url: "http://example.org", snippet: "must not appear" },
  ], 5);
  if (!context.includes("[Source 1] Nepal AI")) throw new Error("Source title was not formatted");
  if (!context.includes("URL: https://example.org/ai")) throw new Error("Source URL was not preserved");
  if (context.includes("Unsafe") || context.includes("must not appear")) {
    throw new Error("Unsafe source URL was included in retrieved context");
  }
});
