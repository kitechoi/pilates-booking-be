-- 모든 결과가 0행이어야 수강권 데이터가 정상이다.

-- 1. 현재 잔액과 전체 이력 합계
SELECT mp.id, mp.remaining_count, COALESCE(SUM(mph.count_delta), 0) AS history_sum
FROM member_pass mp
LEFT JOIN member_pass_history mph ON mph.member_pass_id = mp.id
GROUP BY mp.id, mp.remaining_count
HAVING mp.remaining_count <> COALESCE(SUM(mph.count_delta), 0);

-- 2. 각 이력의 running balance와 기록된 잔액
SELECT id, member_pass_id, remaining_count_after, calculated_remaining
FROM (
    SELECT
        mph.id,
        mph.member_pass_id,
        mph.remaining_count_after,
        SUM(mph.count_delta) OVER (
            PARTITION BY mph.member_pass_id
            ORDER BY mph.created_at, mph.id
            ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
        ) AS calculated_remaining
    FROM member_pass_history mph
) history_balance
WHERE remaining_count_after <> calculated_remaining;

-- 3. 예약 회원과 수강권 소유 회원
SELECT r.id AS reservation_id, r.member_pass_id, r.member_id, mp.member_id AS pass_member_id
FROM reservation r
LEFT JOIN member_pass mp ON mp.id = r.member_pass_id
WHERE r.member_pass_id IS NULL
   OR mp.id IS NULL
   OR r.member_id <> mp.member_id;

-- 4. 예약 차감·취소 환불 cardinality
SELECT reservation_id, type, COUNT(*)
FROM member_pass_history
WHERE type IN ('RESERVATION_DEBIT', 'CANCELLATION_REFUND')
GROUP BY reservation_id, type
HAVING COUNT(*) > 1;

-- 5. 예약과 취소 출처의 상태 정합성
SELECT id, status, cancellation_source
FROM reservation
WHERE (status = 'RESERVED' AND cancellation_source IS NOT NULL)
   OR (
       status = 'CANCELLED'
       AND (
           cancellation_source IS NULL
           OR cancellation_source NOT IN ('MEMBER', 'ADMIN', 'CLASS_SESSION')
       )
   );

-- 6. 수업 reserved_count와 실제 활성 예약 수
SELECT cs.id, cs.reserved_count, COUNT(r.id) AS active_reservations
FROM class_session cs
LEFT JOIN reservation r
    ON r.class_session_id = cs.id
   AND r.status = 'RESERVED'
GROUP BY cs.id, cs.reserved_count
HAVING cs.reserved_count <> COUNT(r.id);
