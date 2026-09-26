#!/bin/sh
set -eu
# psql variables quote the password as an SQL literal, not as executable SQL.
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set=ON_ERROR_STOP=1 <<'SQL'
\getenv app_password APP_PASSWORD
CREATE ROLE helpdesk LOGIN PASSWORD :'app_password' NOSUPERUSER NOCREATEDB NOCREATEROLE;
REVOKE ALL ON DATABASE helpdesk FROM PUBLIC;
GRANT CONNECT ON DATABASE helpdesk TO helpdesk;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO helpdesk;
SQL
