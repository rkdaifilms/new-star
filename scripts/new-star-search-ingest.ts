#!/usr/bin/env -S deno run --allow-read --allow-env=SUPABASE_URL,SUPABASE_SERVICE_ROLE_KEY,NEW_STAR_EMBEDDING_BASE_URL,NEW_STAR_EMBEDDING_API_KEY
import { fetchWithTimeout } from "../supabase/functions/ai-gateway/fetch-with-timeout.ts";
import { readWorkerBytes } from "../supabase/functions/ai-gateway/worker-response.ts";

type InputDocument = {
  url: string;
  title: string;
  source: string;
  content: string;
  language: string;
  published_at: string | null;
};

type SearchChunk = InputDocument & {
  chunk_index: number;
  embedding: number[];
};

function requiredEnv(name: string): string {
  const value = Deno.env.get(name)?.trim() || "";
  if (!value) throw new Error(name + " is required");
  return value;
}

function validateDocument(value: unknown, line: number): InputDocument {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("Line " + line + ": expected a JSON object");
  }
  const item = value as Record<string, unknown>;
  const urlText = typeof item.url === "string" ? item.url.trim() : "";
  let url: URL;
  try { url = new URL(urlText); } catch { throw new Error("Line " + line + ": invalid URL"); }
  if (url.protocol !== "https:" || url.username || url.password) {
    throw new Error("Line " + line + ": only credential-free HTTPS URLs are accepted");
  }
  const title = typeof item.title === "string" ? item.title.trim() : "";
  const source = typeof item.source === "string" ? item.source.trim() : url.hostname;
  const content = typeof item.content === "string" ? item.content.trim() : "";
  const language = typeof item.language === "string" ? item.language.trim().toLowerCase() : "auto";
  const publishedAt = item.published_at === null || item.published_at === undefined
    ? null
    : typeof item.published_at === "string" ? item.published_at : "";
  if (!title || title.length > 300) throw new Error("Line " + line + ": title must contain 1 to 300 characters");
  if (!source || source.length > 200) throw new Error("Line " + line + ": source must contain 1 to 200 characters");
  if (!content || content.length > 30000) throw new Error("Line " + line + ": content must contain 1 to 30000 characters");
  if (language.length < 2 || language.length > 20) throw new Error("Line " + line + ": language must contain 2 to 20 characters");
  if (publishedAt && !Number.isFinite(Date.parse(publishedAt))) {
    throw new Error("Line " + line + ": published_at must be a valid ISO date or null");
  }
  return { url: url.toString(), title, source, content, language, published_at: publishedAt };
}

export function splitIntoChunks(content: string, maxLength = 1200, overlap = 160): string[] {
  if (!Number.isInteger(maxLength) || maxLength < 1 || !Number.isInteger(overlap) || overlap < 0 || overlap >= maxLength) {
    throw new Error("Chunk size and overlap configuration is invalid");
  }
  const words = content.replace(/\s+/g, " ").trim().split(" ").filter(Boolean)
    .flatMap((word) => {
      if (word.length <= maxLength) return [word];
      const fragments: string[] = [];
      for (let offset = 0; offset < word.length; offset += maxLength) {
        fragments.push(word.slice(offset, offset + maxLength));
      }
      return fragments;
    });
  if (!words.length) return [];
  const chunks: string[] = [];
  let start = 0;
  while (start < words.length) {
    let end = start;
    let length = 0;
    while (end < words.length) {
      const nextLength = length + (end === start ? 0 : 1) + words[end].length;
      if (end > start && nextLength > maxLength) break;
      if (end === start && words[end].length > maxLength) {
        length = words[end].length;
        end++;
        break;
      }
      length = nextLength;
      end++;
    }
    if (end === start) end++;
    chunks.push(words.slice(start, end).join(" "));
    if (end >= words.length) break;
    let overlapStart = end;
    let overlapChars = 0;
    while (overlapStart > start) {
      const word = words[overlapStart - 1];
      const addition = word.length + (overlapChars ? 1 : 0);
      if (overlapChars + addition > overlap) break;
      overlapChars += addition;
      overlapStart--;
    }
    start = overlapStart > start ? overlapStart : end;
  }
  return chunks;
}

async function readJsonResponse(response: Response, maxBytes: number): Promise<unknown> {
  const result = await readWorkerBytes(response, maxBytes);
  if (!result.ok) {
    throw new Error(result.error === "response_too_large"
      ? "Upstream response exceeded the configured size limit."
      : "Upstream response could not be read.");
  }
  try {
    return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(result.bytes));
  } catch {
    throw new Error("Upstream returned invalid JSON.");
  }
}

