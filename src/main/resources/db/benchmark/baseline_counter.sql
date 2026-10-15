-- 单计数器对照组（实现与验证计划 6.1）。只在 benchmark profile 启动时创建，使用独立表，
-- 不与单位池模式混用数据。预留头 / 明细结构与主方案一致，保证幂等、状态机和审计语义等价。

CREATE TABLE IF NOT EXISTS baseline_inventory_counter (
    shop_id             BIGINT NOT NULL,
    inventory_item_id   BIGINT NOT NULL,
    location_id         BIGINT NOT NULL,
    on_hand_quantity    BIGINT NOT NULL COMMENT 'H：尚未正式售出的账面库存',
    reserved_quantity   BIGINT NOT NULL DEFAULT 0 COMMENT 'R：ACTIVE 预留总量；所有预留竞争这一行',
    PRIMARY KEY (shop_id, inventory_item_id, location_id),
    CONSTRAINT ck_bl_counter_quantity CHECK
        (on_hand_quantity >= 0 AND reserved_quantity >= 0 AND reserved_quantity <= on_hand_quantity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS baseline_reservations (
    shop_id             BIGINT NOT NULL,
    reservation_id      BINARY(16) NOT NULL,
    idempotency_key     VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash        BINARY(32) NOT NULL,
    status              VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ttl_seconds         INT NOT NULL,
    total_quantity      INT NOT NULL,
    line_count          INT NOT NULL,
    payment_reference   VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    expires_at          DATETIME(6) NOT NULL,
    claimed_at          DATETIME(6) NULL,
    cancelled_at        DATETIME(6) NULL,
    expired_at          DATETIME(6) NULL,
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (shop_id, reservation_id),
    UNIQUE KEY uq_reservation_idempotency (shop_id, idempotency_key),
    UNIQUE KEY uq_reservation_payment (shop_id, payment_reference),
    KEY idx_reservation_expiry (status, expires_at, shop_id, reservation_id),
    CONSTRAINT ck_bl_reservation_status CHECK (status IN ('ACTIVE', 'CLAIMED', 'CANCELLED', 'EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS baseline_reserved_quantities (
    shop_id             BIGINT NOT NULL,
    reservation_id      BINARY(16) NOT NULL,
    line_no             SMALLINT NOT NULL,
    inventory_item_id   BIGINT NOT NULL,
    location_id         BIGINT NOT NULL,
    quantity            INT NOT NULL,
    PRIMARY KEY (shop_id, reservation_id, line_no),
    UNIQUE KEY uq_reserved_dimension (shop_id, reservation_id, inventory_item_id, location_id),
    KEY idx_reserved_inventory (shop_id, inventory_item_id, location_id, reservation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
