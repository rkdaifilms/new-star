-- Do not expose the next cohort until the current cohort has been evaluated.
do $migration$
declare
  v_definition text;
  v_old text := 'or coalesce(ds.status,''active'')=''active''';
  v_new text := 'or (coalesce(ds.status,''active'')=''active'' and ds.stage_reached_at is null)';
begin
  select pg_get_functiondef(
    'public.get_recommended_video_feed_v2(integer,integer,text,text,text)'::regprocedure
  ) into v_definition;

  if position(v_old in v_definition)=0 then
    raise exception 'Expected short distribution filter was not found; no function changed';
  end if;

  v_definition:=replace(v_definition,v_old,v_new);
  execute v_definition;
end;
$migration$;
