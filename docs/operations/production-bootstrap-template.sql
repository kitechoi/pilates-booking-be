-- 운영 최초 데이터 입력 템플릿.
-- 애플리케이션 시작 시 자동 실행되지 않는다. 실행 전 docs/operations/production-bootstrap-template.md의
-- "입력할 데이터 초안"을 검토해 아래 리터럴 값을 실제 값으로 교체한 뒤, 필요한 섹션만 남기고 실행한다.
-- 비밀번호는 평문이 아니라 BCrypt 해시를 password_hash 변수로 전달한다.
-- 실행 예:
--   psql "$PILASLOT_DB_URL" -v password_hash='<BCrypt 해시>' -f production-bootstrap-template.sql

BEGIN;

-- =====================================================================
-- 1. PassProduct — 실제로 판매하는 수강권 상품만 등록한다.
--    재실행해도 안전 (code unique).
-- =====================================================================

INSERT INTO pass_product (
    code, name, default_price, default_count, default_validity_days,
    status, created_at, updated_at
)
VALUES
    ('PILATES_30_90D', '필라테스 30회권', 450000, 30, 90, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('PILATES_10_60D', '필라테스 10회권', 200000, 10, 60, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (code) DO NOTHING;


-- =====================================================================
-- 2. Instructor — 이미 등록된 강사가 있는지 먼저 확인한다:
--      SELECT id, name FROM instructor ORDER BY id;
--    신규 강사만 추가한다. unique 제약이 없으므로 재실행 시 중복 삽입될 수 있다.
-- =====================================================================

-- INSERT INTO instructor (name, profile_image_url, created_at, updated_at)
-- VALUES ('<강사 이름>', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);


-- =====================================================================
-- 3. ClassSession — 정원(ANIMAL_FLOW=8, 나머지=4)과 예약 오픈 시각
--    (수업 주차 전주 금요일 13:00) 규칙을 반드시 지킨다.
--    이미 반복 시간표가 있다면 이 섹션은 생략한다.
-- =====================================================================

-- INSERT INTO class_session (
--     instructor_id, class_type, start_at, duration_minutes,
--     reservation_open_at, capacity, reserved_count, status,
--     created_at, updated_at
-- )
-- SELECT
--     id, 'REFORMER', TIMESTAMP '<수업 시작 일시>', 50,
--     -- date_trunc('week', ...)는 그 주 월요일 00:00을 반환하므로(ISO 기준),
--     -- 여기서 3일을 빼면 정확히 전주 금요일이 된다.
--     date_trunc('week', TIMESTAMP '<수업 시작 일시>') - INTERVAL '3 days' + INTERVAL '13 hours',
--     4, 0, 'SCHEDULED',
--     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
-- FROM instructor WHERE name = '<강사 이름>';


-- =====================================================================
-- 4. Member — 첫 실제 회원. 최초 비밀번호는 member_number와 동일해야 하므로
--    password_hash는 member_number 평문을 BCrypt로 해싱한 값을 넘긴다.
--    재실행해도 안전 (member_number unique).
-- =====================================================================

INSERT INTO member (member_number, password, name, phone_number, created_at, updated_at)
VALUES ('1234', :'password_hash', 'TEST', '01012341234', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (member_number) DO NOTHING;


-- =====================================================================
-- 5. MemberPass + ISSUED history — 그 회원에게 실제 발급하는 수강권.
--    재실행해도 안전 (동일 member+product ACTIVE 조합이 있으면 스킵).
-- =====================================================================

WITH target_member AS (
    SELECT id FROM member WHERE member_number = '1234'
), target_product AS (
    SELECT id, name, default_validity_days FROM pass_product WHERE code = 'PILATES_30_90D'
), inserted_pass AS (
    INSERT INTO member_pass (
        member_id, pass_product_id, product_name_snapshot, price_paid,
        initial_count, remaining_count, valid_from, expires_on, status,
        created_at, updated_at
    )
    SELECT
        target_member.id, target_product.id, target_product.name,
        450000,
        30, 30, CURRENT_DATE, CURRENT_DATE + target_product.default_validity_days, 'ACTIVE',
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    FROM target_member, target_product
    WHERE NOT EXISTS (
        SELECT 1
        FROM member_pass
        WHERE member_id = target_member.id
          AND pass_product_id = target_product.id
          AND status = 'ACTIVE'
    )
    RETURNING id, remaining_count
)
INSERT INTO member_pass_history (
    member_pass_id, reservation_id, type, count_delta,
    remaining_count_after, actor_type, memo, created_at, updated_at
)
SELECT
    id, NULL, 'ISSUED', remaining_count,
    remaining_count, 'ADMIN', '운영 최초 등록',
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM inserted_pass;

COMMIT;
