-- Correct payment verification mismatch handling.
-- A PL/pgSQL exception rolls back prior UPDATEs in the same transaction, so a mismatch
-- must return a review result instead of raising after setting status='review'.
CREATE OR REPLACE FUNCTION public.complete_coin_purchase(
  p_order_id uuid,
  p_provider_reference text,
  p_verified_amount_minor bigint,
  p_verified_currency text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $function$
DECLARE
  v_order public.payment_orders%rowtype;
  v_wallet public.user_wallets%rowtype;
  v_username text;
  v_credit integer;
  v_ledger_rows integer;
  v_reference text;
BEGIN
  SELECT * INTO v_order
  FROM public.payment_orders
  WHERE id = p_order_id
  FOR UPDATE;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Payment order not found';
  END IF;

  IF v_order.status = 'paid' THEN
    RETURN jsonb_build_object('success', true, 'duplicate', true, 'order_id', p_order_id);
  END IF;

  IF v_order.status NOT IN ('pending', 'review') THEN
    RAISE EXCEPTION 'Payment order is not payable';
  END IF;

  v_reference := NULLIF(btrim(p_provider_reference), '');
  IF v_reference IS NULL THEN
    RAISE EXCEPTION 'Missing provider reference';
  END IF;

  IF p_verified_amount_minor IS NULL
     OR p_verified_currency IS NULL
     OR NULLIF(btrim(p_verified_currency), '') IS NULL
     OR v_order.amount_minor IS DISTINCT FROM p_verified_amount_minor
     OR upper(btrim(v_order.currency)) IS DISTINCT FROM upper(btrim(p_verified_currency)) THEN
    UPDATE public.payment_orders
    SET status = 'review', updated_at = now()
    WHERE id = p_order_id;

    RETURN jsonb_build_object(
      'success', false,
      'review_required', true,
      'error', 'Verified amount or currency mismatch',
      'order_id', p_order_id
    );
  END IF;

  v_credit := coalesce(v_order.coins, 0) + coalesce(v_order.bonus_coins, 0);
  IF v_credit <= 0 THEN
    RAISE EXCEPTION 'Order contains no coins';
  END IF;

  INSERT INTO public.wallet_ledger(
    user_id, entry_type, coin_delta, amount_minor, currency, source_id, idempotency_key
  ) VALUES (
    v_order.user_id, 'coin_purchase', v_credit, v_order.amount_minor,
    v_order.currency, p_order_id::text, 'coin-purchase:' || p_order_id::text
  )
  ON CONFLICT (idempotency_key) DO NOTHING;

  GET DIAGNOSTICS v_ledger_rows = ROW_COUNT;
  IF v_ledger_rows <> 1 THEN
    RAISE EXCEPTION 'Coin purchase ledger entry already exists; manual review required';
  END IF;

  SELECT username INTO v_username
  FROM public.profiles
  WHERE user_id = v_order.user_id::text
  LIMIT 1;

  INSERT INTO public.user_wallets(user_id, username, real_balance, coin_balance)
  VALUES (v_order.user_id::text, coalesce(v_username, 'creator'), 0, 0)
  ON CONFLICT (user_id) DO NOTHING;

  SELECT * INTO v_wallet
  FROM public.user_wallets
  WHERE user_id = v_order.user_id::text
  FOR UPDATE;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Wallet could not be created';
  END IF;

  UPDATE public.user_wallets
  SET coin_balance = coalesce(coin_balance, 0) + v_credit
  WHERE id = v_wallet.id
  RETURNING * INTO v_wallet;

  UPDATE public.payment_orders
  SET status = 'paid',
      provider_reference = v_reference,
      paid_at = now(),
      updated_at = now()
  WHERE id = p_order_id;

  RETURN jsonb_build_object(
    'success', true,
    'duplicate', false,
    'order_id', p_order_id,
    'coins_credited', v_credit,
    'coin_balance', v_wallet.coin_balance
  );
END;
$function$;

REVOKE ALL ON FUNCTION public.complete_coin_purchase(uuid, text, bigint, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.complete_coin_purchase(uuid, text, bigint, text) TO service_role;