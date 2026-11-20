import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

function corsFor(req: Request) {
  const origin = req.headers.get("Origin") || "";
  let isProjectPreview = false;
  try {
    const parsed = new URL(origin);
    isProjectPreview = parsed.protocol === "https:" &&
      parsed.hostname.startsWith("new-star-") &&
      parsed.hostname.endsWith(".vercel.app") &&
      parsed.hostname.includes("rkdaifilms-8858");
  } catch { isProjectPreview = false; }
  const allowed = origin === "https://new-star-nepal.netlify.app" || origin === "https://new-star-delta.vercel.app" || isProjectPreview;
  return {
    "Access-Control-Allow-Origin": allowed ? origin : "https://new-star-delta.vercel.app",
    "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Content-Type": "application/json",
    "Vary": "Origin"
  };
}

Deno.serve(async (req: Request) => {
  const cors = corsFor(req);
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  const fail = (message: string, status = 400) =>
    new Response(JSON.stringify({ ok: false, error: message }), { status, headers: cors });

  const url = Deno.env.get("SUPABASE_URL");
  const anon = Deno.env.get("SUPABASE_ANON_KEY");
  const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !anon || !service) return fail("Server configuration incomplete", 503);

  const auth = req.headers.get("Authorization") || "";
  if (!auth.startsWith("Bearer ")) return fail("Authentication required", 401);
  const userClient = createClient(url, anon, { global: { headers: { Authorization: auth } } });
  const { data: { user }, error: userError } = await userClient.auth.getUser();
  if (userError || !user) return fail("Invalid session", 401);

  const admin = createClient(url, service);
  if (req.method === "GET") {
    const { data: adminRole, error: roleError } = await admin.schema("private").from("admin_users")
      .select("role").eq("user_id", user.id).maybeSingle();
    if (roleError || !adminRole || adminRole.role !== "owner") return fail("Developer owner access only", 403);

    const tables = ["profiles", "videos", "live_rooms", "withdrawal_requests", "star_campaigns", "star_video_promotions"];
    const metrics: Record<string, number | null> = {};
    for (const table of tables) {
      const { count, error } = await admin.from(table).select("*", { count: "exact", head: true });
      metrics[table] = error ? null : (count ?? 0);
    }

    // Owner-only playback quality telemetry. Rejected sessions are diagnostics,
    // not automatic evidence of fraud (they can be caused by stale tabs or seeks).
    const since24h = new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString();
    const [
      watchTotal, watchActive, watchCompleted, watchRejected, watchStarted24h, watchRejected24h,
      uniqueViewsTotal, uniqueViews24h, watchDailyTotal, watchDaily24h
    ] = await Promise.all([
      admin.schema("private").from("video_watch_sessions").select("id", { count: "exact", head: true }),
      admin.schema("private").from("video_watch_sessions").select("id", { count: "exact", head: true }).eq("status", "active"),
      admin.schema("private").from("video_watch_sessions").select("id", { count: "exact", head: true }).eq("status", "completed"),
      admin.schema("private").from("video_watch_sessions").select("id", { count: "exact", head: true }).eq("status", "rejected"),
      admin.schema("private").from("video_watch_sessions").select("id", { count: "exact", head: true }).gte("started_at", since24h),
      admin.schema("private").from("video_watch_sessions").select("id", { count: "exact", head: true }).eq("status", "rejected").gte("started_at", since24h),
      admin.schema("private").from("video_view_dedupe").select("video_id", { count: "exact", head: true }),
      admin.schema("private").from("video_view_dedupe").select("video_id", { count: "exact", head: true }).gte("created_at", since24h),
      admin.schema("private").from("creator_watch_daily").select("video_id", { count: "exact", head: true }),
      admin.schema("private").from("creator_watch_daily").select("video_id", { count: "exact", head: true }).gte("updated_at", since24h)
    ]);
    metrics.watch_sessions_total = watchTotal.error ? null : (watchTotal.count ?? 0);
    metrics.watch_sessions_active = watchActive.error ? null : (watchActive.count ?? 0);
    metrics.watch_sessions_completed = watchCompleted.error ? null : (watchCompleted.count ?? 0);
    metrics.watch_sessions_rejected = watchRejected.error ? null : (watchRejected.count ?? 0);
    metrics.watch_sessions_started_24h = watchStarted24h.error ? null : (watchStarted24h.count ?? 0);
    metrics.watch_sessions_rejected_24h = watchRejected24h.error ? null : (watchRejected24h.count ?? 0);
    metrics.qualified_unique_view_records_total = uniqueViewsTotal.error ? null : (uniqueViewsTotal.count ?? 0);
    metrics.qualified_unique_view_records_24h = uniqueViews24h.error ? null : (uniqueViews24h.count ?? 0);
    metrics.long_watch_credit_rows_total = watchDailyTotal.error ? null : (watchDailyTotal.count ?? 0);
    metrics.long_watch_credit_rows_24h = watchDaily24h.error ? null : (watchDaily24h.count ?? 0);

    const [riskSignalsTotal, riskSignalsNeedsReview, riskSignals24h] = await Promise.all([
      admin.schema("private").from("video_watch_risk_signals").select("id", { count: "exact", head: true }),
      admin.schema("private").from("video_watch_risk_signals").select("id", { count: "exact", head: true }).eq("review_status", "needs_review"),
      admin.schema("private").from("video_watch_risk_signals").select("id", { count: "exact", head: true }).gte("last_seen_at", since24h)
    ]);
    metrics.watch_risk_signals_total = riskSignalsTotal.error ? null : (riskSignalsTotal.count ?? 0);
    metrics.watch_risk_signals_needs_review = riskSignalsNeedsReview.error ? null : (riskSignalsNeedsReview.count ?? 0);
    metrics.watch_risk_signals_24h = riskSignals24h.error ? null : (riskSignals24h.count ?? 0);
    const { data: withdrawals } = await admin.from("withdrawal_requests")
      .select("id,creator_id,amount,currency,country_code,payout_method,status,created_at")
      .order("created_at", { ascending: false }).limit(100);

    // Read financial totals only for the verified owner dashboard.
    async function readAll(table: string, columns: string, filterColumn?: string, filterValue?: string) {
      const rows: any[] = [];
      for (let offset = 0; offset < 50000; offset += 1000) {
        let query = admin.from(table).select(columns);
        if (filterColumn) query = query.eq(filterColumn, filterValue);
        const { data, error } = await query.range(offset, offset + 999);
        if (error) throw error;
        rows.push(...(data ?? []));
        if (!data || data.length < 1000) break;
      }
      return rows;
    }
    let financials: any = {
      paid_orders_count: null, paid_totals_by_currency: [],
      creator_gift_gross_coins: null, creator_gift_net_coins: null,
      platform_gift_fee_coins: null, developer_gift_commission_coins: null,
      gift_commission_basis_points: null, gift_commission_rate_percent: null,
      gift_commission_rules: null,
      gift_commission_ledger_rows: null, gift_commission_ledger_matches: null,
      pending_withdrawals_by_currency: []
    };
    try {
      const [paidOrders, giftRows, pendingRows, commissionRules] = await Promise.all([
        readAll("payment_orders", "amount_minor,currency", "status", "paid"),
        readAll("creator_gift_earnings", "coins,gross_coins,platform_fee_coins"),
        readAll("withdrawal_requests", "amount,currency,status", "status", "Pending"),
        admin.schema("private").from("commission_rules")
          .select("rule_code,basis_points,status")
          .order("rule_code", { ascending: true })
      ]);
      if (commissionRules.error) throw commissionRules.error;
      const platformRows: any[] = [];
      for (let offset = 0; offset < 50000; offset += 1000) {
        const { data, error } = await admin.schema("private").from("platform_coin_ledger")
          .select("gross_coins,platform_fee_coins,creator_net_coins,commission_basis_points")
          .range(offset, offset + 999);
        if (error) throw error;
        platformRows.push(...(data ?? []));
        if (!data || data.length < 1000) break;
      }
      const byCurrency = new Map<string, number>();
      for (const order of paidOrders) {
        const currency = String(order.currency || "UNK");
        byCurrency.set(currency, (byCurrency.get(currency) || 0) + Number(order.amount_minor || 0));
      }
      const pendingByCurrency = new Map<string, number>();
      for (const row of pendingRows) {
        const currency = String(row.currency || "UNK");
        pendingByCurrency.set(currency, (pendingByCurrency.get(currency) || 0) + Number(row.amount || 0));
      }
      const developerCommissionCoins = platformRows.reduce((sum, row) => sum + Number(row.platform_fee_coins || 0), 0);
      const giftRowsFeeCoins = giftRows.reduce((sum, row) => sum + Number(row.platform_fee_coins || 0), 0);
      const giftRuleMap = Object.fromEntries((commissionRules.data ?? []).map((rule: any) => [
        rule.rule_code, { basis_points: rule.basis_points, platform_percent: Number(rule.basis_points) / 100,
          creator_percent: 100 - Number(rule.basis_points) / 100, status: rule.status }
      ]));
      const activeBasisPoints = null; // No gift route is enabled until all three systems are verified.
      financials = {
        paid_orders_count: paidOrders.length,
        paid_totals_by_currency: Array.from(byCurrency, ([currency, amount_minor]) => ({ currency, amount_minor })),
        creator_gift_gross_coins: giftRows.reduce((sum, row) => sum + Number(row.gross_coins ?? row.coins ?? 0), 0),
        creator_gift_net_coins: giftRows.reduce((sum, row) => sum + Number(row.coins || 0), 0),
        platform_gift_fee_coins: developerCommissionCoins,
        developer_gift_commission_coins: developerCommissionCoins,
        gift_commission_basis_points: activeBasisPoints,
        gift_commission_rate_percent: null,
        gift_commission_rules: giftRuleMap,
        gift_commission_ledger_rows: platformRows.length,
        gift_commission_ledger_matches: developerCommissionCoins === giftRowsFeeCoins,
        pending_withdrawals_by_currency: Array.from(pendingByCurrency, ([currency, amount]) => ({ currency, amount }))
      };
    } catch (financialError) {
      console.error("Owner financial summary unavailable:", financialError);
    }

    await admin.schema("private").from("admin_audit_logs").insert({
      actor_id: user.id, action: "admin_dashboard_view", target_type: "dashboard", details: { role: adminRole.role }
    });
    const { data: contentReports, error: reportReadError } = await admin.from("content_reports")
      .select("id,reporter_id,target_type,target_id,reason,details,status,created_at")
      .order("created_at", { ascending: false }).limit(100);
    if (reportReadError) console.error("Content report list unavailable:", reportReadError.message);
    const { data: packageRows } = await admin.from("coin_packages").select("id,code,coins,bonus_coins,amount_minor,currency,country_code,channel,active,created_at").order("created_at", { ascending: false }).limit(200);
    const { data: settingsRow } = await admin.schema("private").from("monetization_settings").select("setting_value").eq("setting_key","provider_config").maybeSingle();
    const cfg = settingsRow?.setting_value ?? {};
    const { data: payoutProvider } = await admin.schema("private").from("payout_provider_configs")
      .select("provider,enabled,onboarding_status,last_verified_at").eq("provider","payoneer").maybeSingle();
    const { data: payoutRoutes } = await admin.schema("private").from("country_payout_routes")
      .select("country_code,currency,enabled,availability_status,last_verified_at").order("country_code");
    const { data: countryRows } = await admin.from("country_catalog").select("iso2,country_name");
    const { data: riskReviewQueue, error: riskQueueError } = await admin.schema("private")
      .from("video_watch_risk_signals")
      .select("id,viewer_id,creator_id,video_id,occurrence_count,risk_score,first_seen_at,last_seen_at,review_status")
      .eq("review_status", "needs_review").order("last_seen_at", { ascending: false }).limit(100);
    if (riskQueueError) console.error("Watch risk queue unavailable:", riskQueueError.message);
    return new Response(JSON.stringify({
      ok: true, role: adminRole.role, metrics, financials,
      watch_risk_review_queue: riskQueueError ? [] : (riskReviewQueue ?? []),
      creator_monetization_rules: {
        follower_threshold: 5000,
        long_watch_hours_threshold: 1000,
        long_watch_window_days: 60,
        shorts_views_threshold: 300000,
        shorts_views_window_days: 60,
        eligibility_logic: "5,000 genuine followers AND (1,000 eligible public long-video watch hours in 60 days OR 300,000 qualified public Shorts views in 60 days)"
      },
      withdrawals: withdrawals ?? [], content_reports: contentReports ?? [], coin_packages: packageRows ?? [], monetization: cfg,
      payout_provider: payoutProvider ?? { provider: "payoneer", enabled: false, onboarding_status: "not_connected" },
      payout_routes: (payoutRoutes ?? []).map((route: any) => ({ ...route, country_name: (countryRows ?? []).find((c: any) => c.iso2 === route.country_code)?.country_name ?? route.country_code })), generated_at: new Date().toISOString()
    }), { headers: cors });
  }

  if (req.method !== "POST") return fail("Method not allowed", 405);
  let body: any;
  try { body = await req.json(); } catch { return fail("Invalid JSON"); }

  if (body.action === "review_creator_monetization") {
    const { data: adminRole, error: roleError } = await admin.schema("private").from("admin_users")
      .select("role").eq("user_id", user.id).maybeSingle();
    if (roleError || !adminRole || adminRole.role !== "owner") return fail("Developer owner access only", 403);

    const applicationId = String(body.application_id || "");
    const nextStatus = String(body.status || "");
    const reviewNote = String(body.review_note || "").trim().slice(0, 1000);
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(applicationId) ||
        !["approved", "rejected", "suspended"].includes(nextStatus)) {
      return fail("Invalid creator monetization review request");
    }
    if (nextStatus !== "approved" && reviewNote.length < 3) {
      return fail("A review reason is required for rejection or suspension");
    }
    const { data: application, error: applicationError } = await admin.from("creator_monetization_applications")
      .select("id,creator_id,status").eq("id", applicationId).maybeSingle();
    if (applicationError) return fail("Could not load creator application", 500);
    if (!application) return fail("Creator application not found", 404);
    if (application.status !== "pending") return fail("Only pending applications can be reviewed", 409);

    const { data: reviewed, error: updateError } = await admin.from("creator_monetization_applications")
      .update({
        status: nextStatus,
        reviewer_id: user.id,
        reviewed_at: new Date().toISOString(),
        review_note: reviewNote || null
      }).eq("id", applicationId).eq("status", "pending")
      .select("id,creator_id,status,reviewer_id,reviewed_at,review_note").maybeSingle();
    if (updateError) return fail("Could not save creator monetization review", 500);
    if (!reviewed) return fail("Application was already reviewed; refresh the dashboard", 409);

    const { error: auditError } = await admin.schema("private").from("admin_audit_logs").insert({
      actor_id: user.id,
      action: "creator_monetization_reviewed",
      target_type: "creator_monetization_application",
      target_id: applicationId,
      details: { creator_id: application.creator_id, status: nextStatus, review_note: reviewNote || null }
    });
    if (auditError) console.error("Creator monetization audit log failed:", auditError.message);
    return new Response(JSON.stringify({
      ok: true, application: reviewed, audit_logged: !auditError
    }), { headers: cors });
  }

  if (body.action === "review_content_report") {
    const { data: adminRole, error: roleError } = await admin.schema("private").from("admin_users")
      .select("role").eq("user_id", user.id).maybeSingle();
    if (roleError || !adminRole || adminRole.role !== "owner") return fail("Developer owner access only", 403);
    const reportId = String(body.report_id || "");
    const nextStatus = String(body.status || "");
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(reportId) ||
        !["reviewing", "actioned", "dismissed"].includes(nextStatus)) return fail("Invalid report review request");
    const { data: report, error } = await admin.from("content_reports").update({
      status: nextStatus, reviewed_by: user.id, reviewed_at: new Date().toISOString()
    }).eq("id", reportId).select("id,status,target_type,target_id").single();
    if (error) return fail("Could not update content report", 500);
    await admin.schema("private").from("admin_audit_logs").insert({
      actor_id: user.id, action: "content_report_reviewed", target_type: report.target_type,
      target_id: report.target_id, details: { report_id: report.id, status: nextStatus }
    });
    return new Response(JSON.stringify({ ok: true, report }), { headers: cors });
  }

  if (body.action === "claim_owner") {
    const verifiedEmail = String(user.email || "").trim().toLowerCase();
    if (!user.email_confirmed_at || verifiedEmail !== "rkdaifilms@gmail.com") {
      return fail("Use the verified pre-approved Developer email to claim Owner access.", 403);
    }
    const { data: allowed, error: allowError } = await admin.schema("private").from("owner_bootstrap_allowlist")
      .select("email,enabled").eq("email", verifiedEmail).eq("enabled", true).maybeSingle();
    if (allowError) return fail("Could not verify Developer invitation.", 503);
    if (!allowed) {
      const { data: existingOwner } = await admin.schema("private").from("admin_users")
        .select("user_id").eq("user_id", user.id).eq("role", "owner").maybeSingle();
      if (existingOwner) return new Response(JSON.stringify({ ok: true, is_owner: true, already_owner: true }), { headers: cors });
      return fail("Developer invitation is unavailable or already claimed.", 403);
    }
    const { error: grantError } = await admin.schema("private").from("admin_users")
      .upsert({ user_id: user.id, role: "owner" }, { onConflict: "user_id" });
    if (grantError) return fail("Could not activate Developer access.", 500);
    await admin.schema("private").from("owner_bootstrap_allowlist")
      .update({ enabled: false }).eq("email", verifiedEmail);
    await admin.schema("private").from("admin_audit_logs").insert({
      actor_id: user.id, action: "initial_owner_claimed", target_type: "admin_account",
      details: { email_verified: true, role: "owner" }
    });
    return new Response(JSON.stringify({ ok: true, is_owner: true, role: "owner" }), { headers: cors });
  }

  if (body.action === "check_owner_access") {
    const { data: ownerRole, error: ownerError } = await admin.schema("private").from("admin_users")
      .select("role").eq("user_id", user.id).maybeSingle();
    if (ownerError) return fail("Could not verify account permissions", 503);
    return new Response(JSON.stringify({
      ok: true,
      is_owner: ownerRole?.role === "owner",
      role: ownerRole?.role === "owner" ? "owner" : "creator"
    }), { headers: cors });
  }

  if (body.action === "create_coin_package") {
    const { data: adminRole, error: roleError } = await admin.schema("private").from("admin_users")
      .select("role").eq("user_id", user.id).maybeSingle();
    if (roleError || !adminRole || adminRole.role !== "owner") return fail("Developer owner access only", 403);
    const coins = Number(body.coins), price = Number(body.price);
    // Published New Star bonus schedule: 50→1, 100→2, 200→3, 500→5, 1000→10.
    // Custom coin amounts receive only the highest reached tier; clients cannot set bonus.
    const bonus = coins >= 1000 ? 10 : coins >= 500 ? 5 : coins >= 200 ? 3 : coins >= 100 ? 2 : coins >= 50 ? 1 : 0;
    const country = String(body.country_code || "").trim().toUpperCase();
    const currency = String(body.currency || "").trim().toUpperCase();
    if (!/^[A-Z]{2}$/.test(country) || !/^[A-Z]{3}$/.test(currency) ||
        !Number.isInteger(coins) || coins < 1 || coins > 10000000 ||
        !Number.isInteger(bonus) || bonus < 0 || bonus > 10000000 ||
        !Number.isFinite(price) || price <= 0 || price > 1000000) return fail("Enter valid ISO country/currency codes and package values.");
    const zeroDecimal = new Set(["BIF","CLP","DJF","GNF","ISK","JPY","KMF","KRW","PYG","RWF","UGX","UYI","VND","VUV","XAF","XOF","XPF"]);
    const threeDecimal = new Set(["BHD","IQD","JOD","KWD","LYD","OMR","TND"]);
    const decimals = zeroDecimal.has(currency) ? 0 : (threeDecimal.has(currency) ? 3 : 2);
    const amountMinor = Math.round(price * (10 ** decimals));
    if (amountMinor < 1) return fail("Package price is below the smallest supported currency unit.");
    const code = country + "_WEB_" + coins + "_" + bonus + "_" + amountMinor;
    const { data: packageRow, error } = await admin.from("coin_packages").upsert({
      code, coins, bonus_coins: bonus, amount_minor: amountMinor, currency,
      country_code: country, channel: "web", active: false
    }, { onConflict: "code,currency,channel" }).select("id,code,coins,bonus_coins,amount_minor,currency,country_code,active").single();
    if (error) return fail("Could not save coin package draft", 500);
    await admin.schema("private").from("admin_audit_logs").insert({
      actor_id: user.id, action: "coin_package_draft_created", target_type: "coin_package",
      target_id: packageRow.id, details: { coins, bonus_coins: bonus, currency, country_code: country, active: false }
    });
    return new Response(JSON.stringify({ ok: true, package: packageRow, message: "Draft saved; purchase remains disabled." }), { headers: cors });
  }

  if (body.action === "save_monetization_settings") {
    const { data: adminRole, error: roleError } = await admin.schema("private").from("admin_users")
      .select("role").eq("user_id", user.id).maybeSingle();
    if (roleError || !adminRole || adminRole.role !== "owner") return fail("Developer owner access only", 403);
    const ids = {
      admob_app_id: String(body.admob_app_id || "").trim(),
      admob_banner_id: String(body.admob_banner_id || "").trim(),
      admob_interstitial_id: String(body.admob_interstitial_id || "").trim(),
      admob_rewarded_id: String(body.admob_rewarded_id || "").trim()
    };
    for (const [key, value] of Object.entries(ids)) {
      if (value.length > 180 || (value && !/^ca-app-pub-[0-9]{10,25}[~/][0-9]{4,25}$/.test(value))) return fail("Invalid AdMob identifier: " + key);
    }
    const config = { ...ids, admob_configured: Boolean(ids.admob_app_id), payout_setup_status: "Use official Payoneer/bank portal; not connected in-app" };
    const { error } = await admin.schema("private").from("monetization_settings").upsert({
      setting_key: "provider_config", setting_value: config, updated_by: user.id, updated_at: new Date().toISOString()
    }, { onConflict: "setting_key" });
    if (error) return fail("Could not save provider configuration", 500);
    await admin.schema("private").from("admin_audit_logs").insert({
      actor_id: user.id, action: "monetization_settings_updated", target_type: "provider_config", details: { admob_configured: config.admob_configured }
    });
    return new Response(JSON.stringify({ ok: true, monetization: config }), { headers: cors });
  }

  if (body.action === "send_gift") {
    // Fail closed: this legacy action only supported video gifts and is not a
    // verified unified route for Video, LIVE (hosting + battle), and LIVE Gaming.
    // Never let an authenticated browser invoke a service-role wallet mutation here.
    return fail("Gift sending is not enabled: secure routes for all three gift systems and provider readiness are still pending.", 503);
  }

  if (body.action === "request_withdrawal") {
    // No payout request is accepted until an adult-managed Payoneer account,
    // provider contract, API credentials, recipient verification and webhook
    // settlement flow have all been configured and tested.
    return fail("Creator payouts are not enabled yet. No payout request or transfer was created.", 503);
  }

  return fail("Unknown action");
});