-- plan.md 2.2 allowed only pending/accepted/expired, which conflates two different answers:
-- nobody replied, and someone said no. The purge rule needs to tell them apart, and a declined
-- request must be kept rather than deleted so it cannot simply be sent again.
alter table friend_requests drop constraint friend_requests_status_check;

alter table friend_requests
    add constraint friend_requests_status_check
        check (status in ('pending', 'accepted', 'declined', 'expired'));
