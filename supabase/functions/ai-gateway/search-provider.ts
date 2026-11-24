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
