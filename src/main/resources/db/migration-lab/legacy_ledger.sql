-- 仅 migration profile：模拟旧架构中与 Redis 预留分离的 MySQL 账本（文章：预留在 Redis、账本在 MySQL）。
-- 与本系统的 inventory_ledger 完全独立，旧系统的确认在这里扣减账面库存，且无法与 Redis 原子提交。
CREATE TABLE IF NOT EXISTS legacy_inventory_ledger (
    shop_id             BIGINT NOT NULL,
    inventory_item_id   BIGINT NOT NULL,
    location_id         BIGINT NOT NULL,
    on_hand_quantity    BIGINT NOT NULL COMMENT '旧系统账面库存；确认时扣减',
    PRIMARY KEY (shop_id, inventory_item_id, location_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
