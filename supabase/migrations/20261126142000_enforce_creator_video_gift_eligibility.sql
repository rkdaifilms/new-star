CREATE OR REPLACE FUNCTION public.send_creator_gift_secure(
  p_sender_id uuid,
  p_video_id text,
  p_gift_name text,
  p_coins integer,
  p_request_id uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
DECLARE
  v_sender_id text;
  v_creator_id text;
  v_wallet_id uuid;
  v_balance integer;
  v_expected_coins integer;
  v_fee integer;
  v_net integer;
  v_basis_points integer;
  v_private boolean;
  v_followers bigint := 0;
  v_watch_seconds bigint := 0;
  v_short_views bigint := 0;
  v_age_verified boolean := false;
  v_contact_verified boolean := false;
  v_approved boolean := false;
BEGIN
  IF p_sender_id IS NULL OR p_request_id IS NULL OR p_video_id IS NULL THEN RAISE EXCEPTION 'Invalid gift request.'; END IF;
  v_sender_id := p_sender_id::text;
  v_expected_coins := CASE p_gift_name
    WHEN 'Rose' THEN 1 WHEN 'Coffee' THEN 5 WHEN 'Heart' THEN 10 WHEN 'Glasses' THEN 20
    WHEN 'TikTok' THEN 50 WHEN 'Car' THEN 100 WHEN 'Ring' THEN 200 WHEN 'Yacht' THEN 500
    WHEN 'Lion' THEN 1000 WHEN 'Diamond' THEN 1500 WHEN 'Crown' THEN 2000 WHEN 'Jet' THEN 3000
    WHEN 'Castle' THEN 5000 WHEN 'Dragon' THEN 10000 WHEN 'Galaxy' THEN 20000 WHEN 'Universe' THEN 50000 ELSE NULL END;
  IF v_expected_coins IS NULL OR p_coins <> v_expected_coins THEN RAISE EXCEPTION 'Gift name or coin price is invalid.'; END IF;
  PERFORM pg_advisory_xact_lock(hashtext(p_request_id::text));
  IF EXISTS (SELECT 1 FROM public.coin_transactions WHERE request_id = p_request_id) THEN
    SELECT coin_balance INTO v_balance FROM public.user_wallets WHERE user_id = v_sender_id LIMIT 1;
    RETURN jsonb_build_object('success', true, 'duplicate', true, 'remaining_coins', COALESCE(v_balance, 0));
  END IF;
  SELECT user_id INTO v_creator_id FROM public.videos
  WHERE id::text = p_video_id AND COALESCE(processing_status, 'published') = 'published'
    AND COALESCE(visibility, 'public') IN ('public', 'unlisted') AND COALESCE(audience_type, 'all') = 'all' LIMIT 1;
  IF v_creator_id IS NULL THEN RAISE EXCEPTION 'Video is unavailable for gifts.'; END IF;
  IF v_creator_id = v_sender_id THEN RAISE EXCEPTION 'You cannot gift your own video.'; END IF;
  SELECT count(DISTINCT f.follower_id)::bigint INTO v_followers FROM public.followers f
  JOIN auth.users u ON u.id::text = f.follower_id WHERE f.following_id = v_creator_id AND f.follower_id <> v_creator_id;
  SELECT COALESCE(sum(d.watched_seconds), 0)::bigint INTO v_watch_seconds FROM private.creator_watch_daily d
  JOIN public.videos v ON v.id = d.video_id WHERE d.creator_id = v_creator_id::uuid
    AND d.watch_date >= ((now() AT TIME ZONE 'utc')::date - 59) AND d.watch_date <= (now() AT TIME ZONE 'utc')::date
    AND v.duration_seconds > 60 AND v.duration_seconds <= 10800 AND COALESCE(v.video_type, 'long') = 'long'
    AND COALESCE(v.processing_status, 'published') = 'published' AND COALESCE(v.visibility, 'public') = 'public'
    AND COALESCE(v.audience_type, 'all') = 'all';
  SELECT count(*)::bigint INTO v_short_views FROM private.video_view_dedupe d JOIN public.videos v ON v.id = d.video_id
  WHERE d.creator_id = v_creator_id::uuid AND d.view_date >= ((now() AT TIME ZONE 'utc')::date - 59)
    AND d.view_date <= (now() AT TIME ZONE 'utc')::date AND d.video_type = 'short'
    AND v.duration_seconds BETWEEN 1 AND 60 AND COALESCE(v.video_type, 'short') = 'short'
    AND COALESCE(v.processing_status, 'published') = 'published' AND COALESCE(v.visibility, 'public') = 'public'
    AND COALESCE(v.audience_type, 'all') = 'all';
  SELECT COALESCE(bool_or(status = 'approved'), false) INTO v_age_verified FROM private.creator_age_verifications WHERE creator_id = v_creator_id::uuid;
  SELECT (email_confirmed_at IS NOT NULL OR phone_confirmed_at IS NOT NULL) INTO v_contact_verified FROM auth.users WHERE id = v_creator_id::uuid;
  v_contact_verified := COALESCE(v_contact_verified, false);
  SELECT EXISTS (SELECT 1 FROM public.creator_monetization_applications WHERE creator_id = v_creator_id::uuid AND status = 'approved') INTO v_approved;
  IF NOT (v_followers >= 5000 AND (v_watch_seconds >= 3600000 OR v_short_views >= 300000)
    AND v_age_verified AND v_contact_verified AND v_approved) THEN
    RAISE EXCEPTION 'Creator is not eligible for video gifts yet.';
  END IF;
  SELECT COALESCE(is_private, false) INTO v_private FROM public.profiles WHERE user_id = v_creator_id LIMIT 1;
  IF COALESCE(v_private, false) AND NOT EXISTS (SELECT 1 FROM public.followers f WHERE f.follower_id = v_sender_id AND f.following_id = v_creator_id) THEN
    RAISE EXCEPTION 'Follow request approval is required before gifting this creator.';
  END IF;
  SELECT basis_points INTO v_basis_points FROM private.commission_rules WHERE rule_code = 'video_gifts' AND status = 'active' ORDER BY updated_at DESC LIMIT 1;
  IF v_basis_points IS NULL OR v_basis_points < 0 OR v_basis_points > 10000 THEN RAISE EXCEPTION 'Video gift commission is not configured or enabled.'; END IF;
  SELECT id, coin_balance INTO v_wallet_id, v_balance FROM public.user_wallets WHERE user_id = v_sender_id LIMIT 1 FOR UPDATE;
  IF v_wallet_id IS NULL THEN RAISE EXCEPTION 'Wallet not found.'; END IF;
  IF COALESCE(v_balance, 0) < p_coins THEN RAISE EXCEPTION 'Insufficient coins.'; END IF;
  UPDATE public.user_wallets SET coin_balance = coin_balance - p_coins WHERE id = v_wallet_id AND coin_balance >= p_coins RETURNING coin_balance INTO v_balance;
  IF NOT FOUND THEN RAISE EXCEPTION 'Coin deduction failed.'; END IF;
  INSERT INTO public.wallet_ledger(user_id, entry_type, coin_delta, source_id, idempotency_key)
  VALUES (p_sender_id, 'gift_spend', -p_coins, p_request_id::text, 'creator-gift:' || p_request_id::text);
  v_fee := floor((p_coins::numeric * v_basis_points) / 10000)::integer; v_net := p_coins - v_fee;
  INSERT INTO public.coin_transactions(sender_id, receiver_id, gift_name, coins_deducted, request_id) VALUES (v_sender_id, v_creator_id, p_gift_name, p_coins, p_request_id);
  INSERT INTO public.creator_gift_earnings(request_id, creator_id, sender_id, video_id, gift_name, coins, gross_coins, platform_fee_coins)
  VALUES (p_request_id, v_creator_id, v_sender_id, p_video_id, p_gift_name, v_net, p_coins, v_fee);
  INSERT INTO private.platform_coin_ledger(request_id, sender_id, creator_id, video_id, gross_coins, platform_fee_coins, creator_net_coins, commission_basis_points)
  VALUES (p_request_id, p_sender_id, v_creator_id::uuid, p_video_id, p_coins, v_fee, v_net, v_basis_points);
  RETURN jsonb_build_object('success', true, 'duplicate', false, 'remaining_coins', v_balance,
    'gift_name', p_gift_name, 'coins_deducted', p_coins, 'platform_fee_coins', v_fee,
    'creator_net_coins', v_net, 'commission_basis_points', v_basis_points);
END;
$function$;
