import { buildGeminiVideoRequest } from "../supabase/functions/ai-gateway/video-provider.ts";
import { normalizeNewStarSearchResults, normalizePrivateIndexRows, normalizeEmbeddingVector, toPgVectorLiteral, formatRetrievedContext } from "../supabase/functions/ai-gateway/search-provider.ts";
import { detectReplyScript } from "../supabase/functions/ai-gateway/language-policy.ts";
import { readBoundedJson } from "../supabase/functions/ai-gateway/request-body.ts";
import { splitIntoChunks } from "../scripts/new-star-search-ingest.ts";
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



Deno.test("private search ingestion CLI is import-safe and atomically replaces existing chunks", async () => {
  const source = await Deno.readTextFile(new URL("../scripts/new-star-search-ingest.ts", import.meta.url));
  const initialMigration = await Deno.readTextFile(new URL("../supabase/migrations/20261125131109_replace_new_star_search_chunks_rpc.sql", import.meta.url));
  const migration = await Deno.readTextFile(new URL("../supabase/migrations/20261125131209_harden_search_chunk_replacement_rpc.sql", import.meta.url));
  if (!initialMigration.includes("create or replace function public.replace_new_star_search_chunks") ||
      !migration.includes("create or replace function public.replace_new_star_search_chunks")) {
    throw new Error("Both applied replacement RPC migrations must be committed in the repository");
  }
  if (!migration.includes("jsonb_typeof(item.value) <> 'number'")) {
    throw new Error("The final migration must reject non-numeric embedding values");
  }
  if (!source.includes("if (import.meta.main)")) {
    throw new Error("Importing the ingestion utility must not execute its CLI");
  }
  if (!source.includes("vector.length !== 384")) {
    throw new Error("The ingestion utility must enforce the existing 384-dimensional vector schema");
  }
  if (!source.includes("/rpc/replace_new_star_search_chunks") || !source.includes("p_chunks: chunks")) {
    throw new Error("Ingestion must use the atomic replacement RPC");
  }
  if (source.includes("/new_star_search_chunks?on_conflict")) {
    throw new Error("Ingestion must not write directly to the chunk table and leave stale chunks behind");
  }
  if (!migration.includes("delete from public.new_star_search_chunks") ||
      !migration.includes("grant execute on function public.replace_new_star_search_chunks(jsonb) to service_role")) {
    throw new Error("Atomic replacement migration must replace rows and restrict execution to service_role");
  }
});

Deno.test("private search ingestion chunks long content with overlap and no data loss", () => {
  const content = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu";
  const chunks = splitIntoChunks(content, 24, 5);
  const normalized = splitIntoChunks("alpha\t beta\n gamma", 100, 10);
  if (normalized.length !== 1 || normalized[0] !== "alpha beta gamma") {
    throw new Error("Chunker must normalize tabs and newlines before splitting");
  }
  if (chunks.length < 2) throw new Error("Long content was not split into multiple chunks");
  if (!chunks[0].startsWith("alpha") || !chunks[chunks.length - 1].endsWith("mu")) {
    throw new Error("Chunking dropped the beginning or end of the source content");
  }
  if (!chunks.some((chunk, index) => index > 0 && chunks[index - 1].slice(-5).split(" ").some(word => chunk.startsWith(word)))) {
    throw new Error("Chunk overlap was not preserved");
  }
});


