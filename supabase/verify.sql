-- Post-migration audit. Read-only — safe to run any time, changes nothing.
--
-- Running the migrations successfully proves the SQL parsed. It does not prove the schema is
-- *safe*, because every way of getting this wrong is silent: a view without security_invoker
-- returns rows happily, they are just other people's rows. This asserts the properties that
-- matter, rather than trusting that they followed from the DDL.
--
-- Every row should read PASS. Anything else is a real finding.

-- 1. RLS is enabled on all six tables.
--    Without this the policies exist but are never consulted, and the table is wide open.
select '1. RLS enabled' as check,
       c.relname as object,
       case when c.relrowsecurity then 'PASS' else 'FAIL — table is unprotected' end as status
  from pg_class c
  join pg_namespace n on n.oid = c.relnamespace
 where n.nspname = 'public'
   and c.relname in ('cities','venues','artists','events','event_artists','event_media')
   and c.relkind = 'r'

union all

-- 2. Catalog tables have SELECT policies only.
--    An insert/update/delete policy appearing here means someone can rewrite shared data.
select '2. Catalog is read-only',
       tablename,
       case when count(*) filter (where cmd <> 'SELECT') = 0
            then 'PASS — ' || count(*) || ' select policy'
            else 'FAIL — ' || count(*) filter (where cmd <> 'SELECT') || ' write policy' end
  from pg_policies
 where schemaname = 'public' and tablename in ('cities','venues','artists')
 group by tablename

union all

-- 3. Library tables carry all four verbs.
select '3. Library CRUD policies',
       tablename,
       case when count(distinct cmd) = 4
            then 'PASS — all 4 verbs'
            else 'FAIL — only ' || count(distinct cmd) || ' verb(s)' end
  from pg_policies
 where schemaname = 'public' and tablename in ('events','event_artists','event_media')
 group by tablename

union all

-- 4. Every UPDATE and INSERT policy has a WITH CHECK clause.
--    A USING-only insert policy constrains nothing; a USING-only update policy lets a user hand
--    a row to another account by rewriting its user_id.
select '4. WITH CHECK present',
       tablename || '.' || policyname,
       case when with_check is not null then 'PASS' else 'FAIL — no WITH CHECK' end
  from pg_policies
 where schemaname = 'public' and cmd in ('INSERT','UPDATE')

union all

-- 5. Views run as the caller, not the owner.
--    This is the big one. A view without it bypasses RLS entirely and shows every user
--    every user's shows.
select '5. security_invoker on views',
       c.relname,
       case when array_to_string(c.reloptions, ',') ~ 'security_invoker=(on|true)'
            then 'PASS' else 'FAIL — view bypasses RLS' end
  from pg_class c
  join pg_namespace n on n.oid = c.relnamespace
 where n.nspname = 'public' and c.relkind = 'v'

union all

-- 6. Every SECURITY DEFINER function pins its search_path.
--    Without it the caller can decide what code runs with the owner's privileges.
select '6. search_path pinned',
       p.proname,
       case when array_to_string(p.proconfig, ',') like '%search_path%'
            then 'PASS' else 'FAIL — hijackable' end
  from pg_proc p
  join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public' and p.prosecdef

union all

-- 7. The anon role — the key that ships inside the APK — reaches nothing.
select '7. anon has no access',
       coalesce(table_name, 'all tables'),
       case when count(*) = 0 then 'PASS' else 'FAIL — anon can ' || string_agg(distinct privilege_type, '/') end
  from information_schema.role_table_grants
 where grantee = 'anon' and table_schema = 'public'
 group by table_name

union all

select '7. anon has no access', 'all tables', 'PASS'
 where not exists (
    select 1 from information_schema.role_table_grants
     where grantee = 'anon' and table_schema = 'public'
 )

order by 1, 2;
