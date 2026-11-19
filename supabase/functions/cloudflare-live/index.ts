import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const ALLOWED_ORIGINS = new Set([
  "https://new-star-delta.vercel.app",
  "https://new-star-rkdaifilms-8858.vercel.app",
  "https://new-star-nepal.netlify.app",
]);

function cors(origin: string | null) {
  const allowed = origin && ALLOWED_ORIGINS.has(origin) ? origin : "https://new-star-delta.vercel.app";
  return {
    "Access-Control-Allow-Origin": allowed,
    "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    "Vary": "Origin",
    "Cache-Control": "no-store",
  };
}
function json(body: unknown, status: number, headers: Record<string,string>) {
  return new Response(JSON.stringify(body), { status, headers: { ...headers, "Content-Type": "application/json" } });
}

Deno.serve(async (req: Request) => {
  const headers = cors(req.headers.get("origin"));
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers });
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405, headers);
  const origin = req.headers.get("origin");
  if (!origin || !ALLOWED_ORIGINS.has(origin)) return json({ error: "Origin not allowed" }, 403, headers);

  const url = Deno.env.get("SUPABASE_URL");
  const anon = Deno.env.get("SUPABASE_ANON_KEY");
  const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  const accountId = Deno.env.get("CLOUDFLARE_ACCOUNT_ID");
  const token = Deno.env.get("CLOUDFLARE_STREAM_TOKEN");
  if (!url || !anon || !service) return json({ error: "Server configuration incomplete." }, 503, headers);
  if (!accountId || !token) return json({ error: "Cloudflare Stream is not configured. Ask the project owner to check the server-side Cloudflare secrets." }, 503, headers);

  const authorization = req.headers.get("authorization") || "";
  if (!authorization.startsWith("Bearer ")) return json({ error: "Login required." }, 401, headers);
  const userClient = createClient(url, anon, { auth: { persistSession: false, autoRefreshToken: false }, global: { headers: { Authorization: authorization } } });
  const { data: auth, error: authError } = await userClient.auth.getUser();
  if (authError || !auth.user) return json({ error: "Valid login required." }, 401, headers);

  let body: { action?: string; room_title?: string; room_id?: string };
  try { body = await req.json(); } catch { return json({ error: "Invalid JSON." }, 400, headers); }
  const action = body.action;
  if (!["prepare", "confirm", "stop"].includes(String(action))) return json({ error: "Unsupported action." }, 400, headers);

  const admin = createClient(url, service, { auth: { persistSession: false, autoRefreshToken: false } });
  const creatorId = auth.user.id;

  if (action === "stop") {
    if (!body.room_id) return json({ error: "Room ID is required." }, 400, headers);
    const { data, error } = await admin.from("live_rooms").update({
      is_live: false, ended_at: new Date().toISOString(), recording_status: "not_supported",
    }).eq("id", body.room_id).eq("creator_id", creatorId).select("id").maybeSingle();
    if (error) return json({ error: "Could not end the live room." }, 500, headers);
    if (!data) return json({ error: "Live room not found." }, 404, headers);
    return json({ ok: true }, 200, headers);
  }

  if (action === "confirm") {
    if (!body.room_id) return json({ error: "Room ID is required." }, 400, headers);
    const { data, error } = await admin.from("live_rooms").update({
      is_live: true, started_at: new Date().toISOString(), ended_at: null, recording_status: "not_supported",
    }).eq("id", body.room_id).eq("creator_id", creatorId).select("id").maybeSingle();
    if (error) return json({ error: "Could not mark the live room active." }, 500, headers);
    if (!data) return json({ error: "Live room not found." }, 404, headers);
    return json({ ok: true }, 200, headers);
  }

  const title = typeof body.room_title === "string" ? body.room_title.trim().slice(0, 120) : "";
  if (!title) return json({ error: "Enter a live room title." }, 400, headers);

  const { data: existing, error: readError } = await admin.from("live_rooms")
    .select("id,stream_room_name,is_live").eq("creator_id", creatorId).maybeSingle();
  if (readError) return json({ error: "Could not load your live room." }, 500, headers);
  if (existing?.is_live) return json({ error: "You already have an active live room. End it before starting another." }, 409, headers);

  let input: any = null;
  if (existing?.stream_room_name) {
    try {
      const get = await fetch(`https://api.cloudflare.com/client/v4/accounts/${accountId}/stream/live_inputs/${encodeURIComponent(existing.stream_room_name)}`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      if (get.ok) {
        const payload = await get.json();
        if (payload?.success && payload?.result?.enabled !== false) input = payload.result;
      }
    } catch (e) { console.error("Cloudflare live input lookup failed:", e); }
  }

  if (!input) {
    let response: Response;
    try {
      response = await fetch(`https://api.cloudflare.com/client/v4/accounts/${accountId}/stream/live_inputs`, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Type": "application/json",
          "Idempotency-Key": crypto.randomUUID(),
        },
        body: JSON.stringify({
          enabled: true,
          defaultCreator: creatorId,
          meta: { app: "New Star", creatorId, roomTitle: title },
        }),
      });
    } catch (e) {
      console.error("Cloudflare live input request failed:", e);
      return json({ error: "Could not reach Cloudflare Stream." }, 502, headers);
    }
    const payload = await response.json().catch(() => null);
    if (!response.ok || !payload?.success || !payload?.result?.uid) {
      console.error("Cloudflare live input creation failed:", response.status, payload?.errors || payload);
      return json({ error: response.status === 403 ? "Cloudflare denied live-input creation. Check that the token has Stream Write permission and Live is enabled on the account." : "Cloudflare could not create a live input.", providerStatus: response.status }, 502, headers);
    }
    input = payload.result;
  }

  const whipUrl = input?.webRTC?.url;
  const whepUrl = input?.webRTCPlayback?.url;
  if (typeof whipUrl !== "string" || typeof whepUrl !== "string") {
    return json({ error: "Cloudflare did not return WebRTC broadcast and playback URLs. Confirm Live WebRTC is enabled for this account." }, 502, headers);
  }

  const roomRecord = {
    creator_id: creatorId,
    room_title: title,
    is_live: false,
    stream_provider: "cloudflare",
    stream_room_name: input.uid,
    stream_playback_url: whepUrl,
    recording_status: "not_supported",
    started_at: null,
    ended_at: null,
    stream_mode: "camera",
    moderation_status: "active",
  };
  const { data: room, error: saveError } = await admin.from("live_rooms")
    .upsert(roomRecord, { onConflict: "creator_id" }).select("id").single();
  if (saveError || !room) {
    console.error("Could not save live room metadata:", saveError?.message);
    return json({ error: "Cloudflare input was created, but the live room could not be saved. Contact support before retrying." }, 500, headers);
  }

  // WHIP is a broadcast credential. Return it only to this authenticated creator; never persist it.
  return json({ ok: true, room_id: room.id, whip_url: whipUrl }, 200, headers);
});