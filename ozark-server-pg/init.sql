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

  PRIMARY KEY (document_id, revision) INCLUDE (deleted)
);

CREATE INDEX ON document_revisions (document_id);
CREATE INDEX ON document_revisions USING GIN (document);
CREATE INDEX ON document_revisions (author);

CREATE OR REPLACE FUNCTION notify_document_revision_inserted()
  RETURNS trigger AS $$
DECLARE
BEGIN
  PERFORM pg_notify('document_revision_inserted', json_build_array(NEW.document_id, NEW.revision)::text);
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER document_revision_inserted
AFTER INSERT ON document_revisions
FOR EACH ROW
EXECUTE FUNCTION notify_document_revision_inserted();

CREATE OR REPLACE VIEW latest_revisions AS
SELECT DISTINCT ON (document_id) *
FROM document_revisions
ORDER BY document_id, revision DESC;

CREATE OR REPLACE VIEW documents AS
SELECT *
FROM latest_revisions
WHERE deleted = false;

INSERT INTO document_revisions
(document_id, revision, document, type, author, auth)
VALUES (
  'SYSTEM',
  '1970-01-01 00:00:00Z',
  '{}'::jsonb,
  'user',
  'SYSTEM',
  '{"SYSTEM":{"read":true, "write":true}}'::jsonb
);
