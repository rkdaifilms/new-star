import { buildGeminiVideoRequest } from "../supabase/functions/ai-gateway/video-provider.ts";
import { normalizeNewStarSearchResults, normalizePrivateIndexRows, formatRetrievedContext } from "../supabase/functions/ai-gateway/search-provider.ts";
import { detectReplyScript } from "../supabase/functions/ai-gateway/language-policy.ts";
import { readBoundedJson } from "../supabase/functions/ai-gateway/request-body.ts";
import { buildChatProviderPlan } from "../supabase/functions/ai-gateway/provider-routing.ts";


Deno.test("self-hosted chat provider plan contains only the private New Star model", () => {
  const run = async () => "ok";
  const plan = buildChatProviderPlan({
    selfHostedOnly: true,
    newStarConfigured: true,
    newStarApiPrimary: false,
    enableLocalTestFallback: true,
    newStarModelKey: "n".repeat(40),
    geminiKeys: ["gemini-secret"],
    openaiKeys: ["openai-secret"],
    openrouterKeys: ["openrouter-secret"],
    runNewStarHostedModel: run,
    runGemini: run,
    runOpenAI: run,
    runOpenRouter: run
  });
  if (plan.length !== 1 || plan[0].name !== "new-star-ai") {
    throw new Error("Self-hosted mode must select only the New Star private model");
  }
  if (plan[0].keys.length !== 1 || plan[0].keys[0] !== "n".repeat(40)) {
    throw new Error("Self-hosted mode must not pass external provider keys to inference");
  }
});

Deno.test("self-hosted chat refuses an unconfigured private model instead of falling back", () => {
  const run = async () => "ok";
  const plan = buildChatProviderPlan({
    selfHostedOnly: true,
    newStarConfigured: false,
    newStarApiPrimary: false,
    enableLocalTestFallback: true,
    newStarModelKey: "",
    geminiKeys: ["gemini-secret"],
    openaiKeys: ["openai-secret"],
    openrouterKeys: ["openrouter-secret"],
    runNewStarHostedModel: run,
    runGemini: run,
    runOpenAI: run,
    runOpenRouter: run
  });
  if (plan.length !== 0) throw new Error("Self-hosted mode must fail closed when private model configuration is missing");
});

Deno.test("normal provider mode retains only configured provider adapters", () => {
  const run = async () => "ok";
  const plan = buildChatProviderPlan({
    selfHostedOnly: false,
    newStarConfigured: false,
    newStarApiPrimary: false,
    enableLocalTestFallback: false,
    newStarModelKey: "",
    geminiKeys: ["gemini-secret"],
    openaiKeys: [],
    openrouterKeys: ["openrouter-secret"],
    runNewStarHostedModel: run,
    runGemini: run,
    runOpenAI: run,
    runOpenRouter: run
  });
  if (plan.map((provider) => provider.name).join(",") !== "gemini,openrouter") {
    throw new Error("Normal provider mode did not filter unconfigured providers correctly");
  }
});

Deno.test("video request builder clamps unsupported values", () => {
  const request = buildGeminiVideoRequest("gemini-omni-1.2-flash", "A sunrise", "4:3", "4K", "30s");
  const format = request.response_format[0];
  if (format.aspect_ratio !== "16:9") throw new Error("Unsupported aspect ratio was not clamped");
  if (format.resolution !== "720p") throw new Error("Unsupported resolution was not clamped");
  if (format.duration !== "6s") throw new Error("Unsupported duration was not clamped");
});

Deno.test("video request builder preserves allowed portrait settings", () => {
  const request = buildGeminiVideoRequest("gemini-omni-1.2-flash", "A creator intro", "9:16", "1080p", "15s");
  const format = request.response_format[0];
  if (format.aspect_ratio !== "9:16" || format.resolution !== "1080p" || format.duration !== "15s") {
    throw new Error("Allowed video settings were not preserved");
  }
});

Deno.test("search normalization accepts safe HTTPS results and rejects unsafe URLs", () => {
  const results = normalizeNewStarSearchResults([
    { title: "New Star", url: "https://example.com/article", snippet: "A result" },
    { title: "Unsafe", url: "http://example.com" },
    { title: "Credentials", url: "https://user:pass@example.com" },
    { title: "", url: "https://example.com/empty" }
  ], 5);
  if (results.length !== 1 || results[0].source !== "example.com") {
    throw new Error("Search result validation did not filter unsafe entries");
  }
  if (!formatRetrievedContext(results).includes("https://example.com/article")) {
    throw new Error("Retrieved context omitted the verified source URL");
  }
});


