package com.surprising.wallet.common.security;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/** Startup-only PostgreSQL catalog checks. Never reads wallet business data or edits roles/grants. */
public final class DatabaseRoleIsolation {
    private DatabaseRoleIsolation() { }
    public static void authority(DataSource source) throws SQLException {
        try(var connection=source.getConnection()) {
            restricted(connection);
            require(check(connection,"select has_schema_privilege(current_user,'approval','USAGE') and not has_schema_privilege(current_user,'approval','CREATE')"),"approval runtime needs USAGE without DDL on its independently owned schema");
            require(check(connection,"""
                    select not exists(select 1 from pg_catalog.pg_class c
                        join pg_catalog.pg_namespace n on n.oid=c.relnamespace
                        where n.nspname not in ('approval','pg_catalog','information_schema')
                          and n.nspname not like 'pg_toast%' and n.nspname not like 'pg_temp_%'
                          and c.relkind in ('r','p','v','m','f','S')
                          and has_schema_privilege(current_user,n.oid,'USAGE')
                          and case when c.relkind='S' then has_sequence_privilege(current_user,c.oid,'USAGE,SELECT,UPDATE')
                              else has_table_privilege(current_user,c.oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN') end)
                    """),"approval database runtime can access relations outside its own schema");
            require(check(connection,"""
                    select not exists(select 1 from pg_catalog.pg_namespace n
                        where n.nspname not in ('pg_catalog','information_schema') and n.nspname not like 'pg_temp_%'
                          and has_schema_privilege(current_user,n.oid,'CREATE'))
                    """),"approval runtime must not have application-schema CREATE privileges");
            require(check(connection,"""
                    select not exists(select 1 from pg_catalog.pg_proc p join pg_catalog.pg_namespace n on n.oid=p.pronamespace
                        where p.prosecdef and p.prorettype <> 'pg_catalog.event_trigger'::regtype
                          and n.nspname not in ('approval','pg_catalog','information_schema')
                          and has_schema_privilege(current_user,n.oid,'USAGE') and has_function_privilege(current_user,p.oid,'EXECUTE'))
                    """),"approval runtime can execute an external security-definer function");
        }
    }
    public static void wallet(DataSource source) throws SQLException {
        try(var connection=source.getConnection()) {
            restricted(connection);
            require(check(connection,"""
                    select to_regprocedure('pgmq.create(text)') is not null
                        and to_regprocedure('pgmq.send(text,jsonb,jsonb)') is not null
                        and to_regprocedure('pgmq.read(text,integer,integer,jsonb)') is not null
                        and to_regprocedure('pgmq.archive(text,bigint)') is not null
                        and to_regprocedure('pgmq.set_vt(text,bigint,integer)') is not null
                        and (select count(*) from pg_catalog.pg_attribute
                             where attrelid = (select typrelid from pg_catalog.pg_type
                                                where oid = to_regtype('pgmq.message_record'))
                               and attname in ('msg_id','read_ct','message','headers')) = 4
                    """),"wallet queue requires PGMQ headers and lease APIs");
            require(check(connection,"""
                    select not exists(select 1 from pg_catalog.pg_namespace n where n.nspname='approval'
                        and (has_schema_privilege(current_user,n.oid,'USAGE') or has_schema_privilege(current_user,n.oid,'CREATE')))
                    """),"Wallet runtime must have no privileges on the independent approval schema");
            require(check(connection,"""
                    select not exists(select 1 from pg_catalog.pg_class c join pg_catalog.pg_namespace n on n.oid=c.relnamespace
                        where n.nspname='approval' and c.relkind in ('r','p','v','m','f','S')
                          and case when c.relkind='S' then has_sequence_privilege(current_user,c.oid,'USAGE,SELECT,UPDATE')
                              else has_table_privilege(current_user,c.oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN') end)
                    """),"Wallet runtime can access independent approval relations");
        }
    }
    private static void restricted(Connection connection) throws SQLException {
        require(check(connection,"select current_setting('server_version_num')::integer >= 170000"),"wallet services require PostgreSQL 17 or newer");
        require(check(connection,"""
                select not (r.rolsuper or r.rolcreatedb or r.rolcreaterole or r.rolreplication or r.rolbypassrls)
                    and not exists(select 1 from pg_catalog.pg_auth_members m where m.member=r.oid)
                    and not exists(select 1 from pg_catalog.pg_database d where d.datname=current_database() and d.datdba=r.oid)
                    and not has_database_privilege(current_user,current_database(),'CREATE')
                from pg_catalog.pg_roles r where r.rolname=current_user
                """),"runtime must use a restricted non-owner database role without role memberships or administrative/CREATE privileges");
    }
    private static boolean check(Connection connection,String query) throws SQLException {
        try(var statement=connection.createStatement()) {statement.setQueryTimeout(10);try(var result=statement.executeQuery(query)){return result.next() && result.getBoolean(1);}}
    }
    private static void require(boolean allowed,String message) {if(!allowed)throw new IllegalStateException(message);}
}
