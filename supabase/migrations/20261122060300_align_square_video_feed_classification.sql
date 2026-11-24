-- Match feed type classification with the app's square/portrait/landscape rules.
do $migration$
declare
  v_name text;
  v_def text;
  v_old_inline text := 'else coalesce(v.video_type,case when v.width is not null and v.height is not null and v.width>v.height then ''long'' else ''short'' end)';
  v_old_multiline text := 'else coalesce(v.video_type,'||chr(10)||'        case when v.width is not null and v.height is not null and v.width>v.height then ''long'' else ''short'' end)';
  v_new text := 'else case when v.video_type in (''short'',''long'') then v.video_type when v.height is not null and v.width is not null and v.height>v.width then ''short'' else ''long'' end';
  v_names text[] := array[
    'public.get_recommended_video_feed_v2(integer,integer,text,text,text)',
    'public.record_video_feed_impression(uuid,text)'
  ];
begin
  foreach v_name in array v_names loop
    execute format('select pg_get_functiondef(%L::regprocedure)',v_name) into v_def;
    if position(v_old_inline in v_def)=0 and position(v_old_multiline in v_def)=0 then
      raise exception 'Video type classification pattern not found in %',v_name;
    end if;
    v_def:=replace(v_def,v_old_inline,v_new);
    v_def:=replace(v_def,v_old_multiline,v_new);
    execute v_def;
  end loop;
end;
$migration$;
