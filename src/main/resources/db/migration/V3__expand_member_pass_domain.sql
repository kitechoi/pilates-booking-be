CREATE TABLE pass_product (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(50) NOT NULL,
    name VARCHAR(100) NOT NULL,
    default_price INTEGER NOT NULL,
    default_count INTEGER NOT NULL,
    default_validity_days INTEGER NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_pass_product_code UNIQUE (code),
    CONSTRAINT ck_pass_product_price CHECK (default_price >= 0),
    CONSTRAINT ck_pass_product_count CHECK (default_count > 0),
    CONSTRAINT ck_pass_product_validity_days CHECK (default_validity_days > 0)
);

CREATE TABLE member_pass (
    id BIGSERIAL PRIMARY KEY,
    member_id BIGINT NOT NULL,
    pass_product_id BIGINT NOT NULL,
    product_name_snapshot VARCHAR(100) NOT NULL,
    price_paid INTEGER NOT NULL,
    initial_count INTEGER NOT NULL,
    remaining_count INTEGER NOT NULL,
    valid_from DATE NOT NULL,
    expires_on DATE NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_member_pass_member
        FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT fk_member_pass_product
        FOREIGN KEY (pass_product_id) REFERENCES pass_product (id),
    CONSTRAINT uk_member_pass_id_member UNIQUE (id, member_id),
    CONSTRAINT ck_member_pass_price CHECK (price_paid >= 0),
    CONSTRAINT ck_member_pass_initial_count CHECK (initial_count > 0),
    CONSTRAINT ck_member_pass_remaining_count
        CHECK (remaining_count >= 0 AND remaining_count <= initial_count),
    CONSTRAINT ck_member_pass_period CHECK (expires_on >= valid_from),
    CONSTRAINT ck_member_pass_status CHECK (status IN ('ACTIVE', 'CANCELLED'))
);

CREATE TABLE member_pass_history (
    id BIGSERIAL PRIMARY KEY,
    member_pass_id BIGINT NOT NULL,
    reservation_id BIGINT,
    type VARCHAR(30) NOT NULL,
    count_delta INTEGER NOT NULL,
    remaining_count_after INTEGER NOT NULL,
    actor_type VARCHAR(20) NOT NULL,
    memo VARCHAR(500),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_member_pass_history_member_pass
        FOREIGN KEY (member_pass_id) REFERENCES member_pass (id),
    CONSTRAINT fk_member_pass_history_reservation
        FOREIGN KEY (reservation_id) REFERENCES reservation (id),
    CONSTRAINT ck_member_pass_history_type
        CHECK (type IN ('ISSUED', 'MIGRATION_OPENING', 'RESERVATION_DEBIT', 'CANCELLATION_REFUND')),
    CONSTRAINT ck_member_pass_history_actor
        CHECK (actor_type IN ('SYSTEM', 'MEMBER', 'ADMIN')),
    CONSTRAINT ck_member_pass_history_reservation
        CHECK ((type IN ('RESERVATION_DEBIT', 'CANCELLATION_REFUND')) = (reservation_id IS NOT NULL)),
    CONSTRAINT ck_member_pass_history_delta
        CHECK (
            (type IN ('ISSUED', 'MIGRATION_OPENING') AND count_delta > 0)
            OR (type = 'RESERVATION_DEBIT' AND count_delta = -1)
            OR (type = 'CANCELLATION_REFUND' AND count_delta = 1)
        ),
    CONSTRAINT ck_member_pass_history_remaining CHECK (remaining_count_after >= 0),
    CONSTRAINT ck_member_pass_history_admin_memo
        CHECK (actor_type <> 'ADMIN' OR (memo IS NOT NULL AND length(btrim(memo)) > 0))
);

ALTER TABLE reservation
    ADD COLUMN member_pass_id BIGINT,
    ADD COLUMN cancellation_source VARCHAR(20);

ALTER TABLE reservation
    ADD CONSTRAINT fk_reservation_member_pass_expand
        FOREIGN KEY (member_pass_id) REFERENCES member_pass (id);

CREATE INDEX ix_member_pass_usable
    ON member_pass (member_id, status, expires_on, valid_from, id);

CREATE INDEX ix_member_pass_history_member_pass_created
    ON member_pass_history (member_pass_id, created_at, id);

CREATE UNIQUE INDEX uk_member_pass_history_reservation_debit
    ON member_pass_history (reservation_id)
    WHERE type = 'RESERVATION_DEBIT';

CREATE UNIQUE INDEX uk_member_pass_history_cancellation_refund
    ON member_pass_history (reservation_id)
    WHERE type = 'CANCELLATION_REFUND';

CREATE INDEX ix_reservation_member_pass
    ON reservation (member_pass_id);

CREATE FUNCTION prevent_member_pass_history_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'member_pass_history is append-only';
END;
$$;

CREATE TRIGGER trg_member_pass_history_append_only
BEFORE UPDATE OR DELETE ON member_pass_history
FOR EACH ROW
EXECUTE FUNCTION prevent_member_pass_history_mutation();

CREATE FUNCTION validate_member_pass_balance()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    target_member_pass_id BIGINT;
    stored_remaining_count INTEGER;
    history_count_sum BIGINT;
BEGIN
    IF TG_TABLE_NAME = 'member_pass' THEN
        target_member_pass_id := NEW.id;
    ELSE
        target_member_pass_id := NEW.member_pass_id;
    END IF;

    SELECT remaining_count
    INTO stored_remaining_count
    FROM member_pass
    WHERE id = target_member_pass_id;

    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    SELECT COALESCE(SUM(count_delta), 0)
    INTO history_count_sum
    FROM member_pass_history
    WHERE member_pass_id = target_member_pass_id;

    IF stored_remaining_count <> history_count_sum THEN
        RAISE EXCEPTION 'member_pass balance does not match history sum';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_member_pass_balance
AFTER INSERT OR UPDATE ON member_pass
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION validate_member_pass_balance();

CREATE CONSTRAINT TRIGGER trg_member_pass_history_balance
AFTER INSERT ON member_pass_history
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW
EXECUTE FUNCTION validate_member_pass_balance();
