-- Run as the maintenance owner, against the intended EXISTING PostgreSQL 18 database,
-- after the sole current init baseline. No database/server is created by this file.
-- Passwords are deliberately absent. Set LOGIN credentials with psql \password on the host.
\set ON_ERROR_STOP on
\if :{?wallet_owner}
\else
\set wallet_owner sw_wallet_owner
\endif
\if :{?approval_owner}
\else
\set approval_owner sw_approval_owner
\endif
\if :{?wallet_runtime}
\else
\set wallet_runtime sw_wallet_runtime
\endif
\if :{?approval_runtime}
\else
\set approval_runtime sw_approval_runtime
\endif

-- Reject accidental aliasing or use of the administrative connection as a runtime role.
SELECT (:'wallet_owner'<>:'approval_owner' AND :'wallet_runtime'<>:'approval_runtime'
    AND :'wallet_owner' NOT IN (:'wallet_runtime',:'approval_runtime')
    AND :'approval_owner' NOT IN (:'wallet_runtime',:'approval_runtime')
    AND current_user NOT IN (:'wallet_owner',:'approval_owner',:'wallet_runtime',:'approval_runtime')) AS distinct_roles \gset
\if :distinct_roles
\else
\echo 'Use four distinct roles and a separate maintenance connection.'
\quit 1
\endif

BEGIN;
SELECT format('CREATE ROLE %I NOLOGIN', name)
FROM (VALUES (:'wallet_owner'),(:'approval_owner'),(:'wallet_runtime'),(:'approval_runtime')) roles(name)
WHERE NOT EXISTS(SELECT 1 FROM pg_catalog.pg_roles WHERE rolname=name) \gexec

ALTER ROLE :"wallet_owner" NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE :"approval_owner" NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE :"wallet_runtime" LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE :"approval_runtime" LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
SELECT format('REVOKE %I FROM %I', granted.rolname, member.rolname)
FROM pg_catalog.pg_auth_members memberships
JOIN pg_catalog.pg_roles granted ON granted.oid=memberships.roleid
JOIN pg_catalog.pg_roles member ON member.oid=memberships.member
WHERE member.rolname IN (:'wallet_runtime',:'approval_runtime') \gexec

ALTER SCHEMA public OWNER TO :"wallet_owner";
ALTER SCHEMA approval OWNER TO :"approval_owner";
REVOKE ALL ON SCHEMA approval FROM PUBLIC, :"wallet_runtime";
REVOKE CREATE ON SCHEMA public FROM PUBLIC, :"wallet_runtime", :"approval_runtime";

SELECT format('ALTER %s %I.%I OWNER TO %I',case c.relkind when 'S' then 'SEQUENCE' when 'v' then 'VIEW' when 'm' then 'MATERIALIZED VIEW' else 'TABLE' end,
    n.nspname,c.relname,case n.nspname when 'approval' then :'approval_owner' else :'wallet_owner' end)
FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname IN ('public','approval') AND c.relkind IN ('r','p','S','v','m')
  -- SERIAL/IDENTITY sequence ownership follows its table; PostgreSQL rejects direct reassignment.
  AND (c.relkind<>'S' OR NOT EXISTS(
      SELECT 1 FROM pg_catalog.pg_depend d WHERE d.classid='pg_class'::regclass AND d.objid=c.oid
        AND d.refclassid='pg_class'::regclass AND d.deptype IN ('a','i'))) \gexec
SELECT format('ALTER FUNCTION %s OWNER TO %I',p.oid::regprocedure,case n.nspname when 'approval' then :'approval_owner' else :'wallet_owner' end)
FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace
WHERE n.nspname IN ('public','approval') AND p.prokind='f' \gexec

REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC, :"approval_runtime";
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC, :"approval_runtime";
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC, :"approval_runtime";
REVOKE ALL ON ALL TABLES IN SCHEMA approval FROM PUBLIC, :"wallet_runtime";
REVOKE ALL ON ALL SEQUENCES IN SCHEMA approval FROM PUBLIC, :"wallet_runtime";
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA approval FROM PUBLIC, :"wallet_runtime";
GRANT USAGE ON SCHEMA public TO :"wallet_runtime";
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO :"wallet_runtime";
GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO :"wallet_runtime";
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO :"wallet_runtime";
GRANT USAGE ON SCHEMA approval TO :"approval_runtime";
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA approval TO :"approval_runtime";
GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA approval TO :"approval_runtime";
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA approval TO :"approval_runtime";

-- PGMQ belongs to Wallet orchestration. The authority has no queue permission.
REVOKE ALL ON SCHEMA pgmq FROM PUBLIC, :"approval_runtime";
REVOKE ALL ON ALL TABLES IN SCHEMA pgmq FROM PUBLIC, :"approval_runtime";
REVOKE ALL ON ALL SEQUENCES IN SCHEMA pgmq FROM PUBLIC, :"approval_runtime";
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA pgmq FROM PUBLIC, :"approval_runtime";
GRANT USAGE ON SCHEMA pgmq TO :"wallet_runtime";
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA pgmq TO :"wallet_runtime";
GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA pgmq TO :"wallet_runtime";
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA pgmq TO :"wallet_runtime";
SELECT format('GRANT CONNECT ON DATABASE %I TO %I,%I',current_database(),:'wallet_runtime',:'approval_runtime') \gexec
SELECT format('REVOKE CREATE ON DATABASE %I FROM %I,%I',current_database(),:'wallet_runtime',:'approval_runtime') \gexec

ALTER DEFAULT PRIVILEGES FOR ROLE :"wallet_owner" IN SCHEMA public REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE :"wallet_owner" IN SCHEMA public REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE :"wallet_owner" IN SCHEMA public GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO :"wallet_runtime";
ALTER DEFAULT PRIVILEGES FOR ROLE :"wallet_owner" IN SCHEMA public GRANT USAGE,SELECT ON SEQUENCES TO :"wallet_runtime";
ALTER DEFAULT PRIVILEGES FOR ROLE :"wallet_owner" IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO :"wallet_runtime";
ALTER DEFAULT PRIVILEGES FOR ROLE :"approval_owner" IN SCHEMA approval REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE :"approval_owner" IN SCHEMA approval REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE :"approval_owner" IN SCHEMA approval GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO :"approval_runtime";
ALTER DEFAULT PRIVILEGES FOR ROLE :"approval_owner" IN SCHEMA approval GRANT USAGE,SELECT ON SEQUENCES TO :"approval_runtime";
ALTER DEFAULT PRIVILEGES FOR ROLE :"approval_owner" IN SCHEMA approval GRANT EXECUTE ON FUNCTIONS TO :"approval_runtime";
COMMIT;
