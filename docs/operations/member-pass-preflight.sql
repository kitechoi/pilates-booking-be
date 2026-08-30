BEGIN TRANSACTION READ ONLY;

SET LOCAL statement_timeout = '30s';
SET LOCAL lock_timeout = '3s';

SELECT
    CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul' AS captured_at,
    COUNT(*) AS total,
    COUNT(*) FILTER (WHERE status = 'RESERVED') AS reserved,
    COUNT(*) FILTER (WHERE status = 'CANCELLED') AS cancelled
FROM reservation;

SELECT id, member_id, class_session_id, status, reserved_at, cancelled_at
FROM reservation
WHERE status = 'CANCELLED'
ORDER BY id;

SELECT id, status, reserved_at, cancelled_at
FROM reservation
WHERE (status = 'RESERVED' AND cancelled_at IS NOT NULL)
   OR (status = 'CANCELLED' AND cancelled_at IS NULL)
ORDER BY id;

SELECT
    cs.id,
    cs.reserved_count,
    COUNT(r.id) AS actual_reserved_count
FROM class_session cs
LEFT JOIN reservation r
    ON r.class_session_id = cs.id
   AND r.status = 'RESERVED'
GROUP BY cs.id, cs.reserved_count
HAVING cs.reserved_count <> COUNT(r.id);

SELECT installed_rank, version, description, installed_on, success
FROM flyway_schema_history
ORDER BY installed_rank;

COMMIT;
