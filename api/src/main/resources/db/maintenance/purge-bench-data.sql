-- Removes load-harness data from a LOCAL development database.
--
--   docker exec -i syamdev-data-postgres-1 psql -U "$POSTGRES_SUPERUSER" -d "$SHUSH_DB_NAME" \
--     -v ON_ERROR_STOP=1 -v cutoff="'2026-09-08 04:00:00+00'" \
--     < api/src/main/resources/db/maintenance/purge-bench-data.sql
--
-- Deliberately NOT a Flyway migration. Migrations change the shape of the schema and run
-- everywhere, forever; this deletes rows on one machine because a benchmark filled it up.
-- Putting it in db/migration would replay it against every future database, including one it
-- has no business touching.
--
-- Everything created at or after :cutoff is kept. Set it to the moment the last benchmark
-- finished, and check the histogram first -- the runs stand out by message count:
--
--   select date_trunc('hour', created_at), count(*) from conversations group by 1 order by 1;

BEGIN;

CREATE TEMP TABLE doomed AS
  SELECT id FROM conversations WHERE created_at < :cutoff::timestamptz;

SELECT count(*) AS conversations_to_delete FROM doomed;

-- Children first: there are foreign keys, and no ON DELETE CASCADE. That is the right default
-- for a system of record -- a cascade makes deleting a conversation quietly delete its
-- messages, which is exactly the accident you want the database to refuse.
DELETE FROM messages                  WHERE conversation_id IN (SELECT id FROM doomed);
DELETE FROM media_objects             WHERE conversation_id IN (SELECT id FROM doomed);
DELETE FROM friend_requests           WHERE conversation_id IN (SELECT id FROM doomed);
DELETE FROM friendships               WHERE conversation_id IN (SELECT id FROM doomed);
DELETE FROM reports                   WHERE conversation_id IN (SELECT id FROM doomed);
DELETE FROM conversation_participants WHERE conversation_id IN (SELECT id FROM doomed);
DELETE FROM conversations             WHERE id IN (SELECT id FROM doomed);

-- Accounts left with nothing. Decided by what survived rather than by created_at, because the
-- harness runs and real browser sessions overlap in time and an account still attached to a
-- surviving conversation must not be deleted whenever it happened to be created.
CREATE TEMP TABLE orphans AS
  SELECT u.id FROM users u
  WHERE NOT EXISTS (SELECT 1 FROM conversation_participants p WHERE p.user_id = u.id)
    AND NOT EXISTS (SELECT 1 FROM friendships f WHERE f.user_a_id = u.id OR f.user_b_id = u.id)
    AND NOT EXISTS (SELECT 1 FROM friend_requests r WHERE r.from_user_id = u.id OR r.to_user_id = u.id);

SELECT count(*) AS users_to_delete FROM orphans;

DELETE FROM user_interests WHERE user_id IN (SELECT id FROM orphans);
DELETE FROM device_tokens  WHERE user_id IN (SELECT id FROM orphans);
DELETE FROM blocks         WHERE blocker_id IN (SELECT id FROM orphans)
                              OR blocked_id IN (SELECT id FROM orphans);
DELETE FROM invite_links   WHERE created_by IN (SELECT id FROM orphans);
DELETE FROM users          WHERE id IN (SELECT id FROM orphans);

COMMIT;

SELECT relname, n_live_tup FROM pg_stat_user_tables ORDER BY n_live_tup DESC;
