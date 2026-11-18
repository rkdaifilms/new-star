import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const ALLOWED_ORIGINS = new Set([
  "https://new-star-nepal.netlify.app",
  "https://new-star-delta.vercel.app"
]);

function corsHeaders(origin: string) {
  const allowedOrigin = ALLOWED_ORIGINS.has(origin) ? origin : "https://new-star-nepal.netlify.app";
  return {
    "Access-Control-Allow-Origin": allowedOrigin,
    "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    "Content-Type": "application/json",
    "Vary": "Origin"
  };
}

function json(data: unknown, status: number, headers: Record<string, string>) {
  return new Response(JSON.stringify(data), { status, headers });
}

type ChatMessage = { role: "user" | "assistant"; content: string };

async function fetchWithTimeout(url: string, init: RequestInit, timeoutMs = 16000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...init, signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
}

async function callOpenAI(key: string, messages: ChatMessage[]) {
  const response = await fetchWithTimeout("https://api.openai.com/v1/chat/completions", {
    method: "POST",
    headers: { "Authorization": `Bearer ${key}`, "Content-Type": "application/json" },
    body: JSON.stringify({
      model: "gpt-4o-mini",
      messages: messages.map(m => ({ role: m.role, content: m.content })),
      temperature: 0.4,
      max_tokens: 900
    })
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(`HTTP_${response.status}`);
  const text = data?.choices?.[0]?.message?.content;
  if (typeof text !== "string" || !text.trim()) throw new Error("EMPTY_RESPONSE");
  return text;
}

async function callGemini(key: string, messages: ChatMessage[]) {
  const contents = messages.map(m => ({
    role: m.role === "assistant" ? "model" : "user",
    parts: [{ text: m.content }]
  }));
  const response = await fetchWithTimeout(
    "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
    {
      method: "POST",
      headers: { "x-goog-api-key": key, "Content-Type": "application/json" },
      body: JSON.stringify({
        contents,
        generationConfig: { temperature: 0.4, maxOutputTokens: 900 }
      })
    }
  );
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(`HTTP_${response.status}`);
  const text = data?.candidates?.[0]?.content?.parts?.map((p: any) => p.text || "").join("");
  if (typeof text !== "string" || !text.trim()) throw new Error("EMPTY_RESPONSE");
  return text;
}

async function callOpenRouter(key: string, messages: ChatMessage[]) {
  const response = await fetchWithTimeout("https://openrouter.ai/api/v1/chat/completions", {
    method: "POST",
    headers: {
      "Authorization": `Bearer ${key}`,
      "Content-Type": "application/json",
      "HTTP-Referer": "https://new-star-nepal.netlify.app",
      "X-Title": "New Star Creator Hub"
    },
    body: JSON.stringify({
      model: "openai/gpt-4o-mini",
      messages: messages.map(m => ({ role: m.role, content: m.content })),
      temperature: 0.4,
      max_tokens: 900
    })
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(`HTTP_${response.status}`);
  const text = data?.choices?.[0]?.message?.content;
  if (typeof text !== "string" || !text.trim()) throw new Error("EMPTY_RESPONSE");
  return text;
}

Deno.serve(async (req: Request) => {
  try {
  const origin = req.headers.get("Origin") || "";
  const headers = corsHeaders(origin);
  if (req.method === "OPTIONS") return new Response("ok", { headers });
  if (req.method !== "POST") return json({ ok: false, error: "Method not allowed" }, 405, headers);
  if (!ALLOWED_ORIGINS.has(origin)) return json({ ok: false, error: "Origin not allowed" }, 403, headers);

  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY");
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!supabaseUrl || !anonKey || !serviceKey) {
    return json({ ok: false, error: "Server authentication is not configured" }, 503, headers);
  }

  const authorization = req.headers.get("Authorization") || "";
  if (!authorization.startsWith("Bearer ")) {
    return json({ ok: false, error: "Sign-in required" }, 401, headers);
  }

  const userClient = createClient(supabaseUrl, anonKey, {
    global: { headers: { Authorization: authorization } },
    auth: { persistSession: false, autoRefreshToken: false }
  });
  const { data: userData, error: userError } = await userClient.auth.getUser();
  if (userError || !userData.user) {
    return json({ ok: false, error: "Your session is invalid. Please sign in again." }, 401, headers);
  }

  let body: any;
  try {
    body = await req.json();
  } catch {
    return json({ ok: false, error: "Invalid request body" }, 400, headers);
  }

  if (body?.action === "status") {
    return json({
      ok: true,
      status: true,
      providers: {
        openai: Boolean(Deno.env.get("OPENAI_API_KEY")),
        gemini: Boolean(Deno.env.get("GEMINI_API_KEY")),
        openrouter: Boolean(Deno.env.get("OPENROUTER_API_KEY"))
      }
    }, 200, headers);
  }

  if (!Array.isArray(body?.messages) || body.messages.length < 1 || body.messages.length > 10) {
    return json({ ok: false, error: "Send between 1 and 10 messages." }, 400, headers);
  }

  const messages: ChatMessage[] = [];
  let totalChars = 0;
  for (const item of body.messages) {
    if (!item || !["user", "assistant"].includes(item.role) ||
        typeof item.content !== "string" || !item.content.trim() || item.content.length > 8000) {
      return json({ ok: false, error: "A message is invalid or too long." }, 400, headers);
    }
    totalChars += item.content.length;
    messages.push({ role: item.role, content: item.content.trim() });
  }
  if (totalChars > 20000 || messages[messages.length - 1].role !== "user") {
    return json({ ok: false, error: "Request is too large or the last message is invalid." }, 400, headers);
  }

  const admin = createClient(supabaseUrl, serviceKey, {
    auth: { persistSession: false, autoRefreshToken: false }
  });
  const { data: allowed, error: limitError } = await admin
    .schema("private")
    .rpc("consume_ai_gateway_rate_limit", { p_user_id: userData.user.id, p_limit: 20 });

  if (limitError) {
    return json({ ok: false, error: "Security rate-limit check unavailable. Try again shortly." }, 503, headers);
  }
  if (allowed !== true) {
    return json({ ok: false, error: "Too many requests. Please wait one minute and try again." }, 429, headers);
  }

  const providers = [
    { name: "openai", key: Deno.env.get("OPENAI_API_KEY"), run: callOpenAI },
    { name: "gemini", key: Deno.env.get("GEMINI_API_KEY"), run: callGemini },
    { name: "openrouter", key: Deno.env.get("OPENROUTER_API_KEY"), run: callOpenRouter }
  ].filter((p): p is { name: string; key: string; run: (key: string, messages: ChatMessage[]) => Promise<string> } => Boolean(p.key));

  if (providers.length === 0) {
    return json({
      ok: false,
      error: "No AI provider is configured on the server yet.",
      code: "AI_PROVIDERS_NOT_CONFIGURED"
    }, 503, headers);
  }

  const failedProviders: string[] = [];
  for (const provider of providers) {
    try {
      const answer = await provider.run(provider.key, messages);
      return json({ ok: true, provider: provider.name, answer }, 200, headers);
    } catch (error) {
      // Never log prompts, provider responses, or API keys.
      failedProviders.push(`${provider.name}:${error instanceof Error ? error.message : "UNKNOWN"}`);
      console.error("AI provider failed:", provider.name, error instanceof Error ? error.message : "unknown");
    }
  }

  return json({
    ok: false,
    error: "All configured AI providers are temporarily unavailable. Please retry shortly.",
    code: "ALL_AI_PROVIDERS_FAILED",
    attempted: failedProviders
  }, 503, headers);
  } catch (error) {
    const errorName = error instanceof Error ? error.name : "UNKNOWN";
    console.error("AI gateway unhandled error:", errorName);
    const origin = req.headers.get("Origin") || "";
    return json({ ok: false, error: "AI gateway internal error. Please retry later.", code: "AI_GATEWAY_INTERNAL" }, 500, corsHeaders(origin));
  }
});
