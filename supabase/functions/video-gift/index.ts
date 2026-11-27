import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const ALLOWED_ORIGINS = new Set([
  "https://new-star-delta.vercel.app",
  "https://new-star-nepal.netlify.app",
  "https://new-star.rkdaifilms.workers.dev"
]);

function isAllowedOrigin(origin: string) {
  return ALLOWED_ORIGINS.has(origin) ||
    /^https:\/\/new-star-[a-z0-9-]+-rkdaifilms-8858\.vercel\.app$/.test(origin);
}

function corsHeaders(origin: string) {
  const allowed = isAllowedOrigin(origin) ? origin : "https://new-star-delta.vercel.app";
  return {
    "Access-Control-Allow-Origin": allowed,
    "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    "Content-Type": "application/json",
    "Vary": "Origin"
  };
}

function reply(body: unknown, status: number, headers: Record<string, string>) {
  return new Response(JSON.stringify(body), { status, headers });
}

const GIFT_PRICES: Record<string, number> = {
  Rose: 1, Coffee: 5, Heart: 10, Glasses: 20, TikTok: 50, Car: 100,
  Ring: 200, Yacht: 500, Lion: 1000, Diamond: 1500, Crown: 2000,
  Jet: 3000, Castle: 5000, Dragon: 10000, Galaxy: 20000, Universe: 50000
};

Deno.serve(async (req: Request) => {
  const origin = req.headers.get("Origin") || "";
  const headers = corsHeaders(origin);
  if (req.method === "OPTIONS") return new Response("ok", { headers });
  if (req.method !== "POST") return reply({ success: false, error: "Method not allowed." }, 405, headers);
  // Native Android clients do not send an Origin header. They are still authenticated below with a verified Supabase JWT.\n  if (origin && !isAllowedOrigin(origin)) return reply({ success: false, error: "Origin not allowed." }, 403, headers);

  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY");
  const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!supabaseUrl || !anonKey || !serviceRoleKey) {
    return reply({ success: false, error: "Gift service is not configured." }, 503, headers);
  }

  const authorization = req.headers.get("Authorization") || "";
  if (!authorization.startsWith("Bearer ")) {
    return reply({ success: false, error: "Please sign in first." }, 401, headers);
  }

  const userClient = createClient(supabaseUrl, anonKey, {
    global: { headers: { Authorization: authorization } },
    auth: { persistSession: false, autoRefreshToken: false }
  });
  const { data: authData, error: authError } = await userClient.auth.getUser();
  const user = authData?.user;
  if (authError || !user) return reply({ success: false, error: "Your session is invalid. Please sign in again." }, 401, headers);

  let body: any;
  try { body = await req.json(); }
  catch { return reply({ success: false, error: "Invalid request body." }, 400, headers); }

  const admin = createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false }
  });

  if (body?.action === "balance") {
    const { data, error } = await admin.from("user_wallets")
      .select("coin_balance").eq("user_id", user.id).maybeSingle();
    if (error) {
      console.error("Video gift wallet lookup failed:", error.message);
      return reply({ success: false, error: "Wallet balance could not be loaded." }, 503, headers);
    }
    return reply({ success: true, balance: Number(data?.coin_balance || 0) }, 200, headers);
  }

  if (body?.action !== "send") return reply({ success: false, error: "Unsupported gift action." }, 400, headers);
  const videoId = typeof body.videoId === "string" ? body.videoId.trim() : "";
  const giftName = typeof body.giftName === "string" ? body.giftName : "";
  const coins = Number(body.coins);
  const requestId = typeof body.requestId === "string" ? body.requestId : "";

  if (!videoId || videoId.length > 100 ||
      !Object.prototype.hasOwnProperty.call(GIFT_PRICES, giftName) ||
      !Number.isSafeInteger(coins) || GIFT_PRICES[giftName] !== coins ||
      !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(requestId)) {
    return reply({ success: false, error: "Gift selection or request details are invalid." }, 400, headers);
  }

  const { data, error } = await admin.rpc("send_creator_gift_secure", {
    p_sender_id: user.id,
    p_video_id: videoId,
    p_gift_name: giftName,
    p_coins: coins,
    p_request_id: requestId
  });
  if (error) {
    console.error("Video gift transaction rejected:", error.message);
    const message = error.message || "";
    const safeMessages = [
      "Insufficient coins.", "Wallet not found.", "You cannot gift your own video.",
      "Video is unavailable for gifts.", "Follow request approval is required before gifting this creator.",
      "Video gift commission is not configured or enabled.", "Coin deduction failed.",
      "Gift name or coin price is invalid."
    ];
    const safe = safeMessages.find(item => message.includes(item));
    return reply({ success: false, error: safe || "Gift could not be sent. Please try again." }, 400, headers);
  }

  return reply({ success: true, ...data }, 200, headers);
});
