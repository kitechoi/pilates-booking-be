ALTER TABLE reservation
    ALTER COLUMN member_pass_id SET NOT NULL;

ALTER TABLE reservation
    DROP CONSTRAINT fk_reservation_member_pass_expand,
    ADD CONSTRAINT fk_reservation_member_pass_member
        FOREIGN KEY (member_pass_id, member_id)
        REFERENCES member_pass (id, member_id),
    ADD CONSTRAINT ck_reservation_cancellation_source
        CHECK (
            (status = 'RESERVED' AND cancellation_source IS NULL)
            OR (
                status = 'CANCELLED'
                AND cancellation_source IN ('MEMBER', 'ADMIN', 'CLASS_SESSION')
            )
        );
