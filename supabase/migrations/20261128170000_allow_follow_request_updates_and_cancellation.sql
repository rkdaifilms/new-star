revoke update on table public.follow_requests from authenticated;
grant update (status) on table public.follow_requests to authenticated;

drop policy if exists follow_requests_update_target_pending on public.follow_requests;
create policy follow_requests_update_target_pending
on public.follow_requests
for update
to authenticated
using (
  (select auth.uid())::text = target_user_id
  and status = 'pending'
)
with check (
  (select auth.uid())::text = target_user_id
  and status in ('accepted', 'rejected')
);

drop policy if exists follow_requests_delete_requester_pending on public.follow_requests;
create policy follow_requests_delete_requester_pending
on public.follow_requests
for delete
to authenticated
using (
  (select auth.uid())::text = requester_id
  and status = 'pending'
);
