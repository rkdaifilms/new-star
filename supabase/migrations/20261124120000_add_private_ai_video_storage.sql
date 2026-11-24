-- Private storage for user-generated New Star Creator Studio videos.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('new-star-ai-videos', 'new-star-ai-videos', false, 52428800, array['video/mp4'])
on conflict (id) do update
set public = false,
    file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;

drop policy if exists "Users can read their own New Star AI videos" on storage.objects;
create policy "Users can read their own New Star AI videos"
on storage.objects
for select
to authenticated
using (
  bucket_id = 'new-star-ai-videos'
  and (storage.foldername(name))[1] = (select auth.uid())::text
);
