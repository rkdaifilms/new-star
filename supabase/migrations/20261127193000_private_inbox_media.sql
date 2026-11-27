insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
  'message-media',
  'message-media',
  false,
  20971520,
  array['image/jpeg','image/png','image/webp','image/gif','image/heic','image/heif',
        'video/mp4','video/quicktime','video/webm','audio/mpeg','audio/mp4',
        'audio/aac','audio/wav','application/pdf']
)
on conflict (id) do update
set public = false,
    file_size_limit = 20971520,
    allowed_mime_types = excluded.allowed_mime_types;

drop policy if exists "Inbox members upload their own private media" on storage.objects;
create policy "Inbox members upload their own private media"
on storage.objects
for insert
to authenticated
with check (
  bucket_id = 'message-media'
  and split_part(name, '/', 1) = (select auth.uid())::text
);

drop policy if exists "Inbox participants read private media" on storage.objects;
create policy "Inbox participants read private media"
on storage.objects
for select
to authenticated
using (
  bucket_id = 'message-media'
  and (
    split_part(name, '/', 1) = (select auth.uid())::text
    or exists (
      select 1
      from public.messages m
      where m.media_url = storage.objects.name
        and (
          m.sender_id = (select auth.uid())
          or m.recipient_id = (select auth.uid())
        )
    )
  )
);

drop policy if exists "Owners delete their private inbox media" on storage.objects;
create policy "Owners delete their private inbox media"
on storage.objects
for delete
to authenticated
using (
  bucket_id = 'message-media'
  and split_part(name, '/', 1) = (select auth.uid())::text
);

notify pgrst, 'reload schema';
