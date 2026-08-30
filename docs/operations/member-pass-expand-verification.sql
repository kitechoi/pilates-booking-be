BEGIN TRANSACTION READ ONLY;

SET LOCAL statement_timeout = '30s';
SET LOCAL lock_timeout = '3s';

SELECT
    CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul' AS captured_at,
    version,
    description,
    installed_on,
    success
FROM flyway_schema_history
WHERE version = '3';

SELECT
    to_regclass('public.pass_product') AS pass_product_table,
    to_regclass('public.member_pass') AS member_pass_table,
    to_regclass('public.member_pass_history') AS member_pass_history_table;

SELECT
    COUNT(*) AS member_count
FROM member;

SELECT
    (SELECT COUNT(*) FROM pass_product) AS pass_product_count,
    (SELECT COUNT(*) FROM member_pass) AS member_pass_count,
    (SELECT COUNT(*) FROM member_pass_history) AS member_pass_history_count,
    (SELECT COUNT(*) FROM reservation) AS reservation_count;

SELECT
    COUNT(*) FILTER (WHERE member_pass_id IS NULL) AS reservation_without_member_pass_count,
    COUNT(*) FILTER (
        WHERE status = 'RESERVED'
          AND cancellation_source IS NOT NULL
    ) AS reserved_with_cancellation_source_count,
    COUNT(*) FILTER (
        WHERE status = 'CANCELLED'
          AND cancellation_source IS NULL
    ) AS cancelled_without_cancellation_source_count
FROM reservation;

SELECT
    tgname AS trigger_name,
    tgenabled AS trigger_enabled
FROM pg_trigger
WHERE tgname IN (
    'trg_member_pass_history_append_only',
    'trg_member_pass_balance',
    'trg_member_pass_history_balance'
)
ORDER BY tgname;

COMMIT;