async function createEmbedding(base: string, key: string, text: string): Promise<number[]> {
  const response = await fetchWithTimeout(base + "/embeddings", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: "Bearer " + key },
    body: JSON.stringify({ model: "new-star-embedding", input: text }),
    redirect: "error"
  }, 30000);
  const payload = await readJsonResponse(response, 1024 * 1024) as Record<string, unknown>;
  if (!response.ok) throw new Error("Embedding service returned HTTP " + response.status);
  const data = payload.data;
  const vector = Array.isArray(data) && data[0] && typeof data[0] === "object"
    ? (data[0] as Record<string, unknown>).embedding
    : null;
  if (!Array.isArray(vector) || vector.length !== 384 || vector.some((v: unknown) => typeof v !== "number" || !Number.isFinite(v))) {
    throw new Error("Embedding service must return exactly 384 finite numbers; verify the configured embedding model.");
  }
  return vector as number[];
}

async function main() {
  const inputPath = Deno.args[0];
  if (!inputPath) throw new Error("Usage: deno run --allow-read --allow-env=SUPABASE_URL,SUPABASE_SERVICE_ROLE_KEY,NEW_STAR_EMBEDDING_BASE_URL,NEW_STAR_EMBEDDING_API_KEY scripts/new-star-search-ingest.ts documents.jsonl");
  const supabaseBase = requiredEnv("SUPABASE_URL").replace(/\/$/, "");
  const serviceKey = requiredEnv("SUPABASE_SERVICE_ROLE_KEY");
  const embeddingBase = requiredEnv("NEW_STAR_EMBEDDING_BASE_URL").replace(/\/$/, "");
  const embeddingKey = requiredEnv("NEW_STAR_EMBEDDING_API_KEY");
  if (!/^https:\/\//.test(supabaseBase)) throw new Error("SUPABASE_URL must use HTTPS");
  if (serviceKey.length < 32 || embeddingKey.length < 32) throw new Error("Server keys must be at least 32 characters");
  let embeddingUrl: URL;
  try { embeddingUrl = new URL(embeddingBase); } catch { throw new Error("NEW_STAR_EMBEDDING_BASE_URL must be a valid URL"); }
  const localEmbeddingHost = ["127.0.0.1", "localhost", "::1"].includes(embeddingUrl.hostname);
  if (embeddingUrl.protocol !== "https:" && !(embeddingUrl.protocol === "http:" && localEmbeddingHost)) {
    throw new Error("Embedding service must use HTTPS, except for a local loopback service");
  }
  if (embeddingUrl.username || embeddingUrl.password || embeddingUrl.search || embeddingUrl.hash) {
    throw new Error("Embedding service URL cannot contain credentials, query parameters or fragments");
  }
  const embeddingPath = embeddingUrl.pathname.replace(/\/$/, "");
  const embeddingEndpoint = embeddingPath.endsWith("/v1") ? embeddingBase : embeddingBase + "/v1";
  const raw = await Deno.readTextFile(inputPath);
  const documents: InputDocument[] = [];
  const seen = new Set<string>();
  for (const [index, line] of raw.split(/\r?\n/).entries()) {
    if (!line.trim()) continue;
    const document = validateDocument(JSON.parse(line), index + 1);
    if (seen.has(document.url)) throw new Error("Duplicate URL on input line " + (index + 1));
    seen.add(document.url);
    documents.push(document);
    if (documents.length > 100) throw new Error("A single ingestion batch is limited to 100 source documents");
  }
  if (!documents.length) throw new Error("Input contains no documents");

  const chunks: SearchChunk[] = [];
  for (const document of documents) {
    const pieces = splitIntoChunks(document.content);
    for (let index = 0; index < pieces.length; index++) {
      if (chunks.length >= 200) throw new Error("A single ingestion batch is limited to 200 text chunks");
      const embedding = await createEmbedding(embeddingEndpoint, embeddingKey, pieces[index]);
      chunks.push({
        url: document.url,
        title: document.title,
        source: document.source,
        content: pieces[index],
        language: document.language,
        published_at: document.published_at,
        chunk_index: index,
        embedding
      });
    }
  }

  const response = await fetchWithTimeout(supabaseBase + "/rest/v1/rpc/replace_new_star_search_chunks", {
    method: "POST",
    headers: {
      apikey: serviceKey,
      Authorization: "Bearer " + serviceKey,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ p_chunks: chunks }),
    redirect: "error"
  }, 45000);
  const responsePayload = await readJsonResponse(response, 64 * 1024);
  if (!response.ok) {
    const message = typeof responsePayload === "string"
      ? responsePayload.slice(0, 1200)
      : JSON.stringify(responsePayload).slice(0, 1200);
    throw new Error("Supabase atomic search replacement failed with HTTP " + response.status + ": " + message);
  }
  const replacedCount = Number(responsePayload);
  if (!Number.isInteger(replacedCount) || replacedCount !== chunks.length) {
    throw new Error("Supabase confirmed " + String(replacedCount) + " replaced chunks; expected " + chunks.length);
  }
  console.log("Atomically replaced " + documents.length + " source documents with " + replacedCount + " vector + full-text chunks.");
}

if (import.meta.main) {
  main().catch((error) => {
    console.error(error instanceof Error ? error.message : String(error));
    Deno.exit(1);
  });
}
