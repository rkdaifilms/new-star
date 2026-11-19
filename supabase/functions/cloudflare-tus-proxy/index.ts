import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const ALLOWED_ORIGINS = new Set([
  "https://new-star-rkdaifilms-8858.vercel.app",
  "https://new-star-delta.vercel.app",
  "https://new-star-nepal.netlify.app",
]);
const MAX_FILE_BYTES = 5 * 1024 * 1024 * 1024;
const MAX_DURATION_SECONDS = 3 * 60 * 60;

function corsHeaders(origin: string | null): Record<string, string> {
  const allowed = origin && ALLOWED_ORIGINS.has(origin) ? origin : "https://new-star-delta.vercel.app";
  return {
    "Access-Control-Allow-Origin": allowed,
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    "Access-Control-Allow-Headers": "authorization, apikey, x-client-info, content-type, tus-resumable, upload-length, upload-metadata, x-http-method-override",
    "Access-Control-Expose-Headers": "Location, stream-media-id, Tus-Resumable, Upload-Offset, Upload-Length",
    "Access-Control-Max-Age": "86400",
    "Vary": "Origin",
  };
}

function json(body: unknown, status: number, cors: Record<string, string>) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...cors, "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
}

function decodeMetadataValue(value: string): string {
  try {
    return atob(value);
  } catch {
    return "";
  }
}

Deno.serve(async (req: Request) => {
  const origin = req.headers.get("origin");
  const cors = corsHeaders(origin);

  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405, cors);
  if (!origin || !ALLOWED_ORIGINS.has(origin)) return json({ error: "Origin not allowed" }, 403, cors);

  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const supabaseAnonKey = Deno.env.get("SUPABASE_ANON_KEY");
  const accountId = Deno.env.get("CLOUDFLARE_ACCOUNT_ID");
  const streamToken = Deno.env.get("CLOUDFLARE_STREAM_TOKEN");

  if (!supabaseUrl || !supabaseAnonKey || !accountId || !streamToken) {
    return json({ error: "Video upload service is not configured yet." }, 503, cors);
  }

  const authorization = req.headers.get("authorization");
  if (!authorization?.startsWith("Bearer ")) {
    return json({ error: "Login required." }, 401, cors);
  }

  const supabase = createClient(supabaseUrl, supabaseAnonKey, {
    global: { headers: { Authorization: authorization } },
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data: authData, error: authError } = await supabase.auth.getUser();
  if (authError || !authData.user) return json({ error: "Valid login required." }, 401, cors);

  const tusVersion = req.headers.get("Tus-Resumable");
  const uploadLengthHeader = req.headers.get("Upload-Length");
  const uploadMetadata = req.headers.get("Upload-Metadata") || "";

  if (tusVersion !== "1.0.0") return json({ error: "Unsupported resumable upload protocol." }, 400, cors);
  if (!uploadLengthHeader || !/^\d+$/.test(uploadLengthHeader)) return json({ error: "Upload-Length is required." }, 400, cors);

  const uploadLength = Number(uploadLengthHeader);
  if (!Number.isSafeInteger(uploadLength) || uploadLength <= 0 || uploadLength > MAX_FILE_BYTES) {
    return json({ error: "Maximum upload size is 5 GiB per video." }, 413, cors);
  }

  const metadata = new Map<string, string>();
  for (const part of uploadMetadata.split(",")) {
    const [key, value = ""] = part.trim().split(/\s+/, 2);
    if (key) metadata.set(key, value);
  }

  const durationEncoded = metadata.get("maxDurationSeconds");
  const durationSeconds = durationEncoded ? Number(decodeMetadataValue(durationEncoded)) : NaN;
  if (!Number.isInteger(durationSeconds) || durationSeconds < 1 || durationSeconds > MAX_DURATION_SECONDS) {
    return json({ error: "Video duration must be between 1 second and 3 hours." }, 400, cors);
  }

  const expiry = new Date(Date.now() + 60 * 60 * 1000).toISOString();
  metadata.set("expiry", btoa(expiry));
  metadata.set("requiresignedurls", "");
  const safeMetadata = [...metadata.entries()]
    .map(([key, value]) => value ? key + " " + value : key)
    .join(",");

  const endpoint = `https://api.cloudflare.com/client/v4/accounts/${accountId}/stream?direct_user=true`;
  let upstream: Response;
  try {
    upstream = await fetch(endpoint, {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${streamToken}`,
        "Tus-Resumable": "1.0.0",
        "Upload-Length": String(uploadLength),
        "Upload-Metadata": safeMetadata,
        "Upload-Creator": authData.user.id.slice(0, 64),
      },
    });
  } catch {
    return json({ error: "Cloudflare could not be reached. Please retry." }, 502, cors);
  }

  if (!upstream.ok) {
    const detail = await upstream.text();
    console.error("Cloudflare TUS initialization failed:", upstream.status, detail.slice(0, 1000));
    return json({ error: "Cloudflare could not create the upload session.", providerStatus: upstream.status }, 502, cors);
  }

  const location = upstream.headers.get("Location");
  const streamMediaId = upstream.headers.get("stream-media-id");
  if (!location || !streamMediaId) {
    return json({ error: "Cloudflare returned an incomplete upload session." }, 502, cors);
  }

  return new Response(null, {
    status: upstream.status,
    headers: {
      ...cors,
      "Location": location,
      "stream-media-id": streamMediaId,
      "Tus-Resumable": "1.0.0",
      "Cache-Control": "no-store",
    },
  });
});
