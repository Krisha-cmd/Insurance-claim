-- Local development only: one database + owner per service, user = password = database name (the defaults in
-- each service's application.yml). Safe to run again. On AWS, Terraform's platform stack does this instead.
--   docker exec -i claims-postgres psql -U login -d login < local/create-databases.sql
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', n, n)
FROM unnest(ARRAY['policy', 'claims', 'assessment', 'approval', 'payment']) AS n
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = n)\gexec

SELECT format('CREATE DATABASE %I OWNER %I', n, n)
FROM unnest(ARRAY['policy', 'claims', 'assessment', 'approval', 'payment']) AS n
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = n)\gexec
