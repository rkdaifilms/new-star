import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const ALLOWED_ORIGINS = new Set([
  "https://new-star-git-new-star-repair-pass-1-rkdaifilms-8858.vercel.app",
  "https://new-star-delta.vercel.app",
  "https://new-star-rkdaifilms-8858.vercel.app",
  "https://new-star-nepal.netlify.app",
  "https://new-star.rkdaifilms.workers.dev",
]);

function cors(origin: string | null) {
  const allowed = origin && ALLOWED_ORIGINS.has(origin) ? origin : "https://new-star.rkdaifilms.workers.dev";
  return {
    "Access-Control-Allow-Origin": allowed,
    "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    "Vary": "Origin",
    "Cache-Control": "no-store",
  };
}

function json(body: unknown, status: number, headers: Record<string, string>) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...headers, "Content-Type": "application/json" },
  });
}

Deno.serve(async (req: Request) => {
  const headers = cors(req.headers.get("origin"));
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers });
  if (req.method !== "POST") return json({ ok: false, error: "Method not allowed." }, 405, headers);
  const origin = req.headers.get("origin");
  // Native Android clients omit Origin; the verified Supabase JWT below is the access control.\n  if (origin && !ALLOWED_ORIGINS.has(origin)) return json({ ok: false, error: "Origin not allowed." }, 403, headers);

  const url = Deno.env.get("SUPABASE_URL");
  const anon = Deno.env.get("SUPABASE_ANON_KEY");
  const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  const subscribeKey = Deno.env.get("ABLY_SUBSCRIBE_API_KEY");
  if (!url || !anon || !service) return json({ ok: false, error: "Server configuration incomplete." }, 503, headers);
  if (!subscribeKey) return json({ ok: false, error: "Ably Subscribe-only key is not configured in server secrets." }, 503, headers);

  const authorization = req.headers.get("authorization") || "";
  if (!authorization.startsWith("Bearer ")) return json({ ok: false, error: "Login required." }, 401, headers);
  const userClient = createClient(url, anon, {
    auth: { persistSession: false, autoRefreshToken: false },
    global: { headers: { Authorization: authorization } },
  });
  const { data: auth, error: authError } = await userClient.auth.getUser();
  if (authError || !auth.user) return json({ ok: false, error: "Valid login required." }, 401, headers);

  let body: { room_id?: unknown };
  try { body = await req.json(); } catch { return json({ ok: false, error: "Invalid JSON." }, 400, headers); }
  const roomId = typeof body.room_id === "string" ? body.room_id : "";
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(roomId)) {
    return json({ ok: false, error: "Invalid LIVE room ID." }, 400, headers);
  }

  const admin = createClient(url, service, { auth: { persistSession: false, autoRefreshToken: false } });
  const { data: room, error: roomError } = await admin.from("live_rooms")
    .select("id,is_live").eq("id", roomId).eq("is_live", true).maybeSingle();
  if (roomError || !room) return json({ ok: false, error: "Active LIVE room not found." }, 404, headers);

  const separator = subscribeKey.indexOf(":");
  if (separator < 1 || separator === subscribeKey.length - 1) {
    return json({ ok: false, error: "Ably Subscribe-only key format is invalid. Store the complete key name and secret." }, 503, headers);
  }
  const keyName = subscribeKey.slice(0, separator);
  const channel = "new-star-live-" + room.id;
  try {
    const tokenResponse = await fetch("https://rest.ably.io/keys/" + encodeURIComponent(keyName) + "/requestToken", {
      method: "POST",
      headers: {
        "Authorization": "Basic " + btoa(subscribeKey),
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        keyName,
        timestamp: Date.now(),
        ttl: 60 * 60 * 1000,
        clientId: auth.user.id,
        capability: JSON.stringify({ [channel]: ["subscribe"] }),
      }),
    });
    const tokenPayload = await tokenResponse.json().catch(() => ({}));
    if (!tokenResponse.ok || typeof tokenPayload.token !== "string" || !tokenPayload.token) {
      console.warn("Ably token request failed with status:", tokenResponse.status);
      return json({ ok: false, error: "Ably could not issue a restricted LIVE chat token. Check the Subscribe-only API key permissions." }, 502, headers);
    }
    return json({ ok: true, token: tokenPayload.token, expires: tokenPayload.expires }, 200, headers);
  } catch (error) {
    console.warn("Ably token request failed:", error);
    return json({ ok: false, error: "Could not reach Ably token service." }, 502, headers);
  }
});