Deno.test("private search index rows share HTTPS validation and result limits", () => {
  const rows = normalizePrivateIndexRows([
    { title: "Local result", url: "https://docs.newstar.example/ai", snippet: "Private search index", published_at: null },
    { title: "Unsafe result", url: "javascript:alert(1)", snippet: "must be rejected" },
    { title: "Credential URL", url: "https://user:pass@example.com/private", snippet: "must be rejected" }
  ], 5);
  if (rows.length !== 1 || rows[0].title !== "Local result" || rows[0].source !== "docs.newstar.example") {
    throw new Error("Private index results did not pass the shared safe URL normalizer");
  }
  if (normalizePrivateIndexRows(rows, 0).length !== 0) {
    throw new Error("Private index result limit was not enforced");
  }
});

Deno.test("reply language detection identifies Devanagari without assuming Hindi", () => {
  const hint = detectReplyScript("मलाई नयाँ स्टारको एआई इन्जिन जाँच गर्न मद्दत गर्नुहोस्");
  if (!hint || hint.script !== "Devanagari" || !hint.instruction.includes("Nepali")) {
    throw new Error("Devanagari language guidance is missing");
  }
});

Deno.test("bounded JSON reader rejects wrong content type and oversized bodies", async () => {
  const wrongType = await readBoundedJson(new Request("https://local.test", {
    method: "POST", headers: { "Content-Type": "text/plain" }, body: "not json"
  }), 100);
  if (wrongType.ok || wrongType.status !== 415) throw new Error("Wrong content type was not rejected");

  const oversized = await readBoundedJson(new Request("https://local.test", {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ value: "x".repeat(200) })
  }), 100);
  if (oversized.ok || oversized.status !== 413) throw new Error("Oversized request was not rejected");

  const valid = await readBoundedJson(new Request("https://local.test", {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ ok: true })
  }), 100);
  if (!valid.ok || (valid.value as { ok?: boolean }).ok !== true) throw new Error("Valid JSON request was not parsed");
});

Deno.test("AI gateway has no duplicate local functions or duplicate search route", async () => {
  const source = await Deno.readTextFile(new URL("../supabase/functions/ai-gateway/index.ts", import.meta.url));
  const functionNames = [...source.matchAll(/(?:async\s+)?function\s+([A-Za-z0-9_]+)\s*\(/g)].map((match) => match[1]);
  const duplicates = functionNames.filter((name, index) => functionNames.indexOf(name) !== index);
  if (duplicates.length) throw new Error("Duplicate function declarations: " + [...new Set(duplicates)].join(", "));

  const searchRoutes = source.match(/if \(body\?\.action === "search"\)/g) || [];
  if (searchRoutes.length !== 1) throw new Error("Expected exactly one search route; found " + searchRoutes.length);

  const imports = [...source.matchAll(/^import\s+\{([^}]+)\}\s+from\s+["'][^"']+["'];/gm)]
    .flatMap((match) => match[1].split(",").map((part) => part.trim().split(/\s+as\s+/)[0]));
  const importCollisions = imports.filter((name) => functionNames.includes(name));
  if (importCollisions.length) throw new Error("Imported functions redeclared locally: " + [...new Set(importCollisions)].join(", "));
  if (!source.includes("key.length < 32") || source.includes('key !== "local-no-key"')) {
    throw new Error("Private model must require a real API key and must not use a dummy key");
  }
  if (!source.includes("newStarModelKey.length >= 32")) {
    throw new Error("Private model must not be reported configured without its API key");
  }

  const requiredSelfHostedGuards = [
    "NEW_STAR_AI_MODE",
    "NEW_STAR_VISION_MODEL_NOT_DEPLOYED",
    "NEW_STAR_IMAGE_MODEL_NOT_DEPLOYED",
    "NEW_STAR_SPEECH_MODEL_NOT_DEPLOYED",
    "NEW_STAR_STT_MODEL_NOT_DEPLOYED",
    "NEW_STAR_VIDEO_MODEL_NOT_DEPLOYED",
    "NEW_STAR_VIDEO_WORKER_NOT_DEPLOYED",
    "NEW_STAR_SEARCH_WORKER_NOT_DEPLOYED"
  ];
  const missingGuards = requiredSelfHostedGuards.filter((guard) => !source.includes(guard));
  if (missingGuards.length) throw new Error("Missing self-hosted safety guards: " + missingGuards.join(", "));
  const videoStatusRoute = source.slice(source.indexOf('if (body?.action === "video-status")'));
  if (!videoStatusRoute.includes("NEW_STAR_VIDEO_WORKER_NOT_DEPLOYED")) {
    throw new Error("Self-hosted video-status route could still call an external provider");
  }
});
