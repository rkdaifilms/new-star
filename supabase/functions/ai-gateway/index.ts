import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const ALLOWED_ORIGINS = new Set([
  "https://new-star-nepal.netlify.app",
  "https://new-star-delta.vercel.app",
  "https://new-star-rkdaifilms-8858.vercel.app"
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

const NEW_STAR_AI_SYSTEM_PROMPT = "You are New Star Creator Studio, the full-service creative, learning, research, and technical assistant for creators. Help with whatever safe creator-related task the user asks: story ideas and full original stories, scripts, scene-by-scene storyboards, image concepts and generated images when the image tool is invoked, thumbnails, titles, descriptions, captions, hashtags, voiceover text, editing instructions, video concepts, channel strategy, content calendars, livestream planning, audience engagement, and creator education. Also help write, explain, debug, and improve code, including HTML, CSS, JavaScript, React, SQL, API integrations, and app/website features; provide complete code when asked and clearly state required files, setup, and limitations. Answer broader questions when useful. If the user asks to search or research current information, use a real connected search/retrieval capability if one is available in this product flow; never pretend to have browsed or claim live results when no search tool was used. Clearly distinguish researched facts, general knowledge, and suggestions. For image requests, use the available image-generation action and explain if it fails; do not claim an image was generated unless the action returned one. Reply in the user's latest language and script, switching immediately when they switch. Never invent trends, stats, monetization, copyright permissions, or actions completed in the app. Do not claim to upload, publish, edit files, change settings, access private data, or deploy code unless a verified tool actually did it. Respect copyright and offer original alternatives. Never ask for passwords, OTPs, API keys, bank/card details, or secrets. For payments, payouts, identity checks, and account ownership, direct users to official support and an eligible adult account owner. Be capable, creative, accurate, practical, and concise.";

const NEW_STAR_HELP_SYSTEM_PROMPT = "You are New Star Help Center AI, the app's limited Creator Support, Safety, Reports, and Moderation assistant. You are NOT a general-purpose creative studio and must not replace New Star Creator Studio. Your scope is only New Star account and app support, creator protection, community rules, reports, warnings, moderation notices, safety notifications, upload/playback problems, profiles, settings, privacy, and help using creator features. Act like a careful support and safety desk: explain how to submit a report, collect and organize information supplied by the user, identify which app rule may be relevant when the rules are provided, explain report steps and warning notices, and guide creators on how to protect their accounts and use the appeal/support process. Help review reported behavior neutrally; do not assume the reporter is always right, do not declare guilt from an allegation, and do not invent evidence, report status, warnings, or notification records. You may prepare a warning recommendation or draft for an authorized human moderator, but you must never independently issue a warning, suspend/ban a user, remove content, change account access, or send a notification unless a verified moderation tool and authorized workflow explicitly permit that action. Explain that protection cannot guarantee nobody will ever violate rules; use reporting, evidence review, moderation, and appeals to respond fairly. If a user asks for scripts, stories, images, thumbnails, titles, hashtags, coding, broad research, or other creative production, politely direct them to New Star Creator Studio. Keep responses focused, clear, brief, and step-by-step. Reply in the user's latest language and script. Never claim to see private account data or perform actions without verified app data/tool results. Never ask for passwords, OTPs, API keys, bank/card details, or secrets. Distinguish available, planned, configured, deployed, and tested features. Be safe, neutral, fair, and respectful.";

function firstSecret(...names: string[]) {
  for (const name of names) {
    const value = Deno.env.get(name);

    if (value && value.trim()) return value.trim();
  }
  return "";
}

function providerHttpError(status: number, data: any) {
  const providerMessage = String(data?.error?.message || data?.message || "").toLowerCase();
  if (status === 401) return "INVALID_API_KEY";
  if (status === 403) return providerMessage.includes("billing") || providerMessage.includes("quota") ? "BILLING_OR_QUOTA_BLOCKED" : "PROVIDER_ACCESS_DENIED";
  if (status === 404) return "MODEL_NOT_FOUND_OR_UNAVAILABLE";
  if (status === 429) return "RATE_LIMIT_OR_QUOTA_EXCEEDED";
  if (status >= 500) return "PROVIDER_SERVER_ERROR";
  return `HTTP_${status}`;
}

async function generateGeminiImage(key: string, prompt: string) {
  // Gemini 3.1 Flash Image uses the Interactions API. Gemini 2.5 remains
  // available as a compatibility fallback through generateContent.
  const response = await fetchWithTimeout(
    "https://generativelanguage.googleapis.com/v1beta/interactions",
    {
      method: "POST",
      headers: { "x-goog-api-key": key, "Content-Type": "application/json" },
      body: JSON.stringify({
        model: "gemini-3.1-flash-image",
        input: prompt
      })
    },
    60000
  );
  const data = await response.json().catch(() => ({}));
  if (response.ok) {
    const outputImage = data?.output_image;
    const imageStep = data?.steps?.filter((step: any) => step?.type === "model_output")
      .flatMap((step: any) => step?.content || [])
      .find((part: any) => part?.type === "image" && typeof part?.data === "string" && part.data.length > 0);
    const image = outputImage?.data || imageStep?.data;
    if (typeof image === "string" && image.length > 0) {
      return { image, mimeType: outputImage?.mime_type || imageStep?.mime_type || "image/png" };
    }
    throw new Error("GEMINI_3_1_RETURNED_NO_IMAGE:" + String(data?.status || "UNKNOWN"));
  }

  // Only fall back for model/endpoint availability errors. Authentication,
  // quota and billing failures must remain visible instead of being masked.
  if (response.status !== 404 && response.status !== 400) {
    throw new Error("gemini-3.1-flash-image:" + providerHttpError(response.status, data));
  }

  const fallback = await fetchWithTimeout(
    "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-image:generateContent",
    {
      method: "POST",
      headers: { "x-goog-api-key": key, "Content-Type": "application/json" },
      body: JSON.stringify({
        contents: [{ role: "user", parts: [{ text: "Generate one image based on this description. Return an image, not only text: " + prompt }] }],
        generationConfig: { responseModalities: ["TEXT", "IMAGE"] }
      })
    },
    60000
  );
  const fallbackData = await fallback.json().catch(() => ({}));
  if (!fallback.ok) throw new Error("gemini-2.5-flash-image:" + providerHttpError(fallback.status, fallbackData));
  const parts = fallbackData?.candidates?.[0]?.content?.parts || [];
  const imagePart = parts.find((part: any) =>
    (typeof part?.inlineData?.data === "string" && part.inlineData.data.length > 0) ||
    (typeof part?.inline_data?.data === "string" && part.inline_data.data.length > 0)
  );
  const image = imagePart?.inlineData?.data || imagePart?.inline_data?.data;
  if (!image) throw new Error("gemini-2.5-flash-image:GEMINI_RETURNED_NO_IMAGE:" + String(fallbackData?.candidates?.[0]?.finishReason || "UNKNOWN"));
  return { image, mimeType: imagePart?.inlineData?.mimeType || imagePart?.inline_data?.mime_type || "image/png" };
}
async function generateOpenAIImage(key: string, prompt: string) {
  const response = await fetchWithTimeout("https://api.openai.com/v1/images/generations", {
    method: "POST",
    headers: { "Authorization": `Bearer ${key}`, "Content-Type": "application/json" },
    body: JSON.stringify({ model: "gpt-image-2.5-flare", prompt, size: "1024x1024", n: 1 })
  }, 60000);
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(providerHttpError(response.status, data));
  const image = data?.data?.[0]?.b64_json;
  if (typeof image !== "string" || !image) throw new Error("OPENAI_RETURNED_NO_IMAGE");
  return { image, mimeType: "image/png" };
}

function normalizeGeminiContents(messages: ChatMessage[]) {
  const contents: Array<{ role: "user" | "model"; parts: Array<{ text: string }> }> = [];
  for (const message of messages) {
    const role = message.role === "assistant" ? "model" : "user";
    const previous = contents[contents.length - 1];
    if (previous && previous.role === role) previous.parts.push({ text: message.content });
    else contents.push({ role, parts: [{ text: message.content }] });
  }
  while (contents[0]?.role === "model") contents.shift();
  return contents;
}

async function fetchWithTimeout(url: string, init: RequestInit, timeoutMs = 16000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...init, signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
}

async function callOpenAI(key: string, messages: ChatMessage[], systemPrompt: string) {
  const response = await fetchWithTimeout("https://api.openai.com/v1/chat/completions", {
    method: "POST",
    headers: { "Authorization": `Bearer ${key}`, "Content-Type": "application/json" },
    body: JSON.stringify({
      model: "gpt-4o-mini",
      messages: [{ role: "system", content: systemPrompt }, ...messages.map(m => ({ role: m.role, content: m.content }))],
      temperature: 0.4,
      max_tokens: 900
    })
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(providerHttpError(response.status, data));
  const text = data?.choices?.[0]?.message?.content;
  if (typeof text !== "string" || !text.trim()) throw new Error("EMPTY_RESPONSE");
  return text;
}

async function callGemini(key: string, messages: ChatMessage[], systemPrompt: string) {
  const contents = normalizeGeminiContents(messages);
  if (!contents.length || contents[contents.length - 1].role !== "user") throw new Error("INVALID_GEMINI_TURN_SEQUENCE");
  const response = await fetchWithTimeout(
    "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
    {
      method: "POST",
      headers: { "x-goog-api-key": key, "Content-Type": "application/json" },
      body: JSON.stringify({
        contents,
        systemInstruction: { parts: [{ text: systemPrompt }] },
        generationConfig: { temperature: 0.4, maxOutputTokens: 900 }
      })
    }
  );
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(providerHttpError(response.status, data));
  const text = data?.candidates?.[0]?.content?.parts?.map((p: any) => p.text || "").join("");
  if (typeof text !== "string" || !text.trim()) throw new Error("EMPTY_RESPONSE");
  return text;
}

async function callOpenRouter(key: string, messages: ChatMessage[], systemPrompt: string) {
  const response = await fetchWithTimeout("https://openrouter.ai/api/v1/chat/completions", {
    method: "POST",
    headers: {
      "Authorization": `Bearer ${key}`,
      "Content-Type": "application/json",
      "HTTP-Referer": "https://new-star-delta.vercel.app",
      "X-Title": "New Star Creator Hub"
    },
    body: JSON.stringify({
      model: "openai/gpt-4o-mini",
      messages: [
        { role: "system", content: systemPrompt },
        ...messages.map(m => ({ role: m.role, content: m.content }))
      ],
      temperature: 0.4,
      max_tokens: 900
    })
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(providerHttpError(response.status, data));
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

  if (body?.action === "speak") {
    const spokenText = typeof body.text === "string" ? body.text.trim() : "";
    if (!spokenText || spokenText.length > 3500) return json({ ok: false, error: "Text for speech must be between 1 and 3,500 characters." }, 400, headers);
    const geminiKey = firstSecret("GEMINI_API_KEY", "GOOGLE_GEMINI_API_KEY", "GOOGLE_AI_API_KEY", "GOOGLE_API_KEY");
    const openaiKey = firstSecret("OPENAI_API_KEY", "OPENAI_KEY");
    if (!geminiKey && !openaiKey) return json({ ok: false, error: "Multilingual AI voice is not configured. Written replies remain available." }, 503, headers);
    const speechAdmin = createClient(supabaseUrl, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });
    const { data: speechAllowed, error: speechLimitError } = await speechAdmin.rpc("consume_ai_gateway_rate_limit", { p_user_id: userData.user.id, p_limit: 20 });
    if (speechLimitError || speechAllowed !== true) return json({ ok: false, error: speechLimitError ? "Security rate-limit check unavailable." : "Too many requests. Please wait one minute." }, speechLimitError ? 503 : 429, headers);

    if (geminiKey) {
      try {
        const response = await fetchWithTimeout("https://generativelanguage.googleapis.com/v1beta/interactions", {
          method: "POST",
          headers: { "x-goog-api-key": geminiKey, "Content-Type": "application/json" },
          body: JSON.stringify({
            model: "gemini-3.8-flash-tts",
            input: [{ type: "user_input", content: [{
              type: "text",
              text: spokenText,
              annotations: [{ type: "speech_metadata", style: "warm, clear, natural conversational delivery. Speak the text exactly in its original language; do not translate." }]
            }]}],
            response_format: { type: "audio" },
            generation_config: { speech_config: [{ voice: "Kore" }] }
          })
        }, 60000);
        const data = await response.json().catch(() => ({}));
        const audio = data?.output_audio?.data || data?.steps?.filter((step: any) => step?.type === "model_output").flatMap((step: any) => step?.content || []).filter((part: any) => part?.type === "audio").at(-1)?.data;
        if (response.ok && typeof audio === "string" && audio) {
          return json({ ok: true, audio, mimeType: "audio/wav", provider: "gemini" }, 200, headers);
        }
        if (!openaiKey) return json({ ok: false, error: response.ok ? "Gemini voice returned no audio." : providerHttpError(response.status, data) }, 502, headers);
      } catch (error) {
        if (!openaiKey) return json({ ok: false, error: error instanceof Error ? error.message : "Gemini voice service unavailable." }, 502, headers);
      }
    }

    if (openaiKey) {
      const response = await fetchWithTimeout("https://api.openai.com/v1/audio/speech", {
        method: "POST",
        headers: { "Authorization": `Bearer ${openaiKey}`, "Content-Type": "application/json" },
        body: JSON.stringify({
          model: "gpt-4o-mini-tts",
          voice: "alloy",
          input: spokenText,
          instructions: "Speak the input text naturally in exactly the same language as the input. Do not translate, summarize, or add words.",
          response_format: "mp3"
        })
      }, 60000);
      if (!response.ok) {
        const errorData = await response.json().catch(() => ({}));
        return json({ ok: false, error: providerHttpError(response.status, errorData) }, 502, headers);
      }
      const bytes = new Uint8Array(await response.arrayBuffer());
      let binary = "";
      for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
      return json({ ok: true, audio: btoa(binary), mimeType: "audio/mpeg", provider: "openai" }, 200, headers);
    }
    return json({ ok: false, error: "AI voice providers are unavailable." }, 503, headers);
  }

  if (body?.action === "transcribe") {
    const audio = body.audio;
    const mimeType = typeof body.mimeType === "string" ? body.mimeType : "audio/webm";
    if (typeof audio !== "string" || audio.length < 100 || audio.length > 11000000 ||
        !/^audio\/(webm|ogg|mp4|mpeg|wav|mp3|m4a)/i.test(mimeType)) {
      return json({ ok: false, error: "Voice recording is missing, too large, or uses an unsupported format." }, 400, headers);
    }
    const geminiKey = firstSecret("GEMINI_API_KEY", "GOOGLE_GEMINI_API_KEY", "GOOGLE_AI_API_KEY", "GOOGLE_API_KEY");
    if (!geminiKey) return json({ ok: false, error: "Multilingual voice transcription is not configured. Gemini provider key is missing." }, 503, headers);
    const voiceAdmin = createClient(supabaseUrl, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });
    const { data: allowedVoice, error: voiceLimitError } = await voiceAdmin.rpc("consume_ai_gateway_rate_limit", { p_user_id: userData.user.id, p_limit: 20 });
    if (voiceLimitError || allowedVoice !== true) return json({ ok: false, error: voiceLimitError ? "Security rate-limit check unavailable." : "Too many requests. Please wait one minute." }, voiceLimitError ? 503 : 429, headers);
    const response = await fetchWithTimeout("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent", {
      method: "POST",
      headers: { "x-goog-api-key": geminiKey, "Content-Type": "application/json" },
      body: JSON.stringify({
        contents: [{ role: "user", parts: [
          { text: "Transcribe this recording exactly in the language spoken. Detect the language automatically. Do not translate. Return only valid JSON with keys language (BCP-47 language tag, best effort) and transcript (verbatim text). If speech is unclear, use the closest accurate transcript and do not invent words." },
          { inline_data: { mime_type: mimeType, data: audio } }
        ] }],
        generationConfig: { temperature: 0, responseMimeType: "application/json", maxOutputTokens: 1200 }
      })
    });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) return json({ ok: false, error: providerHttpError(response.status, data) }, 502, headers);
    const output = data?.candidates?.[0]?.content?.parts?.map((p: any) => p.text || "").join("") || "";
    try {
      const parsed = JSON.parse(output);
      if (typeof parsed.transcript !== "string" || !parsed.transcript.trim()) throw new Error("empty");
      return json({ ok: true, transcript: parsed.transcript.trim(), language: String(parsed.language || "und") }, 200, headers);
    } catch {
      return json({ ok: false, error: "Voice transcription returned an unreadable result. Please try again." }, 502, headers);
    }
  }

  if (body?.action === "image") {
    const prompt = typeof body.prompt === "string" ? body.prompt.trim() : "";
    if (!prompt || prompt.length > 1800) return json({ ok: false, error: "Enter an image description of up to 1,800 characters." }, 400, headers);
    const geminiKey = firstSecret("GEMINI_API_KEY", "GOOGLE_GEMINI_API_KEY", "GOOGLE_AI_API_KEY", "GOOGLE_API_KEY");
    const openaiKey = firstSecret("OPENAI_API_KEY", "OPENAI_KEY");
    if (!geminiKey && !openaiKey) return json({ ok: false, error: "Image generation is not configured. Add a Gemini or OpenAI provider key on the server." }, 503, headers);
    const imageAdmin = createClient(supabaseUrl, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });
    const { data: imageAllowed, error: imageLimitError } = await imageAdmin.rpc("consume_ai_gateway_rate_limit", { p_user_id: userData.user.id, p_limit: 20 });
    if (imageLimitError || imageAllowed !== true) return json({ ok: false, error: imageLimitError ? "Security rate-limit check unavailable." : "Too many requests. Please wait one minute." }, imageLimitError ? 503 : 429, headers);

    const failures: string[] = [];
    if (geminiKey) {
      try {
        const result = await generateGeminiImage(geminiKey, prompt);
        return json({ ok: true, ...result, provider: "gemini" }, 200, headers);
      } catch (error) {
        failures.push("gemini:" + (error instanceof Error ? error.message : "UNKNOWN"));
        console.error("AI image provider failed:", "gemini", error instanceof Error ? error.message : "unknown");
      }
    }
    if (openaiKey) {
      try {
        const result = await generateOpenAIImage(openaiKey, prompt);
        return json({ ok: true, ...result, provider: "openai" }, 200, headers);
      } catch (error) {
        failures.push("openai:" + (error instanceof Error ? error.message : "UNKNOWN"));
        console.error("AI image provider failed:", "openai", error instanceof Error ? error.message : "unknown");
      }
    }
    return json({
      ok: false,
      error: "Image generation providers are temporarily unavailable. Please retry shortly.",
      code: "ALL_IMAGE_PROVIDERS_FAILED",
      attempted: failures
    }, 503, headers);
  }

  if (body?.action === "status") {
    return json({
      ok: true,
      status: true,
      providers: {
        openai: Boolean(firstSecret("OPENAI_API_KEY", "OPENAI_KEY")),
        gemini: Boolean(firstSecret("GEMINI_API_KEY", "GOOGLE_GEMINI_API_KEY", "GOOGLE_AI_API_KEY", "GOOGLE_API_KEY")),
        openrouter: Boolean(firstSecret("OPENROUTER_API_KEY", "OPENROUTER_KEY"))
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
    .rpc("consume_ai_gateway_rate_limit", { p_user_id: userData.user.id, p_limit: 20 });

  if (limitError) {
    return json({ ok: false, error: "Security rate-limit check unavailable. Try again shortly." }, 503, headers);
  }
  if (allowed !== true) {
    return json({ ok: false, error: "Too many requests. Please wait one minute and try again." }, 429, headers);
  }

  const providers = [
    { name: "gemini", key: firstSecret("GEMINI_API_KEY", "GOOGLE_GEMINI_API_KEY", "GOOGLE_AI_API_KEY", "GOOGLE_API_KEY"), run: callGemini },
    { name: "openrouter", key: firstSecret("OPENROUTER_API_KEY", "OPENROUTER_KEY"), run: callOpenRouter },
    { name: "openai", key: firstSecret("OPENAI_API_KEY", "OPENAI_KEY"), run: callOpenAI }
  ].filter((p): p is { name: string; key: string; run: (key: string, messages: ChatMessage[], systemPrompt: string) => Promise<string> } => Boolean(p.key));

  if (providers.length === 0) {
    return json({
      ok: false,
      error: "No AI provider is configured on the server yet.",
      code: "AI_PROVIDERS_NOT_CONFIGURED"
    }, 503, headers);
  }

  const systemPrompt = body.mode === "help" ? NEW_STAR_HELP_SYSTEM_PROMPT : NEW_STAR_AI_SYSTEM_PROMPT;

  const failedProviders: string[] = [];
  for (const provider of providers) {
    try {
      const answer = await provider.run(provider.key, messages, systemPrompt);
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
