ALTER TABLE pass_product ADD COLUMN status VARCHAR(20);

UPDATE pass_product
SET status = CASE WHEN active THEN 'ACTIVE' ELSE 'INACTIVE' END;

ALTER TABLE pass_product
    ALTER COLUMN status SET NOT NULL,
    ALTER COLUMN status SET DEFAULT 'ACTIVE';

ALTER TABLE pass_product
    ADD CONSTRAINT ck_pass_product_status CHECK (status IN ('ACTIVE', 'INACTIVE'));

ALTER TABLE pass_product DROP COLUMN active;
