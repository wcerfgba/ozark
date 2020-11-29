CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS document_revisions (
  document_id TEXT NOT NULL DEFAULT gen_random_uuid(),
  revision TIMESTAMPTZ NOT NULL DEFAULT now(),
  document JSONB NOT NULL,
  type TEXT NOT NULL,
  author TEXT NOT NULL,
  -- The author is a document_id.
  deleted BOOLEAN NOT NULL DEFAULT false,
  auth JSONB NOT NULL,
  -- The auth object is structured as follows:
  --   {
  --     <user or group document id> : {
  --       "read" : <true or false> ,
  --       "write" : <true or false>
  --     }
  --   }

  PRIMARY KEY (document_id, revision)
);
