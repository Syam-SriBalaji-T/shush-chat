-- Browser origins allowed to call this API.
--
-- A row here, not a redeploy. Adding a frontend -- a new port during development, a preview
-- deployment, a second domain -- is a fact about the world rather than a fact about the code,
-- and baking it into a config file means the smallest possible change costs a full build and
-- restart of every replica.
--
-- Empty is not "allow everything". A cluster with no rows permits no cross-origin browser
-- calls at all, which is correct: the app and the API are served from one origin through
-- nginx, so nothing in the normal path needs an entry here.

CREATE TABLE cors_origins (
    -- The origin exactly as a browser sends it in the Origin header: scheme, host, port, no
    -- trailing slash and no path. Stored as the key because that is what it is compared to.
    origin      text PRIMARY KEY,
    note        text,
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- The Next.js dev server. `next dev` runs on its own port and talks to the API directly rather
-- than through nginx, so it is the one origin that genuinely is cross-origin day to day.
INSERT INTO cors_origins (origin, note) VALUES
    ('http://localhost:3000', 'next dev'),
    ('http://127.0.0.1:3000', 'next dev, when the browser resolves it this way')
ON CONFLICT (origin) DO NOTHING;
