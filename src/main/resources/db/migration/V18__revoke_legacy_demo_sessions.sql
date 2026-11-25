-- The demo login feature has been removed. Invalidate any session created by
-- its two fixed accounts without deleting the users or their existing data.
DELETE FROM benchmark_sessions
WHERE user_id IN (
    SELECT id
    FROM benchmark_users
    WHERE id IN ('demo_admin', 'demo_researcher')
       OR LOWER(email) IN ('admin@cryptobench.local', 'researcher@cryptobench.local')
);
