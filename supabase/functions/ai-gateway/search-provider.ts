export type NewStarSearchResult = {
  title: string;
  url: string;
  snippet: string;
  source: string;
  publishedAt: string | null;
};

export function normalizeNewStarSearchResults(input: unknown, limit = 5): NewStarSearchResult[] {
  if (!Array.isArray(input) || !Number.isInteger(limit) || limit < 1 || limit > 10) return [];
  return input.slice(0, limit).flatMap((item: any) => {
    if (!item || typeof item.title !== "string" || !item.title.trim() || typeof item.url !== "string") return [];
    try {
      const url = new URL(item.url);
      if (url.protocol !== "https:" || url.username || url.password) return [];
      return [{
        title: item.title.trim().slice(0, 240),
        url: url.toString(),
        snippet: typeof item.snippet === "string" ? item.snippet.slice(0, 1200) : "",
        source: url.hostname,
        publishedAt: typeof item.publishedAt === "string" ? item.publishedAt.slice(0, 80) : null
      }];
    } catch {
      return [];
    }
  });
}

export function formatRetrievedContext(input: unknown, limit = 5): string {
  const results = normalizeNewStarSearchResults(input, limit);
  return results.map((item, index) =>
    `[Source ${index + 1}] ${item.title}\nURL: ${item.url}\nExcerpt: ${item.snippet}`
  ).join("\n\n").slice(0, 6000);
}

export function normalizePrivateIndexRows(input: unknown, limit = 5): NewStarSearchResult[] {
  if (!Array.isArray(input)) return [];
  return normalizeNewStarSearchResults(input.slice(0, limit).map((item: any) => ({
    title: item?.title,
    url: item?.url,
    snippet: item?.snippet,
    publishedAt: item?.published_at
  })), limit);
}

export function normalizeEmbeddingVector(input: unknown): number[] | null {
  if (!Array.isArray(input) || input.length !== 384) return null;
  if (input.some((value) => typeof value !== "number" || !Number.isFinite(value))) return null;
  return input as number[];
}

export function toPgVectorLiteral(input: unknown): string | null {
  const vector = normalizeEmbeddingVector(input);
  return vector ? "[" + vector.join(",") + "]" : null;
}