Deno.test("private hybrid search validates and serializes exactly 384 embedding dimensions", () => {
  const vector = Array.from({ length: 384 }, (_, index) => index === 0 ? 1 : 0);
  const normalized = normalizeEmbeddingVector(vector);
  if (!normalized || normalized.length !== 384) throw new Error("Valid 384-dimensional embedding was rejected");
  const literal = toPgVectorLiteral(vector);
  if (!literal || !literal.startsWith("[1,0,0") || !literal.endsWith("0]")) {
    throw new Error("Embedding was not serialized as a PostgreSQL vector literal");
  }
  if (normalizeEmbeddingVector(vector.slice(0, 383)) !== null) {
    throw new Error("Incorrect embedding dimension was accepted");
  }
  if (normalizeEmbeddingVector([...vector.slice(0, 383), Number.NaN]) !== null) {
    throw new Error("Non-finite embedding value was accepted");
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

Deno.test("private llama.cpp compose uses documented authentication and parallelism environment variables", async () => {
  const compose = await Deno.readTextFile(new URL("../ai-engine/self-hosted/compose.yaml", import.meta.url));
  if ((compose.match(/LLAMA_API_KEY:/g) || []).length !== 2) {
    throw new Error("Both private model services must configure the documented LLAMA_API_KEY variable");
  }
  if (compose.includes("LLAMA_ARG_API_KEY:")) {
    throw new Error("LLAMA_ARG_API_KEY is not the documented llama.cpp API key variable");
  }
  if (!compose.includes("LLAMA_ARG_N_PARALLEL:") || compose.includes("LLAMA_ARG_PARALLEL:")) {
    throw new Error("Chat server must use the documented LLAMA_ARG_N_PARALLEL variable");
  }
  if (!compose.includes('LLAMA_ARG_UI: "false"') || compose.includes("LLAMA_ARG_NO_WEBUI:")) {
    throw new Error("llama.cpp Web UI must be disabled with the documented LLAMA_ARG_UI environment variable");
  }
});

Deno.test("self-hosted model deployment has one canonical Compose source", async () => {
  const compose = await Deno.readTextFile(new URL("../ai-engine/self-hosted/compose.yaml", import.meta.url));
  const workflow = await Deno.readTextFile(new URL("../.github/workflows/new-star-ai-checks.yml", import.meta.url));
  const docs = await Deno.readTextFile(new URL("../NEW-STAR-AI-ENGINE.md", import.meta.url));
  if (!compose.includes("new-star-model:") || !compose.includes("new-star-embeddings:")) {
    throw new Error("Canonical Compose file must define chat and embedding services");
  }
  if (!compose.includes("127.0.0.1:8080:8080") || !compose.includes("127.0.0.1:8081:8081")) {
    throw new Error("Private model and embedding ports must remain loopback-only");
  }
  if (!workflow.includes('YAML.load_file("ai-engine/self-hosted/compose.yaml")') || workflow.includes("ai-model/docker-compose.yml")) {
    throw new Error("CI must validate the single canonical self-hosted Compose file");
  }
  if (!docs.includes("only model runtime deployment directory is `ai-engine/self-hosted/`")) {
    throw new Error("Architecture documentation must identify the canonical runtime");
  }
  let legacyRuntimeExists = false;
  try {
    await Deno.stat(new URL("../ai-model/docker-compose.yml", import.meta.url));
    legacyRuntimeExists = true;
  } catch (error) {
    if (!(error instanceof Deno.errors.NotFound)) throw error;
  }
  if (legacyRuntimeExists) throw new Error("Duplicate legacy ai-model runtime must not be reintroduced");
});

Deno.test("AI gateway has no duplicate local functions or duplicate search route", async () => {
  const source = await Deno.readTextFile(new URL("../supabase/functions/ai-gateway/index.ts", import.meta.url));
  const functionNames = [...source.matchAll(/(?:async\s+)?function\s+([A-Za-z0-9_]+)\s*\(/g)].map((match) => match[1]);
  const duplicates = functionNames.filter((name, index) => functionNames.indexOf(name) !== index);
  if (duplicates.length) throw new Error("Duplicate function declarations: " + [...new Set(duplicates)].join(", "));

  const searchRoutes = source.match(/if \(body\?\.action === "search"\)/g) || [];
  if (searchRoutes.length !== 1) throw new Error("Expected exactly one search route; found " + searchRoutes.length);
  if (!source.includes('ragSetting !== "true" && !selfHostedRag')) {
    throw new Error("Self-hosted chat must enable private RAG by default");
  }
  const privateIndexCalls = source.match(/search_new_star_private_index/g) || [];
  if (privateIndexCalls.length !== 2) {
    throw new Error("Expected private full-text index to serve both direct search and chat retrieval");
  }

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
  if (!source.includes("if (selfHostedRag) return \"\";")) {
    throw new Error("Self-hosted RAG must not fall back to a separately hosted search service");
  }
  if (!source.includes('code: "NEW_STAR_PRIVATE_SEARCH_NOT_READY"')) {
    throw new Error("Self-hosted search must fail closed when its own index is unavailable");
  }
  const videoStatusRoute = source.slice(source.indexOf('if (body?.action === "video-status")'));
  if (!videoStatusRoute.includes("NEW_STAR_VIDEO_WORKER_NOT_DEPLOYED")) {
    throw new Error("Self-hosted video-status route could still call an external provider");
  }
});
