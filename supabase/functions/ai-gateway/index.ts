import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const ALLOWED_ORIGINS = new Set([
  "https://new-star-nepal.netlify.app",
  "https://star-delta.vercel.app",
  "https://new-star-delta.vercel.app",
  "https://new-star-rkdaifilms-8858.vercel.app",
  "https://new-star.rkdaifilms.workers.dev",
  "https://new-star-git-new-star-repair-pass-1-rkdaifilms-8858.vercel.app",
  "https://new-star-git-new-star-ai-engine-foundation-rkdaifilms-8858.vercel.app"
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
  const models = imageSize === "1K" ? ["gemini-3.1-flash-lite-image", "gemini-3.1-flash-image"] : ["gemini-3.1-flash-image"];
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
            generationConfig: { responseModalities: ["TEXT", "IMAGE"], imageConfig: { aspectRatio, imageSize: model === "gemini-3.1-flash-lite-image" ? "1K" : imageSize } }
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
    body: JSON.stringify({ model: "gpt-image-1", prompt, size: aspectRatio === "16:9" || aspectRatio === "4:3" ? "1536x1024" : aspectRatio === "9:16" || aspectRatio === "3:4" ? "1024x1536" : "1024x1024", n: 1 })
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
      model: "google/gemini-3.1-flash-lite-image",
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

async function callNewStarHostedModel(key: string, messages: ChatMessage[], systemPrompt: string) {
  const configuredEndpoint = Deno.env.get("NEW_STAR_MODEL_BASE_URL")?.trim() || "";
  const model = Deno.env.get("NEW_STAR_MODEL_NAME")?.trim() || "";
  if (!configuredEndpoint || !model) throw new Error("NEW_STAR_MODEL_NOT_CONFIGURED");

  let endpoint: URL;
  try {
    endpoint = new URL(configuredEndpoint);
  } catch {
    throw new Error("NEW_STAR_MODEL_INVALID_ENDPOINT");
  }
  if (endpoint.protocol !== "https:" || endpoint.username || endpoint.password || endpoint.search || endpoint.hash) {
    throw new Error("NEW_STAR_MODEL_ENDPOINT_MUST_BE_HTTPS");
  }
  const base = endpoint.toString().replace(/\/$/, "");
  const requestUrl = base.endsWith("/chat/completions") ? base : base + "/chat/completions";
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (key && key !== "local-no-key") headers.Authorization = "Bearer " + key;

  const response = await fetchWithTimeout(requestUrl, {
    method: "POST",
    headers,
    body: JSON.stringify({
      model,
      messages: [{ role: "system", content: systemPrompt }, ...messages.map(m => ({ role: m.role, content: m.content }))],
      temperature: 0.4,
      max_tokens: 1400
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
  if (!configuredEndpoint || !model) {
    return { configured: false, reachable: false, modelAvailable: false };
  }

  try {
    const endpoint = new URL(configuredEndpoint);
    if (endpoint.protocol !== "https:" || endpoint.username || endpoint.password || endpoint.search || endpoint.hash) {
      return { configured: true, reachable: false, modelAvailable: false };
    }
    const base = endpoint.toString().replace(/\/$/, "");
    const modelsUrl = base.endsWith("/models") ? base : base.replace(/\/v1$/, "") + "/v1/models";
    const headers: Record<string, string> = {};
    if (key) headers.Authorization = "Bearer " + key;
    const response = await fetchWithTimeout(modelsUrl, {
      method: "GET",
      headers,
      redirect: "error"
    }, 5000);
    if (!response.ok) {
      await response.body?.cancel().catch(() => {});
      return { configured: true, reachable: false, modelAvailable: false };
    }
    const data = await response.json().catch(() => ({}));
    const models = Array.isArray(data?.data) ? data.data : [];
    const modelAvailable = models.some((item: any) => item?.id === model);
    return { configured: true, reachable: true, modelAvailable };
  } catch {
    return { configured: true, reachable: false, modelAvailable: false };
  }
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
        form.append("model", "gpt-4o-mini-transcribe");
        form.append("prompt", "Transcribe the speech exactly in the original language. Do not translate, summarize, or add words.");
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
            if (response.status === 401) break; continue;
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

  if (body?.action === "research") {
    const query = typeof body.query === "string" ? body.query.trim() : "";
    if (!query || query.length > 1800) return json({ ok: false, error: "Enter a research question of up to 1,800 characters." }, 400, headers);
    const geminiKeys = secretCandidates("GEMINI_API_KEY", "GOOGLE_GEMINI_API_KEY", "GOOGLE_AI_API_KEY", "GOOGLE_API_KEY");
    if (!geminiKeys.length) return json({ ok: false, error: "Live Research needs a configured Gemini server key. No live search was performed." }, 503, headers);
    const researchAdmin = createClient(supabaseUrl, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });
    const { data: researchAllowed, error: researchLimitError } = await researchAdmin.rpc("consume_ai_gateway_rate_limit", { p_user_id: userData.user.id, p_limit: 20 });
    if (researchLimitError || researchAllowed !== true) return json({ ok: false, error: researchLimitError ? "Security rate-limit check unavailable." : "Too many requests. Please wait one minute." }, researchLimitError ? 503 : 429, headers);
    const failures: string[] = [];
    for (let keyIndex = 0; keyIndex < geminiKeys.length; keyIndex++) {
      for (const model of ["gemini-3.8-flash", "gemini-3.5-flash-lite"]) {
        try {
          const response = await fetchWithTimeout("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent", {
            method: "POST",
            headers: { "x-goog-api-key": geminiKeys[keyIndex], "Content-Type": "application/json" },
            body: JSON.stringify({
              contents: [{ role: "user", parts: [{ text: "Research this question using Google Search. Give a clear, accurate answer in the same language as the question. Distinguish confirmed facts from uncertainty, include dates where relevant, and do not invent facts. Question: " + query }] }],
              tools: [{ google_search: {} }],
              generationConfig: { temperature: 0.3, maxOutputTokens: 1800 }
            })
          }, 60000);
          const data = await response.json().catch(() => ({}));
          if (!response.ok) {
            const reason = providerHttpError(response.status, data);
            failures.push(model + ":" + reason);
            if (response.status === 404) continue;
            break;
          }
          const candidate = data?.candidates?.[0];
          const answer = candidate?.content?.parts?.map((part: any) => part.text || "").join("").trim();
          if (!answer) { failures.push(model + ":EMPTY_RESPONSE"); continue; }
          const chunks = candidate?.groundingMetadata?.groundingChunks || candidate?.grounding_metadata?.grounding_chunks || [];
          const seen = new Set<string>();
          const sources = chunks.map((chunk: any) => {
            const web = chunk?.web || {};
            const url = typeof web.uri === "string" ? web.uri : "";
            if (!/^https:\/\//i.test(url) || seen.has(url)) return null;
            seen.add(url);
            return { title: String(web.title || url).slice(0, 240), url };
          }).filter(Boolean).slice(0, 8);
          return json({ ok: true, answer, sources, provider: "gemini-google-search", grounded: sources.length > 0 }, 200, headers);
        } catch (error) {
          failures.push(model + ":" + (error instanceof Error ? error.message : "RESEARCH_FAILED"));
        }
      }
    }
    console.error("New Star research failed:", failures.join("; ").slice(0, 1000));
    return json({ ok: false, error: "Live Research is temporarily unavailable. Check Gemini access and Google Search grounding availability.", attempted: failures }, 502, headers);
  }

  if (body?.action === "vision") {
    const prompt = typeof body.prompt === "string" ? body.prompt.trim() : "Describe this image and identify its main contents.";
    const image = body.image;
    const mimeType = typeof body.mimeType === "string" ? body.mimeType.toLowerCase() : "";
    if (typeof image !== "string" || image.length < 100 || image.length > 9000000 || !["image/png", "image/jpeg", "image/webp"].includes(mimeType)) {
      return json({ ok: false, error: "Choose a PNG, JPEG, or WebP image smaller than 6 MB." }, 400, headers);
    }
    if (prompt.length > 1800) return json({ ok: false, error: "Image question is too long." }, 400, headers);
    const visionAdmin = createClient(supabaseUrl, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });
    const { data: visionAllowed, error: visionLimitError } = await visionAdmin.rpc("consume_ai_gateway_rate_limit", { p_user_id: userData.user.id, p_limit: 20 });
    if (visionLimitError || visionAllowed !== true) return json({ ok: false, error: visionLimitError ? "Security rate-limit check unavailable." : "Too many requests. Please wait one minute." }, visionLimitError ? 503 : 429, headers);
    const geminiKeys = secretCandidates("GEMINI_API_KEY", "GOOGLE_GEMINI_API_KEY", "GOOGLE_AI_API_KEY", "GOOGLE_API_KEY");
    const openaiKeys = secretCandidates("OPENAI_API_KEY", "OPENAI_KEY");
    const failures: string[] = [];
    for (let keyIndex = 0; keyIndex < geminiKeys.length; keyIndex++) {
      for (const model of ["gemini-3.8-flash", "gemini-3.5-flash-lite"]) {
        try {
          const response = await fetchWithTimeout("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent", {
            method: "POST",
            headers: { "x-goog-api-key": geminiKeys[keyIndex], "Content-Type": "application/json" },
            body: JSON.stringify({ contents: [{ role: "user", parts: [{ text: prompt || "Describe this image accurately. Do not guess details that are not visible." }, { inline_data: { mime_type: mimeType, data: image } }] }], generationConfig: { temperature: 0.2, maxOutputTokens: 1400 } })
          }, 60000);
          const data = await response.json().catch(() => ({}));
          if (!response.ok) {
            failures.push(model + ":" + providerHttpError(response.status, data));
            if (response.status === 404) continue;
            break;
          }
          const answer = data?.candidates?.[0]?.content?.parts?.map((part: any) => part.text || "").join("").trim();
          if (answer) return json({ ok: true, answer, provider: "gemini-vision" }, 200, headers);
          failures.push(model + ":EMPTY_RESPONSE");
        } catch (error) { failures.push(model + ":" + (error instanceof Error ? error.message : "VISION_FAILED")); }
      }
    }
    for (let keyIndex = 0; keyIndex < openaiKeys.length; keyIndex++) {
      try {
        const response = await fetchWithTimeout("https://api.openai.com/v1/chat/completions", {
          method: "POST",
          headers: { "Authorization": "Bearer " + openaiKeys[keyIndex], "Content-Type": "application/json" },
          body: JSON.stringify({ model: "gpt-4o-mini", messages: [{ role: "user", content: [{ type: "text", text: prompt || "Describe this image accurately. Do not guess details that are not visible." }, { type: "image_url", image_url: { url: "data:" + mimeType + ";base64," + image } }] }], max_tokens: 1400 })
        }, 60000);
        const data = await response.json().catch(() => ({}));
        if (!response.ok) { failures.push("openai:" + providerHttpError(response.status, data)); continue; }
        const answer = data?.choices?.[0]?.message?.content;
        if (typeof answer === "string" && answer.trim()) return json({ ok: true, answer: answer.trim(), provider: "openai-vision" }, 200, headers);
        failures.push("openai:EMPTY_RESPONSE");
      } catch (error) { failures.push("openai:" + (error instanceof Error ? error.message : "VISION_FAILED")); }
    }
    return json({ ok: false, error: "Image understanding is unavailable. Check configured vision providers.", attempted: failures }, 502, headers);
  }

  if (body?.action === "image") {
    const prompt = typeof body.prompt === "string" ? body.prompt.trim() : "";
    const aspectRatio = requestedImageAspectRatio(prompt, body.aspectRatio);
    const imageSize = ["1K", "2K", "4K"].includes(String(body.imageSize || "")) ? String(body.imageSize) : "1K";
    const requestedLanguage = String(body.language || "");
    const imageLanguage = AI_LANGUAGE_NAMES[requestedLanguage] || "";
    const localizedImagePrompt = imageLanguage
      ? prompt + "\nIf visible text is requested, write it only in " + imageLanguage + " using its native script."
      : requestedLanguage === "auto"
        ? prompt + "\nIf visible text is requested, use the same language and writing system as the user's image description. Do not translate the requested wording."
        : prompt;
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
    return json({
      ok: true,
      status: true,
      providers: {
        newStarHostedModel: newStarModel.configured,
        openai: Boolean(firstSecret("OPENAI_API_KEY", "OPENAI_KEY")),
        gemini: Boolean(firstSecret("GEMINI_API_KEY", "GOOGLE_GEMINI_API_KEY", "GOOGLE_AI_API_KEY", "GOOGLE_API_KEY")),
        openrouter: Boolean(firstSecret("OPENROUTER_API_KEY", "OPENROUTER_KEY")),
        research: Boolean(firstSecret("GEMINI_API_KEY", "GOOGLE_GEMINI_API_KEY", "GOOGLE_AI_API_KEY", "GOOGLE_API_KEY"))
      },
      health: { newStarHostedModel: newStarModel }
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
  const providers = [
    ...(newStarModelUrl && newStarModelName ? [{ name: "new-star-hosted-model", keys: [newStarModelKey], run: callNewStarHostedModel }] : []),
    { name: "gemini", keys: secretCandidates("GEMINI_API_KEY", "GOOGLE_GEMINI_API_KEY", "GOOGLE_AI_API_KEY", "GOOGLE_API_KEY"), run: callGemini },
    { name: "openai", keys: secretCandidates("OPENAI_API_KEY", "OPENAI_KEY"), run: callOpenAI },
    { name: "openrouter", keys: secretCandidates("OPENROUTER_API_KEY", "OPENROUTER_KEY"), run: callOpenRouter }
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
  const automaticLanguage = !selectedLanguageCode || selectedLanguageCode === "auto";
  if (!automaticLanguage && !AI_LANGUAGE_NAMES[selectedLanguageCode]) {
    try {
      const { data: savedPreference } = await userClient.from("user_preferences").select("preferences").eq("user_id", userData.user.id).maybeSingle();
      const savedLanguage = savedPreference?.preferences?.language;
      if (typeof savedLanguage === "string" && AI_LANGUAGE_NAMES[savedLanguage]) selectedLanguageCode = savedLanguage;
    } catch { /* Continue with automatic language detection. */ }
  }
  const selectedLanguage = AI_LANGUAGE_NAMES[selectedLanguageCode] || "";
  const systemPrompt = selectedLanguage
    ? baseSystemPrompt + "\n\nLANGUAGE SETTING: The app user explicitly selected " + selectedLanguage + ". Write every user-facing response entirely in " + selectedLanguage + " using its native script, unless their latest message explicitly requests another language."
    : baseSystemPrompt + "\n\nAUTOMATIC LANGUAGE MODE: Detect the language and script of the LATEST user message (including romanized Nepali, Hindi, and other languages). Reply naturally in that same language and script. Do not default to Hindi or English because of the app interface or previous messages. If the user switches language, switch immediately. If the message is short or ambiguous, ask a brief clarification in the language most likely used."; 


  // Load only conversations owned by the signed-in user. New conversations are
  // created only after a provider returns a successful answer, avoiding empty rows.
  let conversationId = typeof body.conversationId === "string" ? body.conversationId : "";
  if (conversationId && !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(conversationId)) {
    return json({ ok: false, error: "Invalid conversation ID." }, 400, headers);
  }
  if (conversationId) {
    const { data: ownedConversation, error: conversationReadError } = await admin
      .from("new_star_ai_conversations").select("id").eq("id", conversationId).eq("user_id", userData.user.id).maybeSingle();
    if (conversationReadError || !ownedConversation) return json({ ok: false, error: "Conversation not found." }, 404, headers);
    const { data: previousMessages, error: historyError } = await admin
      .from("new_star_ai_messages").select("role,content").eq("conversation_id", conversationId).eq("user_id", userData.user.id)
      .order("created_at", { ascending: false }).limit(10);
    if (historyError) return json({ ok: false, error: "Could not load conversation history." }, 503, headers);
    const previous = (previousMessages || []).reverse().map((m: any) => ({ role: m.role, content: m.content })) as ChatMessage[];
    messages.splice(0, messages.length, ...previous, ...messages.slice(-1));
    while (messages.length > 10) messages.shift();
  }

  const failedProviders: string[] = [];
  for (const provider of providers) {
    for (let keyIndex = 0; keyIndex < provider.keys.length; keyIndex++) {
      try {
        const answer = await provider.run(provider.keys[keyIndex], messages, systemPrompt);
        const latestUserMessage = messages[messages.length - 1];
        if (!conversationId) {
          const title = latestUserMessage.content.slice(0, 80) || "New conversation";
          const { data: createdConversation, error: createConversationError } = await admin
            .from("new_star_ai_conversations").insert({ user_id: userData.user.id, title }).select("id").single();
          if (createConversationError || !createdConversation?.id) {
            console.error("New Star AI conversation creation failed:", createConversationError?.code || "DB_ERROR");
            return json({ ok: true, provider: provider.name, answer, memorySaved: false }, 200, headers);
          }
          conversationId = createdConversation.id;
        }
        const { error: saveMessagesError } = await admin.from("new_star_ai_messages").insert([
          { conversation_id: conversationId, user_id: userData.user.id, role: "user", content: latestUserMessage.content },
          { conversation_id: conversationId, user_id: userData.user.id, role: "assistant", content: answer, provider: provider.name }
        ]);
        if (saveMessagesError) {
          console.error("New Star AI message persistence failed:", saveMessagesError.code || "DB_ERROR");
          return json({ ok: true, provider: provider.name, answer, conversationId, memorySaved: false }, 200, headers);
        }
        await admin.from("new_star_ai_conversations").update({ updated_at: new Date().toISOString() }).eq("id", conversationId).eq("user_id", userData.user.id);
        return json({ ok: true, provider: provider.name, answer, conversationId, memorySaved: true }, 200, headers);
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
