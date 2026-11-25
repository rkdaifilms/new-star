#!/usr/bin/env -S deno run --allow-read --allow-env
type SearchDocument = {
  url: string;
  title: string;
  source: string;
  content: string;
  language?: string;
  published_at?: string | null;
};

function requiredEnv(name: string): string {
  const value = Deno.env.get(name)?.trim() || "";
  if (!value) throw new Error(name + " is required");
  return value;
}

function validateDocument(value: unknown, line: number): SearchDocument {
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
  if (publishedAt && (!Number.isFinite(Date.parse(publishedAt)))) {
    throw new Error("Line " + line + ": published_at must be a valid ISO date or null");
  }
  return { url: url.toString(), title, source, content, language, published_at: publishedAt };
}

async function main() {
  const inputPath = Deno.args[0];
  if (!inputPath) throw new Error("Usage: deno run --allow-read --allow-env scripts/new-star-search-ingest.ts documents.jsonl");
  const base = requiredEnv("SUPABASE_URL").replace(/\/$/, "");
  const key = requiredEnv("SUPABASE_SERVICE_ROLE_KEY");
  if (!/^https:\/\//.test(base)) throw new Error("SUPABASE_URL must use HTTPS");
  if (key.length < 32) throw new Error("SUPABASE_SERVICE_ROLE_KEY is not valid");
  const raw = await Deno.readTextFile(inputPath);
  const rows: SearchDocument[] = [];
  const seen = new Set<string>();
  for (const [index, line] of raw.split(/\r?\n/).entries()) {
    if (!line.trim()) continue;
    const document = validateDocument(JSON.parse(line), index + 1);
    if (seen.has(document.url)) throw new Error("Duplicate URL on input line " + (index + 1));
    seen.add(document.url);
    rows.push(document);
    if (rows.length > 500) throw new Error("A single ingestion batch is limited to 500 documents");
  }
  if (!rows.length) throw new Error("Input contains no documents");
  const response = await fetch(base + "/rest/v1/new_star_search_documents?on_conflict=url", {
    method: "POST",
    headers: {
      apikey: key,
      Authorization: "Bearer " + key,
      "Content-Type": "application/json",
      Prefer: "resolution=merge-duplicates,return=minimal"
    },
    body: JSON.stringify(rows)
  });
  if (!response.ok) {
    const message = (await response.text()).slice(0, 1200);
    throw new Error("Supabase ingestion failed with HTTP " + response.status + ": " + message);
  }
  console.log("Indexed " + rows.length + " New Star search documents.");
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : String(error));
  Deno.exit(1);
});
