create index if not exists live_room_invites_inviter_created_idx
  on public.live_room_invites (inviter_id, created_at desc);

create index if not exists live_room_invites_room_host_guest_status_idx
  on public.live_room_invites (room_id, inviter_id, invitee_id, status, expires_at);
