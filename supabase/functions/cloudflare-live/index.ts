import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { isValidSfuMid } from "./sfu-validation.ts";

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
  // Native Android requests have no browser Origin header; they still must pass the verified Supabase JWT check below.
  if (origin && !ALLOWED_ORIGINS.has(origin)) return json({ error: "Origin not allowed" }, 403, headers);

  const url = Deno.env.get("SUPABASE_URL");
  const anon = Deno.env.get("SUPABASE_ANON_KEY");
  const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  const accountId = Deno.env.get("CLOUDFLARE_ACCOUNT_ID");
  const token = Deno.env.get("CLOUDFLARE_STREAM_TOKEN");
  if (!url || !anon || !service) return json({ error: "Server configuration incomplete." }, 503, headers);

  const authorization = req.headers.get("authorization") || "";
  if (!authorization.startsWith("Bearer ")) return json({ error: "Login required." }, 401, headers);
  const userClient = createClient(url, anon, { auth: { persistSession: false, autoRefreshToken: false }, global: { headers: { Authorization: authorization } } });
  const { data: auth, error: authError } = await userClient.auth.getUser();
  if (authError || !auth.user) return json({ error: "Valid login required." }, 401, headers);

  let body: { action?: string; room_title?: string; room_id?: string; room_type?: string; stream_mode?: string; seat_capacity?: number; message?: string; reaction?: string; invitee_id?: string; invite_id?: string; participant_id?: string; microphone_enabled?: boolean; camera_enabled?: boolean; target_user_id?: string; camera_allowed?: boolean; microphone_allowed?: boolean; moderator?: boolean; match_id?: string; session_id?: string; role?: string; session_description?: { type?: string; sdp?: string }; tracks?: Array<{ mid?: string; trackName?: string; sessionId?: string; location?: string }>; track_names?: string[] };
  try { body = await req.json(); } catch { return json({ error: "Invalid JSON." }, 400, headers); }
  const action = body.action;
  const supportedActions = ["prepare", "confirm", "stop", "live-chat-send", "live-chat-list", "live-react", "live-reaction-counts", "live-invite-create", "live-invites-list", "live-invite-respond", "live-participants-list", "live-seat-request", "live-seat-admit", "live-seat-remove", "live-seat-leave", "list-members", "set-member-permissions", "set-moderator", "remove-member", "pk-join-queue", "pk-status", "pk-leave-queue", "pk-finish", "sfu-health", "sfu-create-session", "sfu-publish", "sfu-subscribe", "sfu-unsubscribe", "sfu-renegotiate", "sfu-close", "sfu-list-pubs", "sfu-abandon"];
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
    return json({ ok: true, message: data }, 200, headers);
  }

  if (action === "live-reaction-counts") {
    const { data: room, error: roomError } = await getActiveRoom(String(body.room_id || ""));
    if (roomError || !room) return json({ error: "Active LIVE room not found." }, 404, headers);
    const reactions = ["like", "heart", "clap", "fire"];
    const results = await Promise.all(reactions.map((reaction) =>
      admin.from("live_room_reactions").select("id", { count: "exact", head: true })
        .eq("room_id", room.id).eq("reaction", reaction)
    ));
    if (results.some((result) => result.error)) {
      return json({ error: "Could not load LIVE reaction totals." }, 503, headers);
    }
    const counts: Record<string, number> = {};
    reactions.forEach((reaction, index) => { counts[reaction] = results[index].count || 0; });
    return json({ ok: true, counts }, 200, headers);
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


  // Guest-seat and invitation actions are performed with the service client only
  // after the caller's JWT has been verified above. No client can assign its own seat.
  if (action === "live-invites-list") {
    const { data, error } = await admin.from("live_room_invites")
      .select("id,room_id,inviter_id,status,expires_at,created_at")
      .eq("invitee_id", creatorId).eq("status", "pending")
      .gt("expires_at", new Date().toISOString())
      .order("created_at", { ascending: false }).limit(20);
    if (error) return json({ error: "Could not load pending LIVE invitations." }, 503, headers);
    const rows = data || [];
    const inviterIds = [...new Set(rows.map((row: any) => String(row.inviter_id)))];
    const roomIds = [...new Set(rows.map((row: any) => String(row.room_id)))];
    const [profilesResult, roomsResult] = await Promise.all([
      inviterIds.length
        ? admin.from("creator_public_profiles").select("user_id,full_name,username,avatar_url").in("user_id", inviterIds)
        : Promise.resolve({ data: [], error: null }),
      roomIds.length
        ? admin.from("live_rooms").select("id,room_title").in("id", roomIds)
        : Promise.resolve({ data: [], error: null }),
    ]);
    if (profilesResult.error || roomsResult.error) {
      return json({ error: "Could not load LIVE invitation details." }, 503, headers);
    }
    const profiles = new Map((profilesResult.data || []).map((p: any) => [p.user_id, p]));
    const rooms = new Map((roomsResult.data || []).map((r: any) => [r.id, r]));
    const invitations = rows.map((row: any) => {
      const profile: any = profiles.get(row.inviter_id) || {};
      const room: any = rooms.get(row.room_id) || {};
      return {
        ...row,
        inviter_name: profile.full_name || profile.username || "New Star Creator",
        inviter_username: profile.username || "",
        inviter_avatar_url: profile.avatar_url || "",
        room_title: room.room_title || "New Star Live",
      };
    });
    return json({ ok: true, invitations }, 200, headers);
  }

  if (action === "live-invite-create") {
    const roomId = String(body.room_id || "");
    const inviteeId = String(body.invitee_id || "");
    if (!roomId || !inviteeId || inviteeId === creatorId) {
      return json({ error: "Valid room and another creator are required." }, 400, headers);
    }
    const { data: room, error: roomError } = await getActiveRoom(roomId);
    if (roomError || !room || room.creator_id !== creatorId) {
      return json({ error: "Only the host of an active room can invite guests." }, 403, headers);
    }
    const { data: invitee, error: inviteeError } = await admin.auth.admin.getUserById(inviteeId);
    if (inviteeError || !invitee?.user) return json({ error: "Creator account not found." }, 404, headers);

    const now = new Date();
    const { data: pendingInvite, error: pendingError } = await admin.from("live_room_invites")
      .select("id,room_id,inviter_id,invitee_id,status,expires_at")
      .eq("room_id", roomId).eq("inviter_id", creatorId).eq("invitee_id", inviteeId)
      .eq("status", "pending").gt("expires_at", now.toISOString()).maybeSingle();
    if (pendingError) return json({ error: "Could not check for an existing invitation." }, 503, headers);
    if (pendingInvite) return json({ ok: true, invite: pendingInvite, already_pending: true }, 200, headers);

    const { count: recentInviteCount, error: rateLimitError } = await admin.from("live_room_invites")
      .select("id", { count: "exact", head: true }).eq("inviter_id", creatorId)
      .gte("created_at", new Date(now.getTime() - 60_000).toISOString());
    if (rateLimitError) return json({ error: "Could not verify invitation rate limit." }, 503, headers);
    if ((recentInviteCount || 0) >= 20) {
      return json({ error: "Invitation limit reached. Wait one minute before inviting more creators." }, 429, headers);
    }

    const { data: invite, error } = await admin.from("live_room_invites").insert({
      room_id: roomId, inviter_id: creatorId, invitee_id: inviteeId, status: "pending",
      expires_at: new Date(now.getTime() + 10 * 60 * 1000).toISOString(),
    }).select("id,room_id,inviter_id,invitee_id,status,expires_at").single();
    if (error || !invite) return json({ error: "Could not send LIVE invitation." }, 503, headers);
    return json({ ok: true, invite }, 200, headers);
  }

  if (action === "live-invite-respond") {
    const inviteId = String(body.invite_id || "");
    const decision = body.message === "accept" ? "accepted" : body.message === "decline" ? "declined" : "";
    if (!inviteId || !decision) return json({ error: "Choose accept or decline for this invitation." }, 400, headers);
    const { data: invite, error: inviteError } = await admin.from("live_room_invites")
      .select("id,room_id,inviter_id,invitee_id,status,expires_at")
      .eq("id", inviteId).eq("invitee_id", creatorId).eq("status", "pending").maybeSingle();
    if (inviteError || !invite) return json({ error: "Invitation not found or already answered." }, 404, headers);
    if (new Date(invite.expires_at).getTime() <= Date.now()) {
      await admin.from("live_room_invites").update({ status: "expired", responded_at: new Date().toISOString() }).eq("id", inviteId);
      return json({ error: "This invitation has expired." }, 410, headers);
    }
    const { data: room } = await getActiveRoom(invite.room_id);
    if (!room) return json({ error: "The LIVE room has ended." }, 410, headers);
    const { data: changed, error } = await admin.from("live_room_invites")
      .update({ status: decision, responded_at: new Date().toISOString() })
      .eq("id", inviteId).eq("invitee_id", creatorId).eq("status", "pending")
      .select("id,status").maybeSingle();
    if (error || !changed) return json({ error: "Invitation response could not be saved." }, 409, headers);
    if (decision === "accepted") {
      const { data: profile } = await admin.from("profiles").select("full_name,username")
        .eq("user_id", creatorId).maybeSingle();
      const displayName = String(profile?.full_name || profile?.username || "Creator").trim().slice(0, 80) || "Creator";
      const { data: existingParticipant, error: lookupError } = await admin.from("live_room_participants")
        .select("id,status,seat_number").eq("room_id", invite.room_id).eq("user_id", creatorId).maybeSingle();
      if (lookupError) return json({ error: "Invitation accepted, but the existing seat could not be checked." }, 503, headers);
      if (existingParticipant?.status !== "admitted") {
        const participantWrite = existingParticipant
          ? await admin.from("live_room_participants").update({
              display_name: displayName, role: "guest", status: "requested",
              seat_number: null, joined_at: null, left_at: null,
              last_seen_at: new Date().toISOString(),
              camera_enabled: false, microphone_enabled: false,
            }).eq("id", existingParticipant.id).neq("status", "admitted")
          : await admin.from("live_room_participants").insert({
              room_id: invite.room_id, user_id: creatorId, display_name: displayName,
              role: "guest", status: "requested", seat_number: null,
              joined_at: null, left_at: null, last_seen_at: new Date().toISOString(),
              camera_enabled: false, microphone_enabled: false,
            });
        if (participantWrite.error) {
          // Restore the invitation to pending so the guest can retry instead of losing it.
          await admin.from("live_room_invites").update({
            status: "pending", responded_at: null,
          }).eq("id", inviteId).eq("status", "accepted");
          return json({ error: "Invitation response could not finish. The invitation is available to retry." }, 503, headers);
        }
      }
    }
    return json({ ok: true, status: decision }, 200, headers);
  }

  if (action === "live-participants-list") {
    const { data: room, error: roomError } = await getActiveRoom(String(body.room_id || ""));
    if (roomError || !room) return json({ error: "Active LIVE room not found." }, 404, headers);
    const isHost = room.creator_id === creatorId;
    let query = admin.from("live_room_participants")
      .select("id,user_id,display_name,role,status,seat_number,camera_enabled,microphone_enabled,camera_allowed,microphone_allowed")
      .eq("room_id", room.id).order("created_at", { ascending: true });
    if (!isHost) query = query.or("status.eq.admitted,user_id.eq." + creatorId);
    const { data, error } = await query.limit(20);
    if (error) return json({ error: "Could not load LIVE participants." }, 503, headers);
    return json({ ok: true, participants: data || [], seat_capacity: (await admin.from("live_rooms").select("seat_capacity").eq("id", room.id).single()).data?.seat_capacity || 1 }, 200, headers);
  }

  if (action === "list-members") {
    const { data: room, error: roomError } = await getActiveRoom(String(body.room_id || ""));
    if (roomError || !room) return json({ error: "Active LIVE room not found." }, 404, headers);
    const isHost = room.creator_id === creatorId;
    const { data: actor, error: actorError } = await admin.from("live_room_participants")
      .select("role,status").eq("room_id", room.id).eq("user_id", creatorId).maybeSingle();
    if (actorError) return json({ error: "Could not verify moderator permissions." }, 503, headers);
    const actorRole = isHost ? "host" : actor?.status === "admitted" ? String(actor.role || "guest") : "viewer";
    if (actorRole !== "host" && actorRole !== "moderator") {
      return json({ error: "Only the host or an admitted moderator can manage LIVE members." }, 403, headers);
    }
    const { data, error } = await admin.from("live_room_participants")
      .select("id,user_id,display_name,role,status,seat_number,camera_enabled,microphone_enabled,camera_allowed,microphone_allowed")
      .eq("room_id", room.id).in("status", ["requested", "admitted"])
      .order("created_at", { ascending: true }).limit(30);
    if (error) return json({ error: "Could not load LIVE members." }, 503, headers);
    return json({ ok: true, actor_role: actorRole, members: data || [] }, 200, headers);
  }

  if (action === "set-member-permissions" || action === "set-moderator" || action === "remove-member") {
    const { data: room, error: roomError } = await getActiveRoom(String(body.room_id || ""));
    if (roomError || !room) return json({ error: "Active LIVE room not found." }, 404, headers);
    const targetUserId = String(body.target_user_id || "");
    if (!targetUserId || targetUserId === creatorId) return json({ error: "A valid target member is required." }, 400, headers);
    const isHost = room.creator_id === creatorId;
    const { data: actor, error: actorError } = await admin.from("live_room_participants")
      .select("role,status").eq("room_id", room.id).eq("user_id", creatorId).maybeSingle();
    if (actorError) return json({ error: "Could not verify moderator permissions." }, 503, headers);
    const actorRole = isHost ? "host" : actor?.status === "admitted" ? String(actor.role || "guest") : "viewer";
    if (actorRole !== "host" && actorRole !== "moderator") {
      return json({ error: "Only the host or an admitted moderator can manage LIVE members." }, 403, headers);
    }
    const { data: target, error: targetError } = await admin.from("live_room_participants")
      .select("id,user_id,role,status").eq("room_id", room.id).eq("user_id", targetUserId)
      .in("status", ["requested", "admitted"]).maybeSingle();
    if (targetError) return json({ error: "Could not load target member." }, 503, headers);
    if (!target || target.role === "host") return json({ error: "Target LIVE member was not found or cannot be managed." }, 404, headers);
    if (!isHost && target.role === "moderator") return json({ error: "Moderators cannot manage another moderator." }, 403, headers);

    if (action === "set-moderator") {
      if (!isHost) return json({ error: "Only the host can appoint or remove moderators." }, 403, headers);
      if (target.status !== "admitted") return json({ error: "Only an admitted guest can become a moderator." }, 409, headers);
      const { error } = await admin.from("live_room_participants")
        .update({ role: body.moderator === true ? "moderator" : "guest" })
        .eq("id", target.id).eq("room_id", room.id).eq("status", "admitted");
      if (error) return json({ error: "Could not update moderator role." }, 503, headers);
      return json({ ok: true, role: body.moderator === true ? "moderator" : "guest" }, 200, headers);
    }

    if (action === "set-member-permissions") {
      const updates: Record<string, boolean> = {};
      if (typeof body.camera_allowed === "boolean") {
        updates.camera_allowed = body.camera_allowed;
        if (!body.camera_allowed) updates.camera_enabled = false;
      }
      if (typeof body.microphone_allowed === "boolean") {
        updates.microphone_allowed = body.microphone_allowed;
        if (!body.microphone_allowed) updates.microphone_enabled = false;
      }
      if (!Object.keys(updates).length) return json({ error: "Choose a camera or microphone permission to change." }, 400, headers);
      const { error } = await admin.from("live_room_participants").update(updates)
        .eq("id", target.id).eq("room_id", room.id).in("status", ["requested", "admitted"]);
      if (error) return json({ error: "Could not update LIVE member permissions." }, 503, headers);
      return json({ ok: true, permissions: updates }, 200, headers);
    }

    const { error } = await admin.from("live_room_participants").update({
      status: "removed", seat_number: null, left_at: new Date().toISOString(),
      camera_enabled: false, microphone_enabled: false,
    }).eq("id", target.id).eq("room_id", room.id).in("status", ["requested", "admitted"]);
    if (error) return json({ error: "Could not remove LIVE member." }, 503, headers);
    return json({ ok: true }, 200, headers);
  }

  if (action === "live-seat-request") {
    const roomId = String(body.room_id || "");
    const { data: room, error: roomError } = await getActiveRoom(roomId);
    if (roomError || !room) return json({ error: "Active LIVE room not found." }, 404, headers);
    if (room.creator_id === creatorId) return json({ error: "Host already controls this room." }, 400, headers);
    const { data: profile } = await admin.from("profiles").select("full_name,username")
      .eq("user_id", creatorId).maybeSingle();
    const displayName = String(profile?.full_name || profile?.username || "Creator").trim().slice(0, 80) || "Creator";
    const now = new Date().toISOString();
    const { data: existing, error: existingError } = await admin.from("live_room_participants")
      .select("id,status,seat_number").eq("room_id", roomId).eq("user_id", creatorId).maybeSingle();
    if (existingError) return json({ error: "Could not check your existing LIVE seat." }, 503, headers);
    if (existing?.status === "admitted") {
      return json({ ok: true, participant: { id: existing.id, status: "admitted", seat_number: existing.seat_number } }, 200, headers);
    }
    let data: any = null;
    let error: any = null;
    if (existing) {
      const result = await admin.from("live_room_participants").update({
        display_name: displayName, role: "guest", status: "requested",
        seat_number: null, left_at: null, last_seen_at: now,
        camera_enabled: false, microphone_enabled: false,
      }).eq("id", existing.id).neq("status", "admitted")
        .select("id,status").single();
      data = result.data; error = result.error;
    } else {
      const result = await admin.from("live_room_participants").insert({
        room_id: roomId, user_id: creatorId, display_name: displayName,
        role: "guest", status: "requested", last_seen_at: now,
        camera_enabled: false, microphone_enabled: false,
      }).select("id,status").single();
      data = result.data; error = result.error;
    }
    if (error || !data) return json({ error: "Could not request a LIVE seat. Refresh and retry." }, 409, headers);
    return json({ ok: true, participant: data }, 200, headers);
  }

  if (action === "live-seat-admit") {
    const participantId = String(body.participant_id || "");
    const { data: room, error: roomError } = await getActiveRoom(String(body.room_id || ""));
    if (roomError || !room || room.creator_id !== creatorId) {
      return json({ error: "Only the host can admit a guest." }, 403, headers);
    }
    const { data: participant, error: participantError } = await admin.from("live_room_participants")
      .select("id,user_id,status,role").eq("id", participantId).eq("room_id", room.id)
      .eq("status", "requested").maybeSingle();
    if (participantError || !participant) return json({ error: "Guest request not found." }, 404, headers);
    const { data: roomConfig } = await admin.from("live_rooms").select("seat_capacity").eq("id", room.id).single();
    const capacity = Math.max(1, Math.min(10, Number(roomConfig?.seat_capacity || 1)));
    const { data: occupied, error: occupiedError } = await admin.from("live_room_participants")
      .select("seat_number").eq("room_id", room.id).eq("status", "admitted").not("seat_number", "is", null);
    if (occupiedError) return json({ error: "Could not check available guest seats." }, 503, headers);
    const used = new Set((occupied || []).map((row: any) => Number(row.seat_number)));
    let seat = 2;
    while (seat <= capacity && used.has(seat)) seat++;
    if (seat > capacity) return json({ error: "All LIVE seats are occupied." }, 409, headers);
    const { data: admitted, error } = await admin.from("live_room_participants").update({
      status: "admitted", seat_number: seat, joined_at: new Date().toISOString(),
      left_at: null, last_seen_at: new Date().toISOString(),
    }).eq("id", participantId).eq("room_id", room.id).eq("status", "requested")
      .select("id,user_id,display_name,role,status,seat_number").maybeSingle();
    if (error || !admitted) return json({ error: "Seat assignment conflicted with another host action. Refresh and retry." }, 409, headers);
    return json({ ok: true, participant: admitted }, 200, headers);
  }

  if (action === "live-seat-remove") {
    const participantId = String(body.participant_id || "");
    const { data: room, error: roomError } = await getActiveRoom(String(body.room_id || ""));
    if (roomError || !room || room.creator_id !== creatorId) {
      return json({ error: "Only the host can remove a guest." }, 403, headers);
    }
    const { data, error } = await admin.from("live_room_participants").update({
      status: "removed", seat_number: null, left_at: new Date().toISOString(),
      camera_enabled: false, microphone_enabled: false,
    }).eq("id", participantId).eq("room_id", room.id).eq("role", "guest")
      .in("status", ["requested", "admitted"]).select("id").maybeSingle();
    if (error || !data) return json({ error: "Guest could not be removed." }, 409, headers);
    return json({ ok: true }, 200, headers);
  }

  if (action === "live-seat-leave") {
    const { data: room, error: roomError } = await getActiveRoom(String(body.room_id || ""));
    if (roomError || !room) return json({ error: "Active LIVE room not found." }, 404, headers);
    const { data, error } = await admin.from("live_room_participants").update({
      status: "left", seat_number: null, left_at: new Date().toISOString(),
      camera_enabled: false, microphone_enabled: false,
    }).eq("room_id", room.id).eq("user_id", creatorId).eq("role", "guest")
      .eq("status", "admitted").select("id").maybeSingle();
    if (error || !data) return json({ error: "You are not an admitted guest in this room." }, 409, headers);
    return json({ ok: true }, 200, headers);
  }

  if (action === "pk-join-queue") {
    const roomId = String(body.room_id || "");
    const { data: room, error: roomError } = await admin.from("live_rooms")
      .select("id,creator_id,is_live").eq("id", roomId).eq("creator_id", creatorId).eq("is_live", true).maybeSingle();
    if (roomError || !room) return json({ error: "Start your own active LIVE room before matchmaking." }, 403, headers);
    const { data, error } = await admin.rpc("live_pk_matchmake", { p_user_id: creatorId, p_room_id: roomId });
    if (error || !data) {
      console.error("LIVE PK matchmaking failed:", error?.message);
      return json({ error: "Could not join the friendly LIVE battle queue." }, 503, headers);
    }
    return json({ ok: true, ...data }, 200, headers);
  }

  if (action === "pk-status") {
    const roomId = String(body.room_id || "");
    const { data: queue, error: queueError } = await admin.from("live_pk_queue")
      .select("id,status,match_id").eq("user_id", creatorId).eq("room_id", roomId)
      .in("status", ["waiting", "matched"]).order("created_at", { ascending: false }).limit(1).maybeSingle();
    if (queueError) return json({ error: "Could not load battle queue status." }, 503, headers);
    if (!queue) return json({ ok: true, status: "idle" }, 200, headers);
    if (queue.status === "waiting" || !queue.match_id) return json({ ok: true, status: "waiting" }, 200, headers);
    const { data: match, error: matchError } = await admin.from("live_pk_matches")
      .select("id,room_a_id,room_b_id,creator_a_id,creator_b_id,status")
      .eq("id", queue.match_id).maybeSingle();
    if (matchError) return json({ error: "Could not load active battle." }, 503, headers);
    if (!match || match.status !== "active") {
      await admin.from("live_pk_queue").update({ status: "cancelled", updated_at: new Date().toISOString() })
        .eq("user_id", creatorId).eq("match_id", queue.match_id);
      return json({ ok: true, status: "idle" }, 200, headers);
    }
    const { data: rooms, error: roomStateError } = await admin.from("live_rooms")
      .select("id,is_live").in("id", [match.room_a_id, match.room_b_id]);
    if (roomStateError) return json({ error: "Could not verify both LIVE rooms." }, 503, headers);
    if (!rooms || rooms.length !== 2 || rooms.some((room: { is_live: boolean | null }) => room.is_live !== true)) {
      await admin.from("live_pk_matches").update({
        status: "completed", ended_at: new Date().toISOString()
      }).eq("id", match.id).eq("status", "active");
      await admin.from("live_pk_queue").update({
        status: "cancelled", updated_at: new Date().toISOString()
      }).eq("match_id", match.id).eq("status", "matched");
      return json({ ok: true, status: "idle" }, 200, headers);
    }
    const ownIsA = match.creator_a_id === creatorId;
    const opponentId = ownIsA ? match.creator_b_id : match.creator_a_id;
    const opponentRoom = ownIsA ? match.room_b_id : match.room_a_id;
    const ownRoom = ownIsA ? match.room_a_id : match.room_b_id;
    const [{ data: profile }, { count: ownScore }, { count: opponentScore }] = await Promise.all([
      admin.from("creator_public_profiles").select("username,full_name").eq("user_id", opponentId).maybeSingle(),
      admin.from("live_room_reactions").select("id", { count: "exact", head: true }).eq("room_id", ownRoom).eq("reaction", "like"),
      admin.from("live_room_reactions").select("id", { count: "exact", head: true }).eq("room_id", opponentRoom).eq("reaction", "like")
    ]);
    return json({ ok: true, status: "matched", match_id: match.id,
      opponent_name: String(profile?.full_name || profile?.username || "LIVE creator"),
      own_score: ownScore || 0, opponent_score: opponentScore || 0 }, 200, headers);
  }

  if (action === "pk-leave-queue") {
    const { error } = await admin.from("live_pk_queue").update({ status: "cancelled", updated_at: new Date().toISOString() })
      .eq("user_id", creatorId).eq("status", "waiting");
    if (error) return json({ error: "Could not leave the matchmaking queue." }, 503, headers);
    return json({ ok: true }, 200, headers);
  }

  if (action === "pk-finish") {
    const matchId = String(body.match_id || "");
    if (!matchId) return json({ error: "Battle match ID is required." }, 400, headers);
    const { data, error } = await admin.from("live_pk_matches").update({
      status: "completed", ended_at: new Date().toISOString()
    }).eq("id", matchId).eq("status", "active")
      .or("creator_a_id.eq." + creatorId + ",creator_b_id.eq." + creatorId)
      .select("id").maybeSingle();
    if (error) return json({ error: "Could not end the friendly battle." }, 503, headers);
    if (!data) return json({ error: "Active battle not found for this creator." }, 404, headers);
    const { error: queueError } = await admin.from("live_pk_queue").update({
      status: "cancelled", updated_at: new Date().toISOString()
    }).eq("match_id", matchId).eq("status", "matched");
    if (queueError) return json({ error: "Battle ended, but matchmaking cleanup failed. Refresh status." }, 503, headers);
    return json({ ok: true }, 200, headers);
  }


  if (action === "pk-join-queue") {
    const roomId = String(body.room_id || "");
    const { data: room, error: roomError } = await admin.from("live_rooms")
      .select("id,creator_id,is_live").eq("id", roomId).eq("creator_id", creatorId).eq("is_live", true).maybeSingle();
    if (roomError || !room) return json({ error: "Start your own active LIVE room before matchmaking." }, 403, headers);
    const { data, error } = await admin.rpc("live_pk_matchmake", { p_user_id: creatorId, p_room_id: roomId });
    if (error || !data) {
      console.error("LIVE PK matchmaking failed:", error?.message);
      return json({ error: "Could not join the friendly LIVE battle queue." }, 503, headers);
    }
    return json({ ok: true, ...data }, 200, headers);
  }

  if (action === "pk-status") {
    const roomId = String(body.room_id || "");
    const { data: queue, error: queueError } = await admin.from("live_pk_queue")
      .select("id,status,match_id").eq("user_id", creatorId).eq("room_id", roomId)
      .in("status", ["waiting", "matched"]).order("created_at", { ascending: false }).limit(1).maybeSingle();
    if (queueError) return json({ error: "Could not load battle queue status." }, 503, headers);
    if (!queue) return json({ ok: true, status: "idle" }, 200, headers);
    if (queue.status === "waiting" || !queue.match_id) return json({ ok: true, status: "waiting" }, 200, headers);
    const { data: match, error: matchError } = await admin.from("live_pk_matches")
      .select("id,room_a_id,room_b_id,creator_a_id,creator_b_id,status")
      .eq("id", queue.match_id).maybeSingle();
    if (matchError) return json({ error: "Could not load active battle." }, 503, headers);
    if (!match || match.status !== "active") {
      await admin.from("live_pk_queue").update({ status: "cancelled", updated_at: new Date().toISOString() })
        .eq("user_id", creatorId).eq("match_id", queue.match_id);
      return json({ ok: true, status: "idle" }, 200, headers);
    }
    const { data: rooms, error: roomStateError } = await admin.from("live_rooms")
      .select("id,is_live").in("id", [match.room_a_id, match.room_b_id]);
    if (roomStateError) return json({ error: "Could not verify both LIVE rooms." }, 503, headers);
    if (!rooms || rooms.length !== 2 || rooms.some((room: { is_live: boolean | null }) => room.is_live !== true)) {
      await admin.from("live_pk_matches").update({ status: "completed", ended_at: new Date().toISOString() })
        .eq("id", match.id).eq("status", "active");
      await admin.from("live_pk_queue").update({ status: "cancelled", updated_at: new Date().toISOString() })
        .eq("match_id", match.id).eq("status", "matched");
      return json({ ok: true, status: "idle" }, 200, headers);
    }
    const ownIsA = match.creator_a_id === creatorId;
    const opponentId = ownIsA ? match.creator_b_id : match.creator_a_id;
    const opponentRoom = ownIsA ? match.room_b_id : match.room_a_id;
    const ownRoom = ownIsA ? match.room_a_id : match.room_b_id;
    const [{ data: profile }, { count: ownScore }, { count: opponentScore }] = await Promise.all([
      admin.from("creator_public_profiles").select("username,full_name").eq("user_id", opponentId).maybeSingle(),
      admin.from("live_room_reactions").select("id", { count: "exact", head: true }).eq("room_id", ownRoom).eq("reaction", "like"),
      admin.from("live_room_reactions").select("id", { count: "exact", head: true }).eq("room_id", opponentRoom).eq("reaction", "like")
    ]);
    return json({ ok: true, status: "matched", match_id: match.id,
      opponent_name: String(profile?.full_name || profile?.username || "LIVE creator"),
      own_score: ownScore || 0, opponent_score: opponentScore || 0 }, 200, headers);
  }

  if (action === "pk-leave-queue") {
    const { error } = await admin.from("live_pk_queue").update({ status: "cancelled", updated_at: new Date().toISOString() })
      .eq("user_id", creatorId).eq("status", "waiting");
    if (error) return json({ error: "Could not leave the matchmaking queue." }, 503, headers);
    return json({ ok: true }, 200, headers);
  }

  if (action === "pk-finish") {
    const matchId = String(body.match_id || "");
    if (!matchId) return json({ error: "Battle match ID is required." }, 400, headers);
    const { data, error } = await admin.from("live_pk_matches").update({
      status: "completed", ended_at: new Date().toISOString()
    }).eq("id", matchId).eq("status", "active")
      .or("creator_a_id.eq." + creatorId + ",creator_b_id.eq." + creatorId)
      .select("id").maybeSingle();
    if (error) return json({ error: "Could not end the friendly battle." }, 503, headers);
    if (!data) return json({ error: "Active battle not found for this creator." }, 404, headers);
    const { error: queueError } = await admin.from("live_pk_queue").update({
      status: "cancelled", updated_at: new Date().toISOString()
    }).eq("match_id", matchId).eq("status", "matched");
    if (queueError) return json({ error: "Battle ended, but matchmaking cleanup failed. Refresh status." }, 503, headers);
    return json({ ok: true }, 200, headers);
  }


  const sfuAppId = Deno.env.get("CLOUDFLARE_SFU_APP_ID") || "";
  const sfuAppSecret = Deno.env.get("CLOUDFLARE_SFU_APP_SECRET") || "";
  const sfuConfigured = /^[a-f0-9]{32}$/i.test(sfuAppId) && /^[a-f0-9]{64}$/i.test(sfuAppSecret);
  async function sfuRequest(path: string, method: string, payload?: unknown) {
    const response = await fetch("https://rtc.live.cloudflare.com/v1/apps/" + sfuAppId + path, {
      method,
      headers: { "Authorization": "Bearer " + sfuAppSecret, "Content-Type": "application/json" },
      ...(payload === undefined ? {} : { body: JSON.stringify(payload) })
    });
    const raw = await response.text();
    let parsed: Record<string, unknown> = {};
    try { parsed = raw ? JSON.parse(raw) : {}; } catch { parsed = { errorDescription: raw.slice(0, 300) }; }
    if (!response.ok) {
      console.error("Cloudflare SFU request failed", method, path, response.status, parsed);
      throw new Error("Cloudflare Realtime request failed (" + response.status + "): " +
        String(parsed.errorDescription || parsed.error || "Unknown SFU error").slice(0, 220));
    }
    return parsed;
  }
  async function sfuRoom(roomId: string) {
    const { data, error } = await getActiveRoom(roomId);
    if (error || !data) throw new Error("Active LIVE room not found.");
    return data;
  }
  async function sfuMember(roomId: string, userId: string) {
    const { data, error } = await admin.from("live_room_participants")
      .select("id,role,status,camera_allowed,microphone_allowed").eq("room_id", roomId).eq("user_id", userId).maybeSingle();
    if (error) throw new Error("Could not verify LIVE membership.");
    return data;
  }

  if (String(action).startsWith("sfu-") && action !== "sfu-health") {
    if (!sfuConfigured) return json({ error: "Cloudflare Realtime SFU credentials are not configured on the server." }, 503, headers);
    try {
      const roomId = String(body.room_id || "");
      const room = await sfuRoom(roomId);
      const isHost = room.creator_id === creatorId;
      const member = isHost ? null : await sfuMember(roomId, creatorId);
      const admitted = member?.status === "admitted";
      const actorCanPublish = isHost || (admitted && (member?.camera_allowed === true || member?.microphone_allowed === true));
      if (action === "sfu-create-session") {
        const role = body.role === "publisher" ? "publisher" : "subscriber";
        if (role === "publisher" && !actorCanPublish) {
          return json({ error: "Only the host or an admitted guest with camera permission can publish." }, 403, headers);
        }
        if (role === "subscriber" && !isHost && !admitted) {
          const { error: touchError } = await admin.from("live_rooms").select("id").eq("id", roomId).maybeSingle();
          if (touchError) return json({ error: "Could not verify room access." }, 503, headers);
        }
        const created = await sfuRequest("/sessions/new", "POST");
        const sessionId = String(created.sessionId || "");
        if (!sessionId) return json({ error: "Cloudflare did not return an SFU session ID." }, 502, headers);
        const { error: saveError } = await admin.from("live_sfu_sessions").insert({
          room_id: roomId, user_id: creatorId, session_id: sessionId, role, status: "active"
        });
        if (saveError) {
          await sfuRequest("/sessions/" + encodeURIComponent(sessionId) + "/tracks/close", "PUT", { tracks: [], force: true }).catch(() => {});
          return json({ error: "Could not save SFU session state." }, 503, headers);
        }
        if (role === "publisher" && member) {
          await admin.from("live_room_participants").update({ sfu_session_id: sessionId, sfu_connected: false })
            .eq("id", member.id);
        }
        return json({ ok: true, session_id: sessionId }, 200, headers);
      }

      const sessionId = String(body.session_id || "");
      if (!sessionId) return json({ error: "SFU session ID is required." }, 400, headers);
      const { data: session, error: sessionError } = await admin.from("live_sfu_sessions")
        .select("id,room_id,user_id,session_id,role,status,published_tracks,subscribed_tracks")
        .eq("session_id", sessionId).eq("room_id", roomId).eq("user_id", creatorId).eq("status", "active").maybeSingle();
      if (sessionError || !session) return json({ error: "Active SFU session not found for this user and room." }, 404, headers);

      if (action === "sfu-publish") {
        if (session.role !== "publisher" || !actorCanPublish) return json({ error: "Publishing permission is not active." }, 403, headers);
        if (!body.session_description?.sdp || body.session_description.type !== "offer") {
          return json({ error: "A complete local SDP offer is required." }, 400, headers);
        }
        const requested = Array.isArray(body.tracks) ? body.tracks : [];
        if (!requested.length || requested.length > 2 || requested.some(t =>
          t.location !== "local" || !["camera", "microphone"].includes(String(t.trackName)) || !String(t.mid || "").match(/^\d+$/)
        )) return json({ error: "Publish only the permitted camera and microphone tracks." }, 400, headers);
        if (requested.some(t => t.trackName === "camera") && !isHost && member?.camera_allowed !== true) return json({ error: "Camera permission was revoked." }, 403, headers);
        if (requested.some(t => t.trackName === "microphone") && !isHost && member?.microphone_allowed !== true) return json({ error: "Microphone permission was revoked." }, 403, headers);
        const result = await sfuRequest("/sessions/" + encodeURIComponent(sessionId) + "/tracks/new", "POST", {
          sessionDescription: body.session_description, tracks: requested
        });
        const published = requested.map(t => ({ mid: t.mid, trackName: t.trackName }));
        const { error: updateError } = await admin.from("live_sfu_sessions")
          .update({ published_tracks: published, updated_at: new Date().toISOString() }).eq("id", session.id);
        if (updateError) return json({ error: "Tracks were negotiated but publication state could not be saved. Reconnect the publisher." }, 503, headers);
        if (member) {
          await admin.from("live_room_participants").update({
            sfu_connected: true,
            camera_enabled: requested.some(t => t.trackName === "camera"),
            microphone_enabled: requested.some(t => t.trackName === "microphone"),
          }).eq("id", member.id);
        }
        return json({ ok: true, ...result }, 200, headers);
      }

      if (action === "sfu-list-pubs") {
        const { data: pubs, error: pubsError } = await admin.from("live_sfu_sessions")
          .select("session_id,user_id,published_tracks").eq("room_id", roomId).eq("role", "publisher").eq("status", "active");
        if (pubsError) return json({ error: "Could not list active LIVE publications." }, 503, headers);
        const allowed = pubs || [];
        return json({ ok: true, publications: allowed.flatMap(p =>
          (Array.isArray(p.published_tracks) ? p.published_tracks : []).map((t: {trackName?: string; mid?: string}) => ({
            session_id: p.session_id, user_id: p.user_id, track_name: t.trackName, mid: t.mid
          }))
        ) }, 200, headers);
      }

      if (action === "sfu-subscribe") {
        if (session.role !== "subscriber") return json({ error: "This session cannot subscribe to publications." }, 403, headers);
        const requested = Array.isArray(body.tracks) ? body.tracks : [];
        if (!requested.length || requested.length > 20 || requested.some(t =>
          t.location !== "remote" || !String(t.sessionId || "").trim() || !["camera", "microphone"].includes(String(t.trackName))
        )) return json({ error: "A valid list of remote camera/microphone publications is required." }, 400, headers);
        for (const t of requested) {
          const { data: pub } = await admin.from("live_sfu_sessions").select("id")
            .eq("room_id", roomId).eq("session_id", t.sessionId).eq("role", "publisher").eq("status", "active").maybeSingle();
          if (!pub) return json({ error: "A requested publication is no longer active in this room." }, 409, headers);
        }
        const result = await sfuRequest("/sessions/" + encodeURIComponent(sessionId) + "/tracks/new", "POST", { tracks: requested });
        const negotiated = Array.isArray(result.tracks) ? result.tracks : [];
        const subscribed = requested.map((track, index) => ({
          ...track,
          mid: String(negotiated[index]?.mid || ""),
        }));
        if (subscribed.some((track) => !isValidSfuMid(track.mid))) {
          return json({ error: "Cloudflare did not return a valid MID for every subscription." }, 502, headers);
        }
        const previous = Array.isArray(session.subscribed_tracks) ? session.subscribed_tracks : [];
        const byMid = new Map<string, Record<string, unknown>>();
        for (const track of previous) {
          if (track && isValidSfuMid((track as {mid?: unknown}).mid)) {
            byMid.set(String((track as {mid: unknown}).mid), track as Record<string, unknown>);
          }
        }
        for (const track of subscribed) byMid.set(track.mid, track);
        const { error: updateError } = await admin.from("live_sfu_sessions")
          .update({ subscribed_tracks: [...byMid.values()], updated_at: new Date().toISOString() }).eq("id", session.id);
        if (updateError) return json({ error: "Subscription negotiation started but state could not be saved." }, 503, headers);
        return json({ ok: true, ...result }, 200, headers);
      }

      if (action === "sfu-unsubscribe") {
        if (session.role !== "subscriber") return json({ error: "Only subscriber sessions can unsubscribe remote tracks." }, 403, headers);
        const requested = Array.isArray(body.tracks) ? body.tracks : [];
        if (!requested.length || requested.length > 20 || requested.some((track) => !isValidSfuMid(track.mid))) {
          return json({ error: "At least one valid subscribed media MID is required." }, 400, headers);
        }
        const current = Array.isArray(session.subscribed_tracks) ? session.subscribed_tracks : [];
        const requestedMids = new Set(requested.map((track) => String(track.mid)));
        const knownMids = new Set(current
          .filter((track: {mid?: unknown}) => isValidSfuMid(track?.mid))
          .map((track: {mid: unknown}) => String(track.mid)));
        if ([...requestedMids].some((mid) => !knownMids.has(mid))) {
          return json({ error: "One or more tracks are not subscribed by this SFU session." }, 409, headers);
        }
        const result = await sfuRequest("/sessions/" + encodeURIComponent(sessionId) + "/tracks/close", "PUT", {
          tracks: [...requestedMids].map((mid) => ({ mid })), force: true
        });
        const remaining = current.filter((track: {mid?: unknown}) =>
          !isValidSfuMid(track?.mid) || !requestedMids.has(String(track.mid))
        );
        const { error: updateError } = await admin.from("live_sfu_sessions")
          .update({ subscribed_tracks: remaining, updated_at: new Date().toISOString() }).eq("id", session.id);
        if (updateError) return json({ error: "Tracks were closed but subscription state could not be updated." }, 503, headers);
        return json({ ok: true, ...result }, 200, headers);
      }

      if (action === "sfu-renegotiate") {
        if (!body.session_description?.sdp || body.session_description.type !== "answer") {
          return json({ error: "A complete local SDP answer is required." }, 400, headers);
        }
        const result = await sfuRequest("/sessions/" + encodeURIComponent(sessionId) + "/renegotiate", "PUT", {
          sessionDescription: body.session_description
        });
        return json({ ok: true, ...result }, 200, headers);
      }

      if (action === "sfu-abandon") {
        await sfuRequest("/sessions/" + encodeURIComponent(sessionId) + "/tracks/close", "PUT", {
          tracks: [], force: true
        }).catch((cleanupError) => {
          console.error("Cloudflare SFU failed-session cleanup failed", cleanupError);
        });
        const { error: abandonError } = await admin.from("live_sfu_sessions").update({
          status: "failed", updated_at: new Date().toISOString()
        }).eq("id", session.id);
        if (abandonError) return json({ error: "Could not mark the failed SFU session for cleanup." }, 503, headers);
        if (session.role === "publisher" && member) {
          await admin.from("live_room_participants").update({
            sfu_connected: false, sfu_session_id: null,
            camera_enabled: false, microphone_enabled: false,
          }).eq("id", member.id).eq("sfu_session_id", sessionId);
        }
        return json({ ok: true }, 200, headers);
      }

      if (action === "sfu-close") {
        const forceTracks = Array.isArray(body.tracks) ? body.tracks : [];
        if (!forceTracks.length || forceTracks.length > 20 || forceTracks.some(t => !isValidSfuMid(t.mid))) {
          return json({ error: "At least one valid media MID is required to close an SFU session." }, 400, headers);
        }
        const result = await sfuRequest("/sessions/" + encodeURIComponent(sessionId) + "/tracks/close", "PUT", {
          tracks: forceTracks.map(t => ({ mid: t.mid })), force: true
        });
        const { error: closeError } = await admin.from("live_sfu_sessions").update({
          status: "closed", updated_at: new Date().toISOString()
        }).eq("id", session.id);
        if (closeError) return json({ error: "Media closed but session state cleanup failed." }, 503, headers);
        if (session.role === "publisher" && member) {
          await admin.from("live_room_participants").update({
            sfu_connected: false, sfu_session_id: null,
            camera_enabled: false, microphone_enabled: false,
          }).eq("id", member.id).eq("sfu_session_id", sessionId);
        }
        return json({ ok: true, ...result }, 200, headers);
      }
    } catch (error) {
      return json({ error: error instanceof Error ? error.message : "Cloudflare Realtime operation failed." }, 502, headers);
    }
  }

  if (action === "sfu-health") {
    const appId = Deno.env.get("CLOUDFLARE_SFU_APP_ID") || "";
    const appSecret = Deno.env.get("CLOUDFLARE_SFU_APP_SECRET") || "";
    const configured = /^[a-f0-9]{32}$/i.test(appId) && /^[a-f0-9]{64}$/i.test(appSecret);
    return json({
      ok: true,
      configured,
      required_secrets: configured ? [] : ["CLOUDFLARE_SFU_APP_ID", "CLOUDFLARE_SFU_APP_SECRET"],
      message: configured
        ? "SFU credentials are present. Native publisher session and track signaling endpoints are enabled; subscriber playback integration is still being completed."
        : "Cloudflare Realtime SFU credentials are not configured. Existing Stream credentials are not interchangeable."
    }, 200, headers);
  }

  if (!accountId || !token) return json({ error: "Cloudflare Stream is not configured. Ask the project owner to check the server-side Cloudflare secrets." }, 503, headers);
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
    const { data: profile } = await admin.from("profiles").select("full_name,username")
      .eq("user_id", creatorId).maybeSingle();
    const displayName = String(profile?.full_name || profile?.username || "Creator").trim().slice(0, 80) || "Creator";
    const { error: hostError } = await admin.from("live_room_participants").upsert({
      room_id: body.room_id, user_id: creatorId, display_name: displayName,
      role: "host", status: "admitted", seat_number: 1,
      joined_at: new Date().toISOString(), left_at: null,
      camera_enabled: body.camera_enabled !== false, microphone_enabled: body.microphone_enabled === true,
    }, { onConflict: "room_id,user_id" });
    if (hostError) return json({ error: "LIVE started, but host seat could not be registered." }, 503, headers);
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