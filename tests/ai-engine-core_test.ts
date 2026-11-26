import { buildGeminiVideoRequest } from "../supabase/functions/ai-gateway/video-provider.ts";
import { normalizeNewStarSearchResults, normalizePrivateIndexRows, normalizeEmbeddingVector, toPgVectorLiteral, formatRetrievedContext } from "../supabase/functions/ai-gateway/search-provider.ts";
import { detectReplyScript } from "../supabase/functions/ai-gateway/language-policy.ts";
import { readBoundedJson } from "../supabase/functions/ai-gateway/request-body.ts";
import { splitIntoChunks } from "../scripts/new-star-search-ingest.ts";
import { buildChatProviderPlan } from "../supabase/functions/ai-gateway/provider-routing.ts";
import { privateSpeechEndpoint } from "../supabase/functions/ai-gateway/private-speech.ts";
import { privateImageEndpoint } from "../supabase/functions/ai-gateway/private-image.ts";


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

Deno.test("self-hosted gateway reports a New Star-specific configuration error instead of generic provider failure", async () => {
  const source = await Deno.readTextFile(new URL("../supabase/functions/ai-gateway/index.ts", import.meta.url));
  const marker = "if (providers.length === 0)";
  const start = source.indexOf(marker);
  const end = source.indexOf("const baseSystemPrompt", start);
  if (start < 0 || end < 0) throw new Error("Gateway provider-empty handling block is missing");
  const block = source.slice(start, end);
  if (!block.includes("if (selfHostedOnly)") ||
      !block.includes("NEW_STAR_PRIVATE_MODEL_NOT_CONFIGURED") ||
      !block.includes("External providers are disabled in self-hosted mode.")) {
    throw new Error("Self-hosted mode must explain that its private model is missing and external fallback is disabled");
  }
  if (!block.includes("AI_PROVIDERS_NOT_CONFIGURED")) {
    throw new Error("Normal provider mode must retain its existing configuration error");
  }
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
  const oversizedToken = "x".repeat(73);
  const bounded = splitIntoChunks(oversizedToken, 16, 0);
  if (bounded.some((chunk) => chunk.length > 16)) {
    throw new Error("A single unbroken token exceeded the configured maximum chunk length");
  }
  if (bounded.join("").replace(/\s/g, "") !== oversizedToken) {
    throw new Error("Splitting an oversized token lost or reordered characters");
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

Deno.test("private model smoke checks validate readiness and safe API key formats", async () => {
  const chat = await Deno.readTextFile(new URL("../ai-engine/self-hosted/verify.sh", import.meta.url));
  const embeddings = await Deno.readTextFile(new URL("../ai-engine/self-hosted/verify-embeddings.sh", import.meta.url));
  if (!chat.includes("NEW_STAR_PRIVATE_MODEL_READY") || !chat.includes("URL-safe random characters")) {
    throw new Error("Chat smoke test must validate the readiness marker and reject unsafe API key characters");
  }
  if (!embeddings.includes("URL-safe random characters") || !embeddings.includes("isfinite(value)")) {
    throw new Error("Embedding smoke test must validate safe API keys and finite numeric vector values");
  }
  if (!embeddings.includes('!= replace-*') || !embeddings.includes("NVIDIA Container Toolkit") ||
      !embeddings.includes("nvidia-smi") || !embeddings.includes("docker compose version")) {
    throw new Error("Embedding smoke test must reject the example secret and verify the NVIDIA GPU runtime");
  }
  if (!embeddings.includes("/v1/embeddings") || !chat.includes("/v1/chat/completions")) {
    throw new Error("Private model smoke tests must call the documented authenticated API endpoints");
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

Deno.test("private index returns an empty success response when the index has no matching documents", async () => {
  const source = await Deno.readTextFile(new URL("../supabase/functions/ai-gateway/index.ts", import.meta.url));
  const start = source.indexOf("async function searchNewStarIndex(");
  const end = source.indexOf("\nasync function searchWithGeminiGrounding", start);
  if (start < 0 || end < 0) throw new Error("Private search function boundaries are missing");
  const implementation = source.slice(start, end);
  if (!implementation.includes("if (!error && Array.isArray(data))")) {
    throw new Error("A healthy empty private index must be treated as a successful search response");
  }
  if (!implementation.includes('provider: "new-star-private-index"') ||
      !implementation.includes("results, count: results.length")) {
    throw new Error("Private search must return an empty result list and count zero when there are no matches");
  }
  if (implementation.includes("data.length) {")) {
    throw new Error("An empty successful RPC response must not be mislabeled as a missing search worker");
  }
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
    "NEW_STAR_PRIVATE_VISION_UNAVAILABLE",
    "NEW_STAR_PRIVATE_IMAGE_UNAVAILABLE",
    "NEW_STAR_PRIVATE_SPEECH_UNAVAILABLE",
    "NEW_STAR_PRIVATE_TRANSCRIPTION_UNAVAILABLE",
    "NEW_STAR_PRIVATE_VIDEO_UNAVAILABLE",
    "NEW_STAR_PRIVATE_VIDEO_STATUS_UNAVAILABLE",
    "NEW_STAR_PRIVATE_SEARCH_UNAVAILABLE"
  ];
  const missingGuards = requiredSelfHostedGuards.filter((guard) => !source.includes(guard));
  if (missingGuards.length) throw new Error("Missing self-hosted safety guards: " + missingGuards.join(", "));
  if (!source.includes("if (selfHostedRag) return \"\";")) {
    throw new Error("Self-hosted RAG must not fall back to a separately hosted search service");
  }
  if (!source.includes('code: "NEW_STAR_PRIVATE_SEARCH_NOT_READY"')) {
    throw new Error("Self-hosted search must fail closed when its own index is unavailable");
  }
  const statusRouteStart = source.indexOf('if (body?.action === "status")');
  const externalProbeStart = source.indexOf("const newStarModel = await probeNewStarHostedModel()", statusRouteStart);
  if (statusRouteStart < 0 || externalProbeStart < 0) {
    throw new Error("Provider status route boundaries could not be verified");
  }
  const selfHostedStatusBranch = source.slice(statusRouteStart, externalProbeStart);
  if (!selfHostedStatusBranch.includes('mode: "self_hosted"') ||
      !selfHostedStatusBranch.includes("No external model providers are contacted.") ||
      !selfHostedStatusBranch.includes("openai: false, gemini: false, openrouter: false")) {
    throw new Error("Self-hosted status must report only New Star and avoid external provider probes");
  }
  const videoStatusRoute = source.slice(source.indexOf('if (body?.action === "video-status")'));
  if (!videoStatusRoute.includes("NEW_STAR_PRIVATE_VIDEO_STATUS_UNAVAILABLE")) {
    throw new Error("Self-hosted video-status route could still call an external provider");
  }
});

Deno.test("Creator Studio clearly distinguishes private-only mode from external provider mode", async () => {
  const source = await Deno.readTextFile(new URL("../new-star-ai.js", import.meta.url));
  const marker = 'if(result.data.mode==="self_hosted"){';
  const start = source.indexOf(marker);
  const end = source.indexOf('const providerState=', start);
  if (start < 0 || end < 0) throw new Error("Creator Studio self-hosted status branch is missing");
  const branch = source.slice(start, end);
  for (const required of [
    "New Star private model:",
    "External AI providers: disabled",
    "no Gemini, OpenAI or OpenRouter inference fallback",
    "private worker not deployed",
    "return;"
  ]) {
    if (!branch.includes(required)) throw new Error("Self-hosted status UI is missing: " + required);
  }
  if (branch.includes("providerState(")) {
    throw new Error("Self-hosted status must not display or evaluate external provider health");
  }
});

Deno.test("every external media and search route fails closed before provider access in self-hosted mode", async () => {
  const source = await Deno.readTextFile(new URL("../supabase/functions/ai-gateway/index.ts", import.meta.url));
  const routes = [
    { action: "vision", guard: "NEW_STAR_PRIVATE_VISION_UNAVAILABLE" },
    { action: "speak", guard: "NEW_STAR_PRIVATE_SPEECH_UNAVAILABLE" },
    { action: "transcribe", guard: "NEW_STAR_PRIVATE_TRANSCRIPTION_UNAVAILABLE" },
    { action: "video", guard: "NEW_STAR_PRIVATE_VIDEO_UNAVAILABLE" },
    { action: "video-status", guard: "NEW_STAR_PRIVATE_VIDEO_STATUS_UNAVAILABLE" },
    { action: "image", guard: "NEW_STAR_PRIVATE_IMAGE_UNAVAILABLE" }
  ];
  for (const route of routes) {
    const marker = 'if (body?.action === "' + route.action + '")';
    const start = source.indexOf(marker);
    if (start < 0) throw new Error("Missing " + route.action + " route");
    const next = source.indexOf('if (body?.action === "', start + marker.length);
    const block = source.slice(start, next < 0 ? source.length : next);
    const guard = block.indexOf(route.guard);
    if (guard < 0) throw new Error(route.action + " route is missing its self-hosted fail-closed guard");
    const firstProviderAccess = [
      block.indexOf("secretCandidates("),
      block.indexOf("fetchWithTimeout("),
      block.indexOf("generateGemini"),
      block.indexOf("generateOpenAI"),
      block.indexOf("getGeminiVideoInteraction(")
    ].filter((index) => index >= 0).sort((a, b) => a - b)[0];
    if (firstProviderAccess !== undefined && guard > firstProviderAccess) {
      throw new Error(route.action + " route accesses an external provider before its self-hosted guard");
    }
  }

  const searchStart = source.indexOf('if (body?.action === "search")');
  if (searchStart < 0) throw new Error("Missing search route");
  const searchEnd = source.indexOf('if (body?.action === "vision")', searchStart);
  const searchBlock = source.slice(searchStart, searchEnd < 0 ? source.length : searchEnd);
  const searchGuard = searchBlock.indexOf("NEW_STAR_PRIVATE_SEARCH_UNAVAILABLE");
  const externalSearch = searchBlock.indexOf("searchWithGeminiGrounding(");
  if (searchGuard < 0 || externalSearch < 0 || searchGuard > externalSearch) {
    throw new Error("Search must fail closed before invoking external grounding in self-hosted mode");
  }
});

Deno.test("Creator Studio sends the detected transcription language with voice chat", async () => {
  const source = await Deno.readTextFile(new URL("../new-star-ai.js", import.meta.url));
  const expected = 'function requestLanguage(){return lastInputWasVoice?(lastTranscriptionLanguage||automaticLanguageOptions().language):(selectedAppLanguage()||automaticLanguageOptions().language);}';
  if (!source.includes(expected)) {
    throw new Error("Voice chat must pass the detected transcription language instead of discarding it");
  }
  if (!source.includes('lastTranscriptionLanguage=result.data.language&&result.data.language!=="und"?result.data.language:""')) {
    throw new Error("Voice transcription must retain the language returned by the speech recognizer");
  }
});

Deno.test("Creator Studio UI does not advertise removed external video dependencies or unreachable legacy fallbacks", async () => {
  const app = await Deno.readTextFile(new URL("../new-star-ai.js", import.meta.url));
  const html = await Deno.readTextFile(new URL("../new-star-ai.html", import.meta.url));
  if (app.includes("Live Research needs a connected search provider")) {
    throw new Error("Obsolete unreachable research error branch must be removed");
  }
  if (app.includes("Video generation needs a video model server and job queue")) {
    throw new Error("Obsolete unreachable video error branch must be removed");
  }
  if (html.includes("Video generation uses Google Cloud Vertex AI")) {
    throw new Error("Creator Studio must not advertise external Vertex AI as its video engine");
  }
  if (!html.includes("private image, speech, search and video workers must be deployed")) {
    throw new Error("Creator Studio must state the private worker deployment requirement");
  }
  for (const staleClaim of [
    "Gemini Google Search grounding as a live-web fallback",
    "Video requests use Google Cloud Vertex AI",
    "Video requires Google Cloud Vertex AI access"
  ]) {
    if (html.includes(staleClaim)) throw new Error("Stale external-provider capability claim remains: " + staleClaim);
  }
  if (!html.includes("Private-only mode requires New Star's own speech workers")) {
    throw new Error("Voice capability card must state private speech worker requirement");
  }
  if (!html.includes("In private-only mode, replies use only the configured New Star model")) {
    throw new Error("Chat capability card must state private-only inference routing");
  }
  if (!html.includes("never falls back to an external search provider")) {
    throw new Error("Search capability card must describe private-mode provider isolation");
  }
});

Deno.test("Creator Studio discards stale speech language after transcript edits or leaving chat", async () => {
  const source = await Deno.readTextFile(new URL("../new-star-ai.js", import.meta.url));
  if (!source.includes('prompt.addEventListener("input",()=>{lastInputWasVoice=false;lastTranscriptionLanguage="";});')) {
    throw new Error("Manually editing a transcript must clear the old speech-recognition language");
  }
  if (!source.includes('function setMode(next){if(next!=="chat"){lastInputWasVoice=false;lastTranscriptionLanguage="";}')) {
    throw new Error("Switching away from chat must clear stale voice-language state");
  }
});

Deno.test("private speech adapter requires a protected HTTPS endpoint", () => {
  if (privateSpeechEndpoint("https://speech.internal.example", "/v1/audio/speech") !== "https://speech.internal.example/v1/audio/speech") {
    throw new Error("Valid private HTTPS speech endpoint was rejected");
  }
  for (const unsafe of [
    "http://speech.internal.example",
    "https://user:pass@speech.internal.example",
    "https://speech.internal.example/?token=secret",
    "not-a-url",
  ]) {
    if (privateSpeechEndpoint(unsafe, "/v1/audio/speech") !== null) {
      throw new Error("Unsafe private speech endpoint was accepted: " + unsafe);
    }
  }
});

Deno.test("private speech worker is wired only in self-hosted voice paths", async () => {
  const gateway = await Deno.readTextFile(new URL("../supabase/functions/ai-gateway/index.ts", import.meta.url));
  const worker = await Deno.readTextFile(new URL("../ai-engine/self-hosted/speech-worker/main.py", import.meta.url));
  const frontend = await Deno.readTextFile(new URL("../new-star-ai.js", import.meta.url));
  if (!gateway.includes("privateSpeechSynthesis(") || !gateway.includes("privateSpeechTranscription(")) {
    throw new Error("Private-mode speech and transcription must call the New Star worker adapter");
  }
  if (!worker.includes("/v1/audio/transcriptions") || !worker.includes("/v1/audio/speech") || !worker.includes("require_auth")) {
    throw new Error("Private speech worker must expose authenticated STT and TTS endpoints");
  }
  if (!frontend.includes('body:{action:"speak",text,language}')) {
    throw new Error("Creator Studio must pass its active language into private speech synthesis");
  }
  if (!frontend.includes("const sendText=text;const responseLanguage=requestLanguage();")) {
    throw new Error("Creator Studio must capture the detected voice language before clearing transient voice state");
  }
  if (!frontend.includes('speakText(result.data.answer||"",voiceButton,responseLanguage)')) {
    throw new Error("Spoken AI replies must use the language captured for the user's request");
  }
});


Deno.test("private image adapter requires an authenticated HTTPS endpoint", () => {
  if (privateImageEndpoint("https://image.internal.example") !== "https://image.internal.example/v1/images/generations") throw new Error("Valid private HTTPS image endpoint was rejected");
  for (const unsafe of ["http://image.internal.example", "https://user:pass@image.internal.example", "https://image.internal.example/?key=secret", "not-a-url"]) {
    if (privateImageEndpoint(unsafe) !== null) throw new Error("Unsafe private image endpoint was accepted");
  }
});

Deno.test("private image worker is wired only for self-hosted text-to-image", async () => {
  const gateway = await Deno.readTextFile(new URL("../supabase/functions/ai-gateway/index.ts", import.meta.url));
  const worker = await Deno.readTextFile(new URL("../ai-engine/self-hosted/image-worker/main.py", import.meta.url));
  const compose = await Deno.readTextFile(new URL("../ai-engine/self-hosted/compose.yaml", import.meta.url));
  if (!gateway.includes("privateImageGeneration(") || !gateway.includes("NEW_STAR_IMAGE_BASE_URL") || !gateway.includes("NEW_STAR_PRIVATE_IMAGE_UNAVAILABLE")) throw new Error("Self-hosted image action must call the private worker and fail closed");
  if (!worker.includes("/v1/images/generations") || !worker.includes("require_auth") || !worker.includes("torch.cuda.is_available")) throw new Error("Image worker must expose authenticated GPU-backed generation");
  if (!compose.includes('profiles: ["image"]') || !compose.includes('127.0.0.1:8091:8091')) throw new Error("Image worker must be optional and bound to loopback");
});


Deno.test("private video adapter accepts only protected HTTPS and supported job settings", async () => {
  const source = await Deno.readTextFile(new URL("../supabase/functions/ai-gateway/private-video.ts", import.meta.url));
  if (!source.includes('endpoint.protocol !== "https:"') || !source.includes('resolution !== "480p"') || !source.includes('duration !== "6s"')) {
    throw new Error("Private video adapter must validate HTTPS and enforce supported generation settings");
  }
  for (const required of ["redirect: \"error\"", "/v1/videos/generations", "/v1/videos/jobs/", "video/mp4", "50 * 1024 * 1024"]) {
    if (!source.includes(required)) throw new Error("Private video adapter is missing " + required);
  }
});

Deno.test("private video worker persists jobs and rejects unauthenticated or unsupported requests", async () => {
  const worker = await Deno.readTextFile(new URL("../ai-engine/self-hosted/video-worker/main.py", import.meta.url));
  const compose = await Deno.readTextFile(new URL("../ai-engine/self-hosted/compose.yaml", import.meta.url));
  for (const required of ["require_auth", "CogVideoXPipeline", "/v1/videos/generations", "/v1/videos/jobs/{job_id}", "restore_jobs", "VIDEO_WORKER_RESTARTED", "torch.cuda.is_available", "FRAME_COUNT = 49"]) {
    if (!worker.includes(required)) throw new Error("Private video worker is missing " + required);
  }
  if (!compose.includes('profiles: ["video"]') || !compose.includes('127.0.0.1:8092:8092') || !compose.includes("new-star-video-jobs:")) {
    throw new Error("Private video worker must use an optional profile, loopback binding, and persistent job storage");
  }
});

Deno.test("Creator Studio exposes only the private worker's supported video settings", async () => {
  const html = await Deno.readTextFile(new URL("../new-star-ai.html", import.meta.url));
  if (!html.includes('value="480p"') || !html.includes('value="6s"') || html.includes('value="15s"')) {
    throw new Error("Video controls must not advertise unsupported private worker resolutions or durations");
  }
});


Deno.test("private search adapter enforces HTTPS and authenticated private endpoints", async () => {
  const source = await Deno.readTextFile(new URL("../supabase/functions/ai-gateway/private-search.ts", import.meta.url));
  for (const required of ['u.protocol!=="https:"', 'Authorization:"Bearer "+key', 'redirect:"error"', '"/v1/search"', '"/v1/crawl"']) {
    if (!source.includes(required)) throw new Error("Private search adapter missing " + required);
  }
});

Deno.test("private crawler rejects non-public targets and limits indexed content", async () => {
  const worker = await Deno.readTextFile(new URL("../ai-engine/self-hosted/search-worker/main.py", import.meta.url));
  for (const required of ["ipaddress.ip_address", "address.is_global", "allow_redirects=False", "MAX_BYTES = 2 * 1024 * 1024", "100000", "pages_fts", "/v1/crawl", "/v1/search", "len(visited) < 5", "parsed.hostname != root_host", "pages_indexed", "peer_ip.is_global", "sock.getpeername()", "port not in {80, 443}", "parsed.scheme != root_scheme", "current_port != root_port", "link_port == root_port"]) {
    if (!worker.includes(required)) throw new Error("Private crawler missing security/index rule " + required);
  }
  if (worker.includes("allow_redirects=True")) throw new Error("Private crawler must not follow redirects");
});

Deno.test("private vision adapter and worker are protected and wired only in self-hosted mode", async () => {
  const adapter = await Deno.readTextFile(new URL("../supabase/functions/ai-gateway/private-vision.ts", import.meta.url));
  const worker = await Deno.readTextFile(new URL("../ai-engine/self-hosted/vision-worker/main.py", import.meta.url));
  const gateway = await Deno.readTextFile(new URL("../supabase/functions/ai-gateway/index.ts", import.meta.url));
  const compose = await Deno.readTextFile(new URL("../ai-engine/self-hosted/compose.yaml", import.meta.url));
  if (!adapter.includes('u.protocol!=="https:"') || !adapter.includes('Authorization:"Bearer "+key')) throw new Error("Private vision adapter must enforce HTTPS and bearer authentication");
  for (const required of ["AutoModelForVision2Seq", "torch.cuda.is_available", "/v1/vision/analyze", "Image.open", "6 * 1024 * 1024"]) {
    if (!worker.includes(required)) throw new Error("Private vision worker missing " + required);
  }
  if (!gateway.includes("privateVision(") || !gateway.includes("NEW_STAR_VISION_BASE_URL") || !gateway.includes("NEW_STAR_PRIVATE_VISION_UNAVAILABLE")) throw new Error("Self-hosted vision action is not connected to its private worker");
  if (!compose.includes('profiles: ["vision"]') || !compose.includes('127.0.0.1:8094:8094')) throw new Error("Private vision worker must be optional and loopback-only");
});

Deno.test("self-hosted search uses private crawl index and never calls Gemini grounding", async () => {
  const gateway = await Deno.readTextFile(new URL("../supabase/functions/ai-gateway/index.ts", import.meta.url));
  const searchStart = gateway.indexOf('if (body?.action === "search")');
  const visionStart = gateway.indexOf('if (body?.action === "crawl")', searchStart);
  const searchBlock = gateway.slice(searchStart, visionStart);
  if (!searchBlock.includes("privateSearch(") || !searchBlock.includes("NEW_STAR_SEARCH_BASE_URL") || !searchBlock.includes("NEW_STAR_PRIVATE_SEARCH_UNAVAILABLE")) {
    throw new Error("Self-hosted search must query the private search index");
  }
  if (searchBlock.indexOf('NEW_STAR_AI_MODE") === "self_hosted"') > searchBlock.indexOf("searchWithGeminiGrounding")) {
    throw new Error("Self-hosted search must not reach Gemini grounding");
  }
  if (!gateway.includes('body?.action === "crawl"') || !gateway.includes("privateCrawl(")) throw new Error("Authenticated crawl action must be connected");
});


Deno.test("Creator Studio Search mode can index a public URL before querying its private crawl index", async () => {
  const frontend = await Deno.readTextFile(new URL("../new-star-ai.js", import.meta.url));
  if (!frontend.includes('const crawlUrl=/^https?:\\/\\/[^\\s]+$/i.test(sendText.trim())')) throw new Error("Search mode must recognize a pasted URL for private indexing");
  if (!frontend.includes('crawlUrl?{action:"crawl",url:sendText.trim()}:{action:"search"')) throw new Error("Search mode must call the private crawler for URLs and the private index for queries");
  if (!frontend.includes("Paste a public page URL to index it first.")) throw new Error("Empty private search results must explain how to add a source");
});


Deno.test("private New Star fine-tuning pipeline validates datasets and uses local CUDA QLoRA", async () => {
  const prepare = await Deno.readTextFile(new URL("../ai-engine/training/prepare_dataset.py", import.meta.url));
  const train = await Deno.readTextFile(new URL("../ai-engine/training/train_lora.py", import.meta.url));
  for (const required of ["SECRET_PATTERNS", "possible credential/secret detected", "duplicate_conversations_removed", "train.jsonl", "validation.jsonl", "At least 10 unique validated conversations are required"]) {
    if (!prepare.includes(required)) throw new Error("Training dataset validator is missing " + required);
  }
  for (const required of ["Qwen/Qwen2.5-3B-Instruct", "torch.cuda.is_available()", "load_in_4bit=True", "LoraConfig", "SFTTrainer", "training-report.json", "not pretraining a New Star foundation model"]) {
    if (!train.includes(required)) throw new Error("Private QLoRA trainer is missing " + required);
  }
});


Deno.test("private crawler requires the developer API research permission", async () => {
  const gateway = await Deno.readTextFile(new URL("../supabase/functions/ai-gateway/index.ts", import.meta.url));
  const scopeStart = gateway.indexOf("const requiredScope =");
  const scopeEnd = gateway.indexOf("if (!apiKeyScopes.includes(requiredScope))", scopeStart);
  const scopeBlock = gateway.slice(scopeStart, scopeEnd);
  if (!scopeBlock.includes('action === "search" || action === "crawl" ? "research"')) {
    throw new Error("Both private search and crawl actions must require the research API scope");
  }
});


Deno.test("New Star private image UI exposes only the worker's supported 1K size", async () => {
  const frontend = await Deno.readTextFile(new URL("../new-star-ai.js", import.meta.url));
  const privateMode = frontend.indexOf('if(result.data.mode==="self_hosted")');
  const externalMode = frontend.indexOf('setPrivateImageSizeMode(false)');
  const helper = frontend.indexOf("function setPrivateImageSizeMode(privateOnly)");
  if (helper < 0 || privateMode < 0 || externalMode < 0) {
    throw new Error("Image size controls must react to the active AI mode");
  }
  if (!frontend.slice(privateMode, externalMode).includes("setPrivateImageSizeMode(true)")) {
    throw new Error("Self-hosted mode must restrict image output to 1K");
  }
  if (!frontend.slice(helper, privateMode).includes('option.value==="1K"')) {
    throw new Error("Private image-size helper must disable unsupported 2K and 4K outputs");
  }
});


Deno.test("private speech requests refuse redirects before forwarding worker credentials", async () => {
  const adapter = await Deno.readTextFile(new URL("../supabase/functions/ai-gateway/private-speech.ts", import.meta.url));
  const redirectGuards = adapter.match(/redirect: "error"/g) || [];
  if (redirectGuards.length !== 2) {
    throw new Error("Both private speech synthesis and transcription must reject HTTP redirects");
  }
});


Deno.test("private chat model requests refuse HTTP redirects", async () => {
  const gateway = await Deno.readTextFile(new URL("../supabase/functions/ai-gateway/index.ts", import.meta.url));
  const start = gateway.indexOf("async function callNewStarHostedModel");
  const end = gateway.indexOf("\nasync function probeApiProvider", start);
  const request = gateway.slice(start, end);
  if (start < 0 || end < 0 || !request.includes('redirect: "error"')) {
    throw new Error("The authenticated private model request must reject redirects");
  }
});


Deno.test("AI engine control room exposes private vision capability status", async () => {
  const dashboard = await Deno.readTextFile(new URL("../ai-engine-dashboard.html", import.meta.url));
  if (!dashboard.includes('id="visionModule"') ||
      !dashboard.includes('id="visionStatus"') ||
      !dashboard.includes('id="visionDescription"') ||
      !dashboard.includes('capabilityLabel(caps.imageUnderstanding)') ||
      !dashboard.includes('capabilitySummary(caps.imageUnderstanding)')) {
    throw new Error("Control Room must display the gateway's private image-understanding capability and reason");
  }
});
