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
  const allowed = origin && ALLOWED_ORIGINS.has(origin) ? origin : "https://new-star-delta.vercel.app";
  return {
    "Access-Control-Allow-Origin": allowed,
    "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    "Vary": "Origin",
    "Cache-Control": "no-store",
  };
}
async function cloudflareFetch(url: string, init: RequestInit, candidates: Array<{ name: string; value: string }>): Promise<Response> {
  let last: Response | null = null;
  for (const candidate of candidates) {
    const headers = new Headers(init.headers || {});
    headers.set("Authorization", `Bearer ${candidate.value}`);
    const response = await fetch(url, { ...init, headers });
    if (response.ok) return response;
    last = response;
    if (response.status !== 401 && response.status !== 403) return response;
    console.warn("Cloudflare rejected configured token alias:", candidate.name, response.status);
  }
  return last || new Response("Cloudflare token unavailable", { status: 401 });
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
  const tokenCandidates = [
    { name: "CLOUDFLARE_STREAM_TOKEN", value: Deno.env.get("CLOUDFLARE_STREAM_TOKEN") },
    { name: "CLOUDFLARE_API_TOKEN", value: Deno.env.get("CLOUDFLARE_API_TOKEN") },
    { name: "CLOUDFLARE_STREAM_API_TOKEN", value: Deno.env.get("CLOUDFLARE_STREAM_API_TOKEN") },
  ].filter((item): item is { name: string; value: string } => Boolean(item.value));
  if (!url || !anon || !service) return json({ error: "Server configuration incomplete." }, 503, headers);
  if (!accountId || tokenCandidates.length === 0) return json({ error: "Cloudflare Stream is not configured. Ask the project owner to check the server-side Cloudflare secrets." }, 503, headers);

  const authorization = req.headers.get("authorization") || "";
  if (!authorization.startsWith("Bearer ")) return json({ error: "Login required." }, 401, headers);
  const userClient = createClient(url, anon, { auth: { persistSession: false, autoRefreshToken: false }, global: { headers: { Authorization: authorization } } });
  const { data: auth, error: authError } = await userClient.auth.getUser();
  if (authError || !auth.user) return json({ error: "Valid login required." }, 401, headers);

  let body: { action?: string; room_title?: string; room_id?: string; room_type?: string; stream_mode?: string; seat_capacity?: number; message?: string; reaction?: string };
  try { body = await req.json(); } catch { return json({ error: "Invalid JSON." }, 400, headers); }
  const action = body.action;
  const supportedActions = ["prepare", "confirm", "stop", "live-chat-send", "live-chat-list", "live-react"];
  if (!supportedActions.includes(String(action))) return json({ error: "This LIVE interaction is not enabled on the server yet." }, 501, headers);

  const admin = createClient(url, service, { auth: { persistSession: false, autoRefreshToken: false } });
  const creatorId = auth.user.id;

  async function getActiveRoom(roomId: string) {
    if (!roomId) return { data: null, error: new Error("Room ID is required.") };
    return await admin.from("live_rooms").select("id,creator_id,is_live,room_type,stream_mode")
      .eq("id", roomId).eq("is_live", true).maybeSingle();
  }

  if (action === "live-chat-list") {
    const { data: room, error: roomError } = await getActiveRoom(String(body.room_id || ""));
    if (roomError || !room) return json({ error: "Active LIVE room not found." }, 404, headers);
    const { data, error } = await admin.from("live_room_messages")
      .select("id,sender_id,display_name,message,created_at")
      .eq("room_id", room.id).is("deleted_at", null)
      .order("created_at", { ascending: true }).limit(100);
    if (error) return json({ error: "Could not load LIVE chat." }, 503, headers);
    return json({ ok: true, messages: data || [] }, 200, headers);
  }

  if (action === "live-chat-send") {
    const { data: room, error: roomError } = await getActiveRoom(String(body.room_id || ""));
    if (roomError || !room) return json({ error: "Active LIVE room not found." }, 404, headers);
    const message = typeof body.message === "string" ? body.message.trim() : "";
    if (!message || message.length > 500) return json({ error: "Chat message must contain 1 to 500 characters." }, 400, headers);
    const { data: lastMessage, error: cooldownError } = await admin.from("live_room_messages")
      .select("created_at").eq("room_id", room.id).eq("sender_id", creatorId)
      .order("created_at", { ascending: false }).limit(1).maybeSingle();
    if (cooldownError) return json({ error: "Could not verify chat rate limit." }, 503, headers);
    if (lastMessage?.created_at && Date.now() - new Date(lastMessage.created_at).getTime() < 1200) {
      return json({ error: "Please wait a moment before sending another message." }, 429, headers);
    }
    const { data: profile } = await admin.from("profiles").select("full_name,username")
      .eq("user_id", creatorId).maybeSingle();
    const displayName = String(profile?.full_name || profile?.username || "Creator").trim().slice(0, 80) || "Creator";
    const { data, error } = await admin.from("live_room_messages")
      .insert({ room_id: room.id, sender_id: creatorId, display_name: displayName, message })
      .select("id,sender_id,display_name,message,created_at").single();
    if (error || !data) return json({ error: "Could not send LIVE chat message." }, 503, headers);
    const rootAblyKey = Deno.env.get("ABLY_ROOT_API_KEY");
    if (rootAblyKey) {
      try {
        const channel = "new-star-live-" + room.id;
        const publishResponse = await fetch("https://rest.ably.io/channels/" + encodeURIComponent(channel) + "/messages", {
          method: "POST",
          headers: {
            "Authorization": "Basic " + btoa(rootAblyKey),
            "Content-Type": "application/json",
          },
          body: JSON.stringify({ name: "chat-message", data }),
        });
        if (!publishResponse.ok) console.warn("Ably LIVE chat publish failed with status:", publishResponse.status);
      } catch (publishError) {
        console.warn("Ably LIVE chat publish request failed:", publishError);
      }
    }
    return json({ ok: true, message: data }, 200, headers);
  }

  if (action === "live-react") {
    const { data: room, error: roomError } = await getActiveRoom(String(body.room_id || ""));
    if (roomError || !room) return json({ error: "Active LIVE room not found." }, 404, headers);
    const reaction = ["like", "heart", "clap", "fire"].includes(String(body.reaction)) ? String(body.reaction) : "like";
    const { error } = await admin.from("live_room_reactions").insert({
      room_id: room.id, sender_id: creatorId, reaction
    });
    if (error) return json({ error: "Could not record LIVE reaction." }, 503, headers);
    const { count, error: countError } = await admin.from("live_room_reactions")
      .select("id", { count: "exact", head: true }).eq("room_id", room.id).eq("reaction", reaction);
    if (countError) return json({ error: "Reaction was recorded, but its total could not be loaded." }, 503, headers);
    return json({ ok: true, total: count || 0 }, 200, headers);
  }

  if (action === "stop") {
    if (!body.room_id) return json({ error: "Room ID is required." }, 400, headers);
    const { data, error } = await admin.from("live_rooms").update({
      is_live: false, ended_at: new Date().toISOString(), recording_status: "not_supported",
    }).eq("id", body.room_id).eq("creator_id", creatorId).select("id").maybeSingle();
    if (error) return json({ error: "Could not end the live room." }, 500, headers);
    if (!data) return json({ error: "Live room not found." }, 404, headers);
    return json({ ok: true }, 200, headers);
  }

  // Enforce the 1,000-follower LIVE threshold on the server for prepare and confirm.
  const { count: followerCount, error: followerCountError } = await admin
    .from("followers").select("follower_id", { count: "exact", head: true })
    .eq("following_id", creatorId).neq("follower_id", creatorId);
  if (followerCountError) {
    console.error("LIVE follower eligibility lookup failed:", followerCountError.message);
    return json({ error: "Could not verify LIVE eligibility. Please retry." }, 503, headers);
  }
  if ((followerCount || 0) < 1000) {
    return json({ error: "LIVE unlocks at 1,000 genuine followers.", follower_count: followerCount || 0 }, 403, headers);
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
  const roomType = body.room_type === "gaming" ? "gaming" : "live";
  const streamMode = roomType === "gaming" ? "screen" : "camera";
  const requestedSeats = Number(body.seat_capacity || 1);
  const seatCapacity = [1, 4, 10].includes(requestedSeats) ? requestedSeats : 1;

  const { data: existing, error: readError } = await admin.from("live_rooms")
    .select("id,stream_room_name,is_live").eq("creator_id", creatorId).maybeSingle();
  if (readError) return json({ error: "Could not load your live room." }, 500, headers);
  if (existing?.is_live) return json({ error: "You already have an active live room. End it before starting another." }, 409, headers);

  let input: any = null;
  if (existing?.stream_room_name) {
    try {
      const get = await cloudflareFetch(`https://api.cloudflare.com/client/v4/accounts/${accountId}/stream/live_inputs/${encodeURIComponent(existing.stream_room_name)}`, {}, tokenCandidates);
      if (get.ok) {
        const payload = await get.json();
        if (payload?.success && payload?.result?.enabled !== false) input = payload.result;
      }
    } catch (e) { console.error("Cloudflare live input lookup failed:", e); }
  }

  if (!input) {
    let response: Response;
    try {
      response = await cloudflareFetch(`https://api.cloudflare.com/client/v4/accounts/${accountId}/stream/live_inputs`, {
        method: "POST",
        headers: {
          
          "Content-Type": "application/json",
          "Idempotency-Key": crypto.randomUUID(),
        },
        body: JSON.stringify({ enabled: true, defaultCreator: creatorId, meta: { app: "New Star", creatorId, roomTitle: title } }),
      }, tokenCandidates);
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
    stream_mode: streamMode,
    room_type: roomType,
    seat_capacity: seatCapacity,
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