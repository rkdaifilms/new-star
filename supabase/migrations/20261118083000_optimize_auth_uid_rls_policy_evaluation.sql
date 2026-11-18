-- Optimize auth.uid() evaluation in existing public RLS policies without changing access rules.
DO $$
DECLARE r record; v_using text; v_check text; v_stmt text;
BEGIN
  FOR r IN
    SELECT schemaname, tablename, policyname, cmd, qual, with_check
    FROM pg_policies
    WHERE schemaname = 'public'
  LOOP
    v_using := r.qual;
    v_check := r.with_check;
    IF v_using IS NOT NULL THEN
      v_using := replace(v_using, '(auth.uid())', '((select auth.uid()))');
      v_using := replace(v_using, 'auth.uid() =', '(select auth.uid()) =');
    END IF;
    IF v_check IS NOT NULL THEN
      v_check := replace(v_check, '(auth.uid())', '((select auth.uid()))');
      v_check := replace(v_check, 'auth.uid() =', '(select auth.uid()) =');
    END IF;
    IF v_using IS DISTINCT FROM r.qual OR v_check IS DISTINCT FROM r.with_check THEN
      v_stmt := format('ALTER POLICY %I ON %I.%I', r.policyname, r.schemaname, r.tablename);
      IF r.cmd IN ('SELECT','DELETE') THEN
        v_stmt := v_stmt || format(' USING (%s)', v_using);
      ELSIF r.cmd = 'INSERT' THEN
        v_stmt := v_stmt || format(' WITH CHECK (%s)', v_check);
      ELSIF r.cmd IN ('UPDATE','ALL') THEN
        v_stmt := v_stmt || format(' USING (%s) WITH CHECK (%s)', v_using, v_check);
      ELSE
        CONTINUE;
      END IF;
      EXECUTE v_stmt;
    END IF;
  END LOOP;
END $$;