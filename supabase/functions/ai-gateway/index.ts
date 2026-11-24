import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const ALLOWED_ORIGINS = new Set([
  "https://new-star-nepal.netlify.app",
  "https://star-delta.vercel.app",
  "https://new-star-delta.vercel.app",
  "https://new-star-rkdaifilms-8858.vercel.app",
  "https://new-star.rkdaifilms.workers.dev",
  "https://new-star-git-new-star-repair-pass-1-rkdaifilms-8858.vercel.app"
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

const AI_LANGUAGE_NAMES: Record<string,string> = {hi:"Hindi",ne:"Nepali",en:"English",zh:"Simplified Chinese",es:"Spanish",fr:"French",ar:"Arabic",ja:"Japanese",de:"German",ru:"Russian",pt:"Portuguese",it:"Italian",ko:"Korean",tr:"Turkish",bn:"Bengali",ur:"Urdu",id:"Indonesian",vi:"Vietnamese",th:"Thai",fa:"Persian",sw:"Swahili",nl:"Dutch",pl:"Polish",uk:"Ukrainian",el:"Greek"};

const NEW_STAR_HELP_SYSTEM_PROMPT = "You are New Star Help Center AI, the app's limited Creator Support, Safety, Reports, and Moderation assistant. You are NOT a general-purpose creative studio and must not replace New Star Creator Studio. Your scope is only New Star account and app support, creator protection, community rules, reports, warnings, moderation notices, safety notifications, upload/playback problems, profiles, settings, privacy, and help using creator features. Act like a careful support and safety desk: explain how to submit a report, collect and organize information supplied by the user, identify which app rule may be relevant when the rules are provided, explain report steps and warning notices, and guide creators on how to protect their accounts and use the appeal/support process. Help review reported behavior neutrally; do not assume the reporter is always right, do not declare guilt from an allegation, and do not invent evidence, report status, warnings, or notification records. You may prepare a warning recommendation or draft for an authorized human moderator, but you must never independently issue a warning, suspend/ban a user, remove content, change account access, or send a notification unless a verified moderation tool and authorized workflow explicitly permit that action. Explain that protection cannot guarantee nobody will ever violate rules; use reporting, evidence review, moderation, and appeals to respond fairly. If a user asks for scripts, stories, images, thumbnails, titles, hashtags, coding, broad research, or other creative production, politely direct them to New Star Creator Studio. Keep responses focused, clear, brief, and step-by-step. Reply in the user's latest language and script. Never claim to see private account data or perform actions without verified app data/tool results. Never ask for passwords, OTPs, API keys, bank/card details, or secrets. Distinguish available, planned, configured, deployed, and tested features. Be safe, neutral, fair, and respectful.";

function secretCandidates(...names: string[]) {
  const values = new Set<string>();
  for (const name of names) {
    for (const suffix of Array.from({ length: 21 }, (_, index) => index === 0 ? "" : "_" + index)) {
      const value = Deno.env.get(name + suffix);
      if (value && value.trim()) values.add(value.trim());
    }
  }
  return [...values];
}

function firstSecret(...names: string[]) {
  return secretCandidates(...names)[0] || "";
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

function requestedImageAspectRatio(prompt: string, requested: unknown) {
  const allowed = ["1:1", "3:4", "4:3", "9:16", "16:9"];
  if (typeof requested === "string" && allowed.includes(requested)) return requested;
  const match = prompt.match(/(\d{2,5})\s*[x×]\s*(\d{2,5})/i) || prompt.match(/(\d{1,2})\s*:\s*(\d{1,2})/);
  if (!match) return "1:1";
  const width = Number(match[1]), height = Number(match[2]);
  if (!width || !height) return "1:1";
  const ratio = width / height;
  return allowed.reduce((best, item) => {
    const [w, h] = item.split(":").map(Number);
    const [bestW, bestH] = best.split(":").map(Number);
    return Math.abs(Math.log(ratio / (w / h))) < Math.abs(Math.log(ratio / (bestW / bestH))) ? item : best;
  }, "1:1");
}

async function generateGeminiImage(key: string, prompt: string, aspectRatio: string, imageSize: string) {
  // Try fast native image generation first, then compatible higher-quality and
  // lightweight models. Quota/model access failures must not stop model fallback.
  const models = ["gemini-3.1-flash-image", "gemini-3-pro-image", "gemini-3.1-flash-lite-image", "gemini-2.5-flash-image"];
  let lastFailure = "GEMINI_IMAGE_UNAVAILABLE";
  for (const model of models) {
    try {
      const response = await fetchWithTimeout(
        `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`,
        {
          method: "POST",
          headers: { "x-goog-api-key": key, "Content-Type": "application/json" },
          body: JSON.stringify({
            contents: [{ role: "user", parts: [{ text: "Generate one image based on this description. Return an image, not only text: " + prompt }] }],
            generationConfig: { responseModalities: ["TEXT", "IMAGE"], imageConfig: { aspectRatio, imageSize } }
          })
        },
        60000
      );
      const data = await response.json().catch(() => ({}));
      if (!response.ok) {
        lastFailure = model + ":" + providerHttpError(response.status, data);
        if (response.status === 401) throw new Error(lastFailure);
        console.warn("Gemini image model fallback:", model, lastFailure);
        continue;
      }
      const parts = data?.candidates?.[0]?.content?.parts || [];
      const imagePart = parts.find((part: any) =>
        (typeof part?.inlineData?.data === "string" && part.inlineData.data.length > 0) ||
        (typeof part?.inline_data?.data === "string" && part.inline_data.data.length > 0)
      );
      const image = imagePart?.inlineData?.data || imagePart?.inline_data?.data;
      if (image) {
        return { image, mimeType: imagePart?.inlineData?.mimeType || imagePart?.inline_data?.mime_type || "image/png" };
      }
      lastFailure = model + ":GEMINI_RETURNED_NO_IMAGE:" + (data?.candidates?.[0]?.finishReason || "UNKNOWN");
    } catch (error) {
      lastFailure = model + ":" + (error instanceof Error ? error.message : "REQUEST_FAILED");
      if (lastFailure.includes("INVALID_API_KEY")) throw error;
      console.warn("Gemini image model request failed; trying fallback:", model, lastFailure);
    }
  }
  throw new Error(lastFailure);
}
async function generateOpenAIImage(key: string, prompt: string, aspectRatio: string) {
  const response = await fetchWithTimeout("https://api.openai.com/v1/images/generations", {
    method: "POST",
    headers: { "Authorization": `Bearer ${key}`, "Content-Type": "application/json" },
    body: JSON.stringify({ model: "gpt-image-2.5-flare", prompt, size: aspectRatio === "16:9" || aspectRatio === "4:3" ? "1536x1024" : aspectRatio === "9:16" || aspectRatio === "3:4" ? "1024x1536" : "1024x1024", n: 1 })
  }, 60000);
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(providerHttpError(response.status, data));
  const image = data?.data?.[0]?.b64_json;
  if (typeof image !== "string" || !image) throw new Error("OPENAI_RETURNED_NO_IMAGE");
  return { image, mimeType: "image/png" };
}

async function generateOpenRouterImage(key: string, prompt: string, aspectRatio: string) {
  const response = await fetchWithTimeout("https://openrouter.ai/api/v1/chat/completions", {
    method: "POST",
    headers: {
      "Authorization": `Bearer ${key}`,
      "Content-Type": "application/json",
      "HTTP-Referer": "https://new-star-delta.vercel.app",
      "X-Title": "New Star Creator Studio"
    },
    body: JSON.stringify({
      model: "google/gemini-3.1-flash-image",
      modalities: ["text", "image"],
      image_config: { aspect_ratio: aspectRatio },
      messages: [{ role: "user", content: "Generate one image, not just a description. Create this image: " + prompt }]
    })
  }, 90000);
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(providerHttpError(response.status, data));
  const imageItem = data?.choices?.[0]?.message?.images?.find((item: any) =>
    typeof item?.image_url?.url === "string" && item.image_url.url.length > 20
  );
  const imageUrl = imageItem?.image_url?.url;
  if (!imageUrl) throw new Error("OPENROUTER_RETURNED_NO_IMAGE");
  const dataMatch = imageUrl.match(/^data:(image\/(?:png|jpeg|webp));base64,([A-Za-z0-9+/=]+)$/i);
  if (dataMatch) return { image: dataMatch[2], mimeType: dataMatch[1] };
  if (/^https:\/\//i.test(imageUrl)) {
    const imageResponse = await fetchWithTimeout(imageUrl, {}, 60000);
    if (!imageResponse.ok) throw new Error("OPENROUTER_IMAGE_DOWNLOAD_HTTP_" + imageResponse.status);
    const mimeType = imageResponse.headers.get("content-type")?.split(";")[0] || "image/png";
    if (!/^image\/(png|jpeg|webp)$/i.test(mimeType)) throw new Error("OPENROUTER_UNSUPPORTED_IMAGE_FORMAT");
    const bytes = new Uint8Array(await imageResponse.arrayBuffer());
    let binary = "";
    for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
    return { image: btoa(binary), mimeType };
  }
  throw new Error("OPENROUTER_INVALID_IMAGE_DATA");
}


async function startGeminiVideo(key: string, prompt: string, aspectRatio: string, resolution: string, duration: string) {
  const model = Deno.env.get("NEW_STAR_VIDEO_MODEL")?.trim() || "gemini-omni-1.2-flash";
  const response = await fetchWithTimeout("https://generativelanguage.googleapis.com/v1beta/interactions", {
    method: "POST",
    headers: { "x-goog-api-key": key, "Content-Type": "application/json" },
    body: JSON.stringify({
      model,
      input: [{ type: "text", text: prompt }],
      generation_config: { video_config: { task: "text_to_video" } },
      response_format: { type: "video", delivery: "inline", aspect_ratio: aspectRatio, resolution, duration },
      background: true,
      store: true
    })
  }, 30000);
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(providerHttpError(response.status, data));
  if (typeof data?.id !== "string" || !data.id || !["in_progress", "completed"].includes(data?.status)) {
    throw new Error("GEMINI_INVALID_VIDEO_INTERACTION");
  }
  return { interactionId: data.id, status: data.status, model };
}

async function getGeminiVideoInteraction(key: string, interactionId: string) {
  const response = await fetchWithTimeout(
    "https://generativelanguage.googleapis.com/v1beta/interactions/" + encodeURIComponent(interactionId),
    { method: "GET", headers: { "x-goog-api-key": key } },
    25000
  );
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(providerHttpError(response.status, data));
  return data;
}

function extractGeminiVideoData(interaction: any): string | null {
  const steps = Array.isArray(interaction?.steps) ? interaction.steps : [];
  for (const step of steps) {
    if (step?.type !== "model_output" || !Array.isArray(step?.content)) continue;
    for (const part of step.content) {
      if (part?.type === "video" && typeof part?.data === "string" && part.data.length > 100) return part.data;
    }
  }
  return null;
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
      max_tokens: 1400
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

  const models = ["gemini-3.8-flash", "gemini-3.5-flash-lite"];
  let lastFailure = "GEMINI_UNAVAILABLE";
  for (const model of models) {
    try {
      const response = await fetchWithTimeout(
        `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`,
        {
          method: "POST",
          headers: { "x-goog-api-key": key, "Content-Type": "application/json" },
          body: JSON.stringify({
            contents,
            systemInstruction: { parts: [{ text: systemPrompt }] },
            generationConfig: { temperature: 0.4, maxOutputTokens: 1400 }
          })
        },
        30000
      );
      const data = await response.json().catch(() => ({}));
      if (!response.ok) {
        lastFailure = providerHttpError(response.status, data);
        if (response.status === 401) throw new Error(lastFailure);
        console.warn("Gemini chat model fallback:", model, lastFailure);
        continue;
      }
      const answer = data?.candidates?.[0]?.content?.parts?.map((p: any) => p.text || "").join("");
      if (typeof answer === "string" && answer.trim()) return answer;
      lastFailure = model + ":EMPTY_RESPONSE";
    } catch (error) {
      lastFailure = error instanceof Error ? error.message : "GEMINI_REQUEST_FAILED";
      if (lastFailure === "INVALID_API_KEY") throw error;
      console.warn("Gemini chat request failed; trying fallback:", model, lastFailure);
    }
  }
  throw new Error(lastFailure);
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


async function callNewStarHostedModel(key: string, messages: ChatMessage[], systemPrompt: string) {
  const configuredEndpoint = Deno.env.get("NEW_STAR_MODEL_BASE_URL")?.trim() || "";
  const model = Deno.env.get("NEW_STAR_MODEL_NAME")?.trim() || "";
  if (!configuredEndpoint || !model) throw new Error("NEW_STAR_MODEL_NOT_CONFIGURED");
  let endpoint: URL;
  try { endpoint = new URL(configuredEndpoint); }
  catch { throw new Error("NEW_STAR_MODEL_INVALID_ENDPOINT"); }
  if (endpoint.protocol !== "https:" || endpoint.username || endpoint.password || endpoint.search || endpoint.hash) {
    throw new Error("NEW_STAR_MODEL_ENDPOINT_MUST_BE_HTTPS");
  }
  const base = endpoint.toString().replace(/\/$/, "");
  const requestUrl = base.endsWith("/chat/completions") ? base : base + "/chat/completions";
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (key && key !== "local-no-key") headers.Authorization = "Bearer " + key;
  const response = await fetchWithTimeout(requestUrl, {
    method: "POST", headers,
    body: JSON.stringify({
      model,
      messages: [{ role: "system", content: systemPrompt }, ...messages.map(m => ({ role: m.role, content: m.content }))],
      temperature: 0.4, max_tokens: 1400
    })
  }, 45000);
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(providerHttpError(response.status, data));
  const answer = data?.choices?.[0]?.message?.content;
  if (typeof answer !== "string" || !answer.trim()) throw new Error("NEW_STAR_MODEL_EMPTY_RESPONSE");
  return answer.trim();
}

async function probeNewStarHostedModel() {
  const configuredEndpoint = Deno.env.get("NEW_STAR_MODEL_BASE_URL")?.trim() || "";
  const model = Deno.env.get("NEW_STAR_MODEL_NAME")?.trim() || "";
  const key = Deno.env.get("NEW_STAR_MODEL_API_KEY")?.trim() || "";
  if (!configuredEndpoint || !model) return { configured: false, reachable: false, modelAvailable: false };
  try {
    const endpoint = new URL(configuredEndpoint);
    if (endpoint.protocol !== "https:" || endpoint.username || endpoint.password || endpoint.search || endpoint.hash) {
      return { configured: true, reachable: false, modelAvailable: false };
    }
    const base = endpoint.toString().replace(/\/$/, "");
    const modelsUrl = base.endsWith("/models") ? base : base.replace(/\/v1$/, "") + "/v1/models";
    const headers: Record<string, string> = {};
    if (key) headers.Authorization = "Bearer " + key;
    const response = await fetchWithTimeout(modelsUrl, { method: "GET", headers, redirect: "error" }, 5000);
    if (!response.ok) {
      await response.body?.cancel().catch(() => {});
      return { configured: true, reachable: false, modelAvailable: false };
    }
    const data = await response.json().catch(() => ({}));
    const models = Array.isArray(data?.data) ? data.data : [];
    return { configured: true, reachable: true, modelAvailable: models.some((item: any) => item?.id === model) };
  } catch {
    return { configured: true, reachable: false, modelAvailable: false };
  }
}


async function transcribeWithOpenAI(key: string, audio: string, mimeType: string) {
  const binary = atob(audio);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  const cleanMime = mimeType.split(";")[0].toLowerCase();
  const extension = cleanMime === "audio/mp4" || cleanMime === "audio/m4a" ? "m4a"
    : cleanMime === "audio/ogg" ? "ogg"
    : cleanMime === "audio/wav" ? "wav"
    : cleanMime === "audio/mpeg" || cleanMime === "audio/mp3" ? "mp3"
    : "webm";
  const form = new FormData();
  form.append("file", new Blob([bytes], { type: cleanMime }), "new-star-voice." + extension);
  form.append("model", "gpt-4o-mini-transcribe");
  form.append("response_format", "json");
  form.append("prompt", "Transcribe the speech exactly in the original language. Do not translate, summarize, or add words.");
  const response = await fetchWithTimeout("https://api.openai.com/v1/audio/transcriptions", {
    method: "POST",
    headers: { "Authorization": `Bearer ${key}` },
    body: form
  }, 60000);
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(providerHttpError(response.status, data));
  if (typeof data?.text !== "string" || !data.text.trim()) throw new Error("EMPTY_TRANSCRIPTION");
  return data.text.trim();
}

Deno.serve(async (req: Request) => {
  try {
  const origin = req.headers.get("Origin") || "";
  const headers = corsHeaders(origin);
  if (req.method === "OPTIONS") return new Response("ok", { headers });
  if (req.method !== "POST") return json({ ok: false, error: "Method not allowed" }, 405, headers);
  // Browser calls must come from an approved site. Native Android requests have no
  // Origin header; they are authenticated immediately below with Supabase auth.getUser().
  if (origin && !ALLOWED_ORIGINS.has(origin)) return json({ ok: false, error: "Origin not allowed" }, 403, headers);

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
    const geminiKeys = secretCandidates("GEMINI_API_KEY", "GOOGLE_GEMINI_API_KEY", "GOOGLE_AI_API_KEY", "GOOGLE_API_KEY");
    const openaiKeys = secretCandidates("OPENAI_API_KEY", "OPENAI_KEY");
    if (!geminiKeys.length && !openaiKeys.length) return json({ ok: false, error: "Multilingual AI voice is not configured. Written replies remain available." }, 503, headers);
    const speechAdmin = createClient(supabaseUrl, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });
    const { data: speechAllowed, error: speechLimitError } = await speechAdmin.rpc("consume_ai_gateway_rate_limit", { p_user_id: userData.user.id, p_limit: 20 });
    if (speechLimitError || speechAllowed !== true) return json({ ok: false, error: speechLimitError ? "Security rate-limit check unavailable." : "Too many requests. Please wait one minute." }, speechLimitError ? 503 : 429, headers);

    const failures: string[] = [];
    const speechModels = ["gemini-3.8-flash-tts", "gemini-3.8-flash-lite-tts"];
    for (let keyIndex = 0; keyIndex < geminiKeys.length; keyIndex++) {
      for (const model of speechModels) {
        try {
          const response = await fetchWithTimeout(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`, {
            method: "POST",
            headers: { "x-goog-api-key": geminiKeys[keyIndex], "Content-Type": "application/json" },
            body: JSON.stringify({
              contents: [{ role: "user", parts: [{ text: "Speak the following text naturally and clearly in the same language. Do not translate or add words: " + spokenText }] }],
              generationConfig: {
                responseModalities: ["AUDIO"],
                speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: "Kore" } } }
              }
            })
          }, 60000);
          const data = await response.json().catch(() => ({}));
          if (!response.ok) {
            const reason = providerHttpError(response.status, data);
            failures.push("gemini[" + (keyIndex + 1) + "]/" + model + ":" + reason);
            if (response.status === 404) continue;
            break;
          }
          const audioPart = data?.candidates?.[0]?.content?.parts?.find((part: any) =>
            typeof part?.inlineData?.data === "string" && part.inlineData.data.length > 0
          );
          if (audioPart?.inlineData?.data) {
            return json({ ok: true, audio: audioPart.inlineData.data, mimeType: audioPart.inlineData.mimeType || "audio/wav", provider: "gemini" }, 200, headers);
          }
          failures.push("gemini[" + (keyIndex + 1) + "]/" + model + ":GEMINI_RETURNED_NO_AUDIO");
          break;
        } catch (error) {
          failures.push("gemini[" + (keyIndex + 1) + "]/" + model + ":" + (error instanceof Error ? error.message : "GEMINI_TTS_UNAVAILABLE"));
          break;
        }
      }
    }

    for (let keyIndex = 0; keyIndex < openaiKeys.length; keyIndex++) {
      try {
        const response = await fetchWithTimeout("https://api.openai.com/v1/audio/speech", {
          method: "POST",
          headers: { "Authorization": `Bearer ${openaiKeys[keyIndex]}`, "Content-Type": "application/json" },
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
          failures.push("openai[" + (keyIndex + 1) + "]:" + providerHttpError(response.status, errorData));
          continue;
        }
        const bytes = new Uint8Array(await response.arrayBuffer());
        let binary = "";
        for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
        return json({ ok: true, audio: btoa(binary), mimeType: "audio/mpeg", provider: "openai" }, 200, headers);
      } catch (error) {
        failures.push("openai[" + (keyIndex + 1) + "]:" + (error instanceof Error ? error.message : "OPENAI_TTS_UNAVAILABLE"));
      }
    }
    console.error("AI speech providers exhausted:", failures.join("; ").slice(0, 1200));
    return json({ ok: false, error: "AI voice providers are unavailable. Written AI replies remain available.", attempted: failures }, 502, headers);
  }

  if (body?.action === "transcribe") {
    const audio = body.audio;
    const rawMimeType = typeof body.mimeType === "string" ? body.mimeType : "audio/webm";
    const mimeType = rawMimeType.split(";")[0].trim().toLowerCase();
    const extensions: Record<string, string> = { "audio/webm": "webm", "audio/ogg": "ogg", "audio/mp4": "m4a", "audio/mpeg": "mp3", "audio/mp3": "mp3", "audio/wav": "wav", "audio/m4a": "m4a" };
    if (typeof audio !== "string" || audio.length < 100 || audio.length > 11000000 || !extensions[mimeType]) return json({ ok: false, error: "Voice recording is missing, too large, or uses an unsupported format." }, 400, headers);
    const voiceAdmin = createClient(supabaseUrl, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });
    const { data: allowedVoice, error: voiceLimitError } = await voiceAdmin.rpc("consume_ai_gateway_rate_limit", { p_user_id: userData.user.id, p_limit: 20 });
    if (voiceLimitError || allowedVoice !== true) return json({ ok: false, error: voiceLimitError ? "Security rate-limit check unavailable." : "Too many requests. Please wait one minute." }, voiceLimitError ? 503 : 429, headers);
    const failures: string[] = [];
    const audioBytes = Uint8Array.from(atob(audio), (character) => character.charCodeAt(0));
    const openaiKeys = secretCandidates("OPENAI_API_KEY", "OPENAI_KEY");
    for (let keyIndex = 0; keyIndex < openaiKeys.length; keyIndex++) {
      try {
        const form = new FormData();
        form.append("file", new File([audioBytes], "new-star-voice." + extensions[mimeType], { type: mimeType }));
        form.append("model", "gpt-transcribe");
        form.append("response_format", "json");
        const response = await fetchWithTimeout("https://api.openai.com/v1/audio/transcriptions", { method: "POST", headers: { "Authorization": "Bearer " + openaiKeys[keyIndex] }, body: form }, 105000);
        const data = await response.json().catch(() => ({}));
        if (response.ok && typeof data.text === "string" && data.text.trim()) return json({ ok: true, transcript: data.text.trim(), language: "und", provider: "openai" }, 200, headers);
        const reason = response.ok ? "EMPTY_TRANSCRIPTION" : providerHttpError(response.status, data);
        failures.push("openai[" + (keyIndex + 1) + "]:" + reason);
        console.error("AI voice transcription provider failed:", "openai", keyIndex + 1, reason);
      } catch (error) {
        const reason = error instanceof Error ? error.message : "OPENAI_TRANSCRIPTION_FAILED";
        failures.push("openai[" + (keyIndex + 1) + "]:" + reason);
        console.error("AI voice transcription provider failed:", "openai", keyIndex + 1, reason);
      }
    }
    const geminiKeys = secretCandidates("GEMINI_API_KEY", "GOOGLE_GEMINI_API_KEY", "GOOGLE_AI_API_KEY", "GOOGLE_API_KEY");
    const transcriptionModels = ["gemini-3.8-flash", "gemini-3.5-flash-lite"];
    for (let keyIndex = 0; keyIndex < geminiKeys.length; keyIndex++) {
      for (const model of transcriptionModels) {
        try {
          const response = await fetchWithTimeout("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent", {
            method: "POST", headers: { "x-goog-api-key": geminiKeys[keyIndex], "Content-Type": "application/json" },
            body: JSON.stringify({ contents: [{ role: "user", parts: [{ text: "Transcribe this recording exactly in the language spoken. Detect the language automatically. Do not translate. Return only JSON with language and transcript." }, { inline_data: { mime_type: mimeType, data: audio } }] }], generationConfig: { temperature: 0, responseMimeType: "application/json", maxOutputTokens: 1200 } })
          }, 105000);
          const data = await response.json().catch(() => ({}));
          if (!response.ok) {
            const reason = providerHttpError(response.status, data); failures.push("gemini[" + (keyIndex + 1) + "]/" + model + ":" + reason);
            console.error("AI voice transcription provider failed:", "gemini", keyIndex + 1, model, reason);
            if (response.status === 404) continue; break;
          }
          const output = data?.candidates?.[0]?.content?.parts?.map((p: any) => p.text || "").join("") || "";
          const parsed = JSON.parse(output);
          if (typeof parsed.transcript === "string" && parsed.transcript.trim()) return json({ ok: true, transcript: parsed.transcript.trim(), language: String(parsed.language || "und"), provider: "gemini" }, 200, headers);
          failures.push("gemini[" + (keyIndex + 1) + "]/" + model + ":EMPTY_TRANSCRIPTION");
        } catch (error) {
          const reason = error instanceof Error ? error.message : "GEMINI_TRANSCRIPTION_FAILED";
          failures.push("gemini[" + (keyIndex + 1) + "]/" + model + ":" + reason);
          console.error("AI voice transcription provider failed:", "gemini", keyIndex + 1, model, reason); break;
        }
      }
    }
    return json({ ok: false, error: "Voice transcription providers failed: " + failures.join("; "), attempted: failures }, 502, headers);
  }
  if (body?.action === "video") {
    const prompt = typeof body.prompt === "string" ? body.prompt.trim() : "";
    const aspectRatio = ["16:9", "9:16"].includes(String(body.aspectRatio || "")) ? String(body.aspectRatio) : "16:9";
    const resolution = ["360p", "720p", "1080p"].includes(String(body.resolution || "")) ? String(body.resolution) : "720p";
    const duration = "6s";
    if (!prompt || prompt.length > 1800) return json({ ok: false, error: "Enter a video description of up to 1,800 characters." }, 400, headers);
    const geminiKeys = secretCandidates("GEMINI_API_KEY", "GOOGLE_GEMINI_API_KEY", "GOOGLE_AI_API_KEY", "GOOGLE_API_KEY");
    if (!geminiKeys.length) return json({ ok: false, error: "Video generation is not configured. An authorized project owner must configure the Gemini API key on the server." }, 503, headers);
    const videoAdmin = createClient(supabaseUrl, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });
    const { data: videoAllowed, error: videoLimitError } = await videoAdmin.rpc("consume_ai_gateway_rate_limit", { p_user_id: userData.user.id, p_limit: 3 });
    if (videoLimitError || videoAllowed !== true) return json({ ok: false, error: videoLimitError ? "Security rate-limit check unavailable." : "Video request limit reached. Please wait before trying again." }, videoLimitError ? 503 : 429, headers);

    const failures: string[] = [];
    for (let keyIndex = 0; keyIndex < geminiKeys.length; keyIndex++) {
      try {
        const result = await startGeminiVideo(geminiKeys[keyIndex], prompt, aspectRatio, resolution, duration);
        const { data: job, error: jobError } = await videoAdmin.from("ai_video_jobs").insert({
          user_id: userData.user.id,
          provider_interaction_id: result.interactionId,
          provider_model: result.model,
          status: result.status
        }).select("id").single();
        if (jobError || !job?.id) {
          await fetchWithTimeout("https://generativelanguage.googleapis.com/v1beta/interactions/" + encodeURIComponent(result.interactionId) + "/cancel", {
            method: "POST", headers: { "x-goog-api-key": geminiKeys[keyIndex] }
          }, 10000).catch(() => {});
          throw new Error("VIDEO_JOB_SAVE_FAILED");
        }
        return json({ ok: true, jobId: job.id, status: result.status, provider: "gemini", model: result.model }, 202, headers);
      } catch (error) {
        const reason = error instanceof Error ? error.message : "VIDEO_START_FAILED";
        failures.push("gemini[" + (keyIndex + 1) + "]:" + reason);
        console.error("AI video job start failed:", "gemini", keyIndex + 1, reason);
      }
    }
    const failureText = failures.join(" ").toUpperCase();
    const message = failureText.includes("HTTP_402") || failureText.includes("RATE_LIMIT_OR_QUOTA_EXCEEDED")
      ? "Video provider quota or billing limit reached. An authorized account owner can check provider usage."
      : failureText.includes("INVALID_API_KEY") || failureText.includes("HTTP_403")
        ? "The video provider rejected its server-side key or project access."
        : "Could not start the video job. Check provider access and try again.";
    return json({ ok: false, error: message, code: "VIDEO_JOB_START_FAILED", attempted: failures }, 502, headers);
  }

  if (body?.action === "video-status") {
    const jobId = typeof body.jobId === "string" ? body.jobId.trim() : "";
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(jobId)) {
      return json({ ok: false, error: "A valid video job ID is required." }, 400, headers);
    }
    const videoAdmin = createClient(supabaseUrl, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });
    const { data: job, error: jobError } = await videoAdmin.from("ai_video_jobs")
      .select("id, provider_interaction_id, provider_model, status, storage_path, failure_code, updated_at")
      .eq("id", jobId).eq("user_id", userData.user.id).maybeSingle();
    if (jobError) return json({ ok: false, error: "Could not read video job status." }, 503, headers);
    if (!job) return json({ ok: false, error: "Video job not found." }, 404, headers);
    if (job.status === "failed") return json({ ok: false, status: "failed", error: "Video generation failed. Check provider access and try again.", code: job.failure_code || "VIDEO_JOB_FAILED" }, 200, headers);
    if (job.status === "completed" && job.storage_path) {
      const { data: signed, error: signedError } = await videoAdmin.storage.from("new-star-ai-videos").createSignedUrl(job.storage_path, 3600);
      if (signedError || !signed?.signedUrl) return json({ ok: false, error: "The video is saved, but a secure playback link could not be created." }, 503, headers);
      return json({ ok: true, status: "completed", videoUrl: signed.signedUrl, storagePath: job.storage_path, mimeType: "video/mp4", model: job.provider_model, expiresInSeconds: 3600 }, 200, headers);
    }

    if (Date.now() - new Date(job.updated_at).getTime() < 4000) {
      return json({ ok: true, status: "in_progress", jobId: job.id, model: job.provider_model }, 200, headers);
    }
    await videoAdmin.from("ai_video_jobs").update({ updated_at: new Date().toISOString() }).eq("id", job.id);
    const geminiKeys = secretCandidates("GEMINI_API_KEY", "GOOGLE_GEMINI_API_KEY", "GOOGLE_AI_API_KEY", "GOOGLE_API_KEY");
    if (!geminiKeys.length) return json({ ok: false, error: "Video provider is not configured." }, 503, headers);
    let interaction: any = null;
    let lastFailure = "GEMINI_VIDEO_STATUS_UNAVAILABLE";
    for (const key of geminiKeys) {
      try { interaction = await getGeminiVideoInteraction(key, job.provider_interaction_id); break; }
      catch (error) { lastFailure = error instanceof Error ? error.message : lastFailure; }
    }
    if (!interaction) return json({ ok: false, error: "Could not check video progress. Please retry.", code: lastFailure }, 502, headers);
    if (interaction.status === "in_progress") return json({ ok: true, status: "in_progress", jobId: job.id, model: job.provider_model }, 200, headers);
    if (interaction.status !== "completed") {
      await videoAdmin.from("ai_video_jobs").update({ status: "failed", failure_code: "GEMINI_" + String(interaction.status || "FAILED").toUpperCase(), updated_at: new Date().toISOString() }).eq("id", job.id);
      return json({ ok: false, status: "failed", error: "The video provider could not complete this video.", code: "GEMINI_VIDEO_" + String(interaction.status || "FAILED").toUpperCase() }, 200, headers);
    }

    const encodedVideo = extractGeminiVideoData(interaction);
    if (!encodedVideo || encodedVideo.length > 70000000) {
      await videoAdmin.from("ai_video_jobs").update({ status: "failed", failure_code: "GEMINI_VIDEO_OUTPUT_MISSING_OR_TOO_LARGE", updated_at: new Date().toISOString() }).eq("id", job.id);
      return json({ ok: false, status: "failed", error: "The provider completed the job but returned no supported inline MP4 data.", code: "GEMINI_VIDEO_OUTPUT_MISSING" }, 200, headers);
    }
    const videoBytes = Uint8Array.from(atob(encodedVideo), (character) => character.charCodeAt(0));
    if (videoBytes.byteLength > 50 * 1024 * 1024) {
      await videoAdmin.from("ai_video_jobs").update({ status: "failed", failure_code: "VIDEO_OUTPUT_TOO_LARGE", updated_at: new Date().toISOString() }).eq("id", job.id);
      return json({ ok: false, status: "failed", error: "The generated video exceeds New Star's 50 MB storage limit." }, 200, headers);
    }
    const storagePath = userData.user.id + "/" + crypto.randomUUID() + ".mp4";
    const { error: uploadError } = await videoAdmin.storage.from("new-star-ai-videos").upload(storagePath, videoBytes, { contentType: "video/mp4", cacheControl: "3600", upsert: false });
    if (uploadError) {
      await videoAdmin.from("ai_video_jobs").update({ status: "failed", failure_code: "VIDEO_STORAGE_UPLOAD_FAILED", updated_at: new Date().toISOString() }).eq("id", job.id);
      return json({ ok: false, status: "failed", error: "The video finished but could not be saved to New Star storage." }, 503, headers);
    }
    const { error: updateError } = await videoAdmin.from("ai_video_jobs").update({ status: "completed", storage_path: storagePath, updated_at: new Date().toISOString() }).eq("id", job.id);
    if (updateError) {
      await videoAdmin.storage.from("new-star-ai-videos").remove([storagePath]);
      return json({ ok: false, error: "The video was uploaded but its job record could not be finalized." }, 503, headers);
    }
    const { data: signed, error: signedError } = await videoAdmin.storage.from("new-star-ai-videos").createSignedUrl(storagePath, 3600);
    if (signedError || !signed?.signedUrl) return json({ ok: false, error: "The video is saved, but a secure playback link could not be created." }, 503, headers);
    return json({ ok: true, status: "completed", videoUrl: signed.signedUrl, storagePath, mimeType: "video/mp4", model: job.provider_model, expiresInSeconds: 3600 }, 200, headers);
  }

    if (body?.action === "image") {
    const prompt = typeof body.prompt === "string" ? body.prompt.trim() : "";
    const aspectRatio = requestedImageAspectRatio(prompt, body.aspectRatio);
    const imageSize = ["1K", "2K", "4K"].includes(String(body.imageSize || "")) ? String(body.imageSize) : "1K";
    const imageLanguage = AI_LANGUAGE_NAMES[String(body.language || "")] || "";
    const localizedImagePrompt = imageLanguage ? prompt + "\nIf any visible text is included in the image, write it only in " + imageLanguage + "." : prompt;
    if (!prompt || prompt.length > 1800) return json({ ok: false, error: "Enter an image description of up to 1,800 characters." }, 400, headers);
    const geminiKeys = secretCandidates("GEMINI_API_KEY", "GOOGLE_GEMINI_API_KEY", "GOOGLE_AI_API_KEY", "GOOGLE_API_KEY");
    const openrouterKeys = secretCandidates("OPENROUTER_API_KEY", "OPENROUTER_KEY");
    const openaiKeys = secretCandidates("OPENAI_API_KEY", "OPENAI_KEY");
    if (!geminiKeys.length && !openrouterKeys.length && !openaiKeys.length) return json({ ok: false, error: "Image generation is not configured. Add a Gemini, OpenRouter, or OpenAI provider key on the server." }, 503, headers);
    const imageAdmin = createClient(supabaseUrl, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });
    const { data: imageAllowed, error: imageLimitError } = await imageAdmin.rpc("consume_ai_gateway_rate_limit", { p_user_id: userData.user.id, p_limit: 20 });
    if (imageLimitError || imageAllowed !== true) return json({ ok: false, error: imageLimitError ? "Security rate-limit check unavailable." : "Too many requests. Please wait one minute." }, imageLimitError ? 503 : 429, headers);

    const failures: string[] = [];
    for (let keyIndex = 0; keyIndex < geminiKeys.length; keyIndex++) {
      try {
        const result = await generateGeminiImage(geminiKeys[keyIndex], localizedImagePrompt, aspectRatio, imageSize);
        return json({ ok: true, ...result, aspectRatio, provider: "gemini" }, 200, headers);
      } catch (error) {
        const reason = error instanceof Error ? error.message : "UNKNOWN";
        failures.push("gemini[" + (keyIndex + 1) + "]:" + reason);
        console.error("AI image provider failed:", "gemini", keyIndex + 1, reason);
      }
    }
    for (let keyIndex = 0; keyIndex < openrouterKeys.length; keyIndex++) {
      try {
        const result = await generateOpenRouterImage(openrouterKeys[keyIndex], localizedImagePrompt, aspectRatio);
        return json({ ok: true, ...result, aspectRatio, provider: "openrouter" }, 200, headers);
      } catch (error) {
        const reason = error instanceof Error ? error.message : "UNKNOWN";
        failures.push("openrouter[" + (keyIndex + 1) + "]:" + reason);
        console.error("AI image provider failed:", "openrouter", keyIndex + 1, reason);
      }
    }
    for (let keyIndex = 0; keyIndex < openaiKeys.length; keyIndex++) {
      try {
        const result = await generateOpenAIImage(openaiKeys[keyIndex], localizedImagePrompt, aspectRatio);
        return json({ ok: true, ...result, aspectRatio, provider: "openai" }, 200, headers);
      } catch (error) {
        const reason = error instanceof Error ? error.message : "UNKNOWN";
        failures.push("openai[" + (keyIndex + 1) + "]:" + reason);
        console.error("AI image provider failed:", "openai", keyIndex + 1, reason);
      }
    }
    const failureText = failures.join(" ").toUpperCase();
    const hasQuotaFailure = failureText.includes("RATE_LIMIT_OR_QUOTA_EXCEEDED") || failureText.includes("HTTP_402");
    const hasInvalidKey = failureText.includes("INVALID_API_KEY") || failureText.includes("HTTP_401") || failureText.includes("HTTP_403");
    const errorMessage = hasQuotaFailure && hasInvalidKey
      ? "तस्वीर बनाने की सेवा की सभी उपलब्ध keys में समस्या है: Gemini की quota/limit समाप्त है, OpenRouter खाते में पर्याप्त credits या billing नहीं है, और OpenAI key अमान्य है। Supabase Edge Function Secrets में सही billing वाली Gemini/OpenRouter या मान्य OpenAI key अपडेट करें।"
      : hasQuotaFailure
        ? "तस्वीर बनाने की provider quota या credits सीमा पूरी हो गई है। Gemini/OpenRouter खाते की billing या quota ठीक करें।"
        : hasInvalidKey
          ? "तस्वीर बनाने की API key अस्वीकार हुई है। Supabase Edge Function Secrets में सही provider key अपडेट करें।"
          : "तस्वीर बनाने की सभी सेवाएँ अभी उपलब्ध नहीं हैं। Provider configuration और billing जाँचें।";
    return json({
      ok: false,
      error: errorMessage,
      code: "ALL_IMAGE_PROVIDERS_FAILED",
      attempted: failures
    }, 503, headers);
  }

  if (body?.action === "status") {
    const newStarModel = await probeNewStarHostedModel();
    const newStarTestFallbackEnabled = Deno.env.get("NEW_STAR_MODEL_ENABLE_FALLBACK") === "true" && newStarModel.configured;
    const hasOpenAI = Boolean(firstSecret("OPENAI_API_KEY", "OPENAI_KEY"));
    const hasGemini = Boolean(firstSecret("GEMINI_API_KEY", "GOOGLE_GEMINI_API_KEY", "GOOGLE_AI_API_KEY", "GOOGLE_API_KEY"));
    const hasOpenRouter = Boolean(firstSecret("OPENROUTER_API_KEY", "OPENROUTER_KEY"));
    const imageConfigured = hasGemini || hasOpenRouter || hasOpenAI;
    const speechConfigured = hasGemini || hasOpenAI;
    return json({
      ok: true,
      status: true,
      providers: {
        newStarTestModelConfigured: newStarModel.configured,
        newStarTestFallbackEnabled,
        openai: hasOpenAI,
        gemini: hasGemini,
        openrouter: hasOpenRouter
      },
      capabilities: {
        chat: { supported: true, configured: newStarTestFallbackEnabled || hasOpenAI || hasGemini || hasOpenRouter },
        imageGeneration: { supported: true, configured: imageConfigured },
        speechGeneration: { supported: true, configured: speechConfigured },
        speechTranscription: { supported: true, configured: speechConfigured },
        videoGeneration: { supported: true, configured: hasGemini, provider: "gemini", model: Deno.env.get("NEW_STAR_VIDEO_MODEL")?.trim() || "gemini-omni-1.2-flash", reason: "A configured key does not verify model access, paid-tier eligibility, quota, or successful video generation." },
        webSearch: { supported: false, configured: false, reason: "A live search provider adapter is not implemented in this gateway yet." }
      },
      health: {
        newStarHostedModel: newStarModel,
        note: "Configured means a server-side key or endpoint is present; it does not prove provider billing, quota, reachability, or successful generation. The hosted model check verifies the authenticated model-list endpoint only."
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

  const newStarModelUrl = Deno.env.get("NEW_STAR_MODEL_BASE_URL")?.trim() || "";
  const newStarModelName = Deno.env.get("NEW_STAR_MODEL_NAME")?.trim() || "";
  const newStarModelKey = Deno.env.get("NEW_STAR_MODEL_API_KEY")?.trim() || "local-no-key";
  const enableLocalTestFallback = Deno.env.get("NEW_STAR_MODEL_ENABLE_FALLBACK") === "true";
  const providers = [
    { name: "gemini", keys: secretCandidates("GEMINI_API_KEY", "GOOGLE_GEMINI_API_KEY", "GOOGLE_AI_API_KEY", "GOOGLE_API_KEY"), run: callGemini },
    { name: "openai", keys: secretCandidates("OPENAI_API_KEY", "OPENAI_KEY"), run: callOpenAI },
    { name: "openrouter", keys: secretCandidates("OPENROUTER_API_KEY", "OPENROUTER_KEY"), run: callOpenRouter },
    ...(enableLocalTestFallback && newStarModelUrl && newStarModelName ? [{ name: "new-star-local-test-model", keys: [newStarModelKey], run: callNewStarHostedModel }] : [])
  ].filter((p): p is { name: string; keys: string[]; run: (key: string, messages: ChatMessage[], systemPrompt: string) => Promise<string> } => p.keys.length > 0);

  if (providers.length === 0) {
    return json({
      ok: false,
      error: "No AI provider is configured on the server yet.",
      code: "AI_PROVIDERS_NOT_CONFIGURED"
    }, 503, headers);
  }

  const baseSystemPrompt = body.mode === "help" ? NEW_STAR_HELP_SYSTEM_PROMPT : NEW_STAR_AI_SYSTEM_PROMPT;
  let selectedLanguageCode = typeof body.language === "string" ? body.language : "";
  if (!AI_LANGUAGE_NAMES[selectedLanguageCode]) {
    try {
      const { data: savedPreference } = await userClient.from("user_preferences").select("preferences").eq("user_id", userData.user.id).maybeSingle();
      const savedLanguage = savedPreference?.preferences?.language;
      if (typeof savedLanguage === "string" && AI_LANGUAGE_NAMES[savedLanguage]) selectedLanguageCode = savedLanguage;
    } catch { /* Use the language detected from the user's message when no saved preference is available. */ }
  }
  const selectedLanguage = AI_LANGUAGE_NAMES[selectedLanguageCode] || "";
  const systemPrompt = selectedLanguage
    ? baseSystemPrompt + "\n\nLANGUAGE SETTING: The app user has selected " + selectedLanguage + ". Write every user-facing response entirely in " + selectedLanguage + " using its native script. Do not answer in Hindi or English unless that is the selected language. Keep names, code, URLs, and technical identifiers unchanged where necessary. If the user explicitly asks for translation into another language, follow that request for that answer only."
    : baseSystemPrompt;

  const failedProviders: string[] = [];
  for (const provider of providers) {
    for (let keyIndex = 0; keyIndex < provider.keys.length; keyIndex++) {
      try {
        const answer = await provider.run(provider.keys[keyIndex], messages, systemPrompt);
        return json({ ok: true, provider: provider.name, answer }, 200, headers);
      } catch (error) {
        // Never log prompts, provider responses, or API keys.
        const reason = error instanceof Error ? error.message : "UNKNOWN";
        failedProviders.push(`${provider.name}[${keyIndex + 1}]:${reason}`);
        console.error("AI provider failed:", provider.name, keyIndex + 1, reason);
      }
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
