-- 애플리케이션 시작 시 자동 실행되지 않는 명시적 로컬 fixture다.
-- 사용 전 BCrypt 해시를 직접 전달한다.
-- psql "$PILASLOT_DB_URL" -v password_hash='<BCrypt hash>' -f dev/local-fixture.sql

BEGIN;

INSERT INTO member (member_number, password, name, phone_number, created_at, updated_at)
VALUES ('1234', :'password_hash', '로컬 회원', '010-0000-0000', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (member_number) DO NOTHING;

INSERT INTO pass_product (
    code, name, default_price, default_count, default_validity_days,
    status, created_at, updated_at
)
VALUES (
    'PILATES_30_90D', '필라테스 30회권', 600000, 30, 90,
    'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
)
ON CONFLICT (code) DO NOTHING;

WITH target_member AS (
    SELECT id FROM member WHERE member_number = '1234'
), target_product AS (
    SELECT id, name FROM pass_product WHERE code = 'PILATES_30_90D'
), inserted_pass AS (
    INSERT INTO member_pass (
        member_id, pass_product_id, product_name_snapshot, price_paid,
        initial_count, remaining_count, valid_from, expires_on, status,
        created_at, updated_at
    )
    SELECT
        target_member.id, target_product.id, target_product.name, 600000,
        30, 30, CURRENT_DATE, CURRENT_DATE + 90, 'ACTIVE',
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
    remaining_count, 'ADMIN', '로컬 명시적 fixture 발급',
    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM inserted_pass;

COMMIT;
