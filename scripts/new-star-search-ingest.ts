#!/usr/bin/env -S deno run --allow-read --allow-env=SUPABASE_URL,SUPABASE_SERVICE_ROLE_KEY,NEW_STAR_EMBEDDING_BASE_URL,NEW_STAR_EMBEDDING_API_KEY
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
  const normalized = content.replace(/\s+/g, " ").trim();
  if (!normalized) return [];
  const chunks: string[] = [];
  let start = 0;
  while (start < normalized.length) {
    let end = Math.min(normalized.length, start + maxLength);
    if (end < normalized.length) {
      const boundary = normalized.lastIndexOf(" ", end);
      if (boundary > start + Math.floor(maxLength * 0.6)) end = boundary;
    }
    const chunk = normalized.slice(start, end).trim();
    if (chunk) chunks.push(chunk);
    if (end >= normalized.length) break;
    start = Math.max(start + 1, end - overlap);
  }
  return chunks;
}

async function createEmbedding(base: string, key: string, text: string): Promise<number[]> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 30000);
  try {
    const response = await fetch(base + "/embeddings", {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: "Bearer " + key },
      body: JSON.stringify({ model: "new-star-embedding", input: text }),
      signal: controller.signal
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error("Embedding service returned HTTP " + response.status);
    const vector = payload?.data?.[0]?.embedding;
    if (!Array.isArray(vector) || vector.length !== 384 || vector.some((v: unknown) => typeof v !== "number" || !Number.isFinite(v))) {
      throw new Error("Embedding service must return exactly 384 finite numbers; verify the configured embedding model.");
    }
    return vector as number[];
  } finally {
    clearTimeout(timer);
  }
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

  const response = await fetch(supabaseBase + "/rest/v1/new_star_search_chunks?on_conflict=url,chunk_index", {
    method: "POST",
    headers: {
      apikey: serviceKey,
      Authorization: "Bearer " + serviceKey,
      "Content-Type": "application/json",
      Prefer: "resolution=merge-duplicates,return=minimal"
    },
    body: JSON.stringify(chunks)
  });
  if (!response.ok) {
    const message = (await response.text()).slice(0, 1200);
    throw new Error("Supabase ingestion failed with HTTP " + response.status + ": " + message);
  }
  console.log("Indexed " + documents.length + " source documents into " + chunks.length + " vector + full-text chunks.");
}

if (import.meta.main) {
  main().catch((error) => {
    console.error(error instanceof Error ? error.message : String(error));
    Deno.exit(1);
  });
}
