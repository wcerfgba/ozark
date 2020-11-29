CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS document_revisions (
  document_id TEXT NOT NULL DEFAULT gen_random_uuid(),
  revision TIMESTAMPTZ NOT NULL,
  document JSONB NOT NULL,
  type TEXT NOT NULL,
  author TEXT NOT NULL,
  deleted BOOLEAN NOT NULL DEFAULT false,
  auth JSONB NOT NULL,
  -- The auth object is structured as follows:
  --   {
  --     <user or group document id> : {
  --       "read" : <true or false> ,
  --       "write" : <true or false>
  --     }
  --   }

  PRIMARY KEY (document_id, revision),
  FOREIGN KEY author REFERENCES document_revisions (document_id)
);
