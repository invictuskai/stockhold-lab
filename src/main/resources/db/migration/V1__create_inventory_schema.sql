-- StockHold Lab / MySQL 8.4 LTS
-- Flyway V1：由 docs/数据库表结构.sql 设计稿迁移而来；表间关系、数量约定与使用场景的完整注释见该文件。
-- 应用、迁移和运维会话统一使用 UTC；业务写事务显式 READ COMMITTED。核心表刻意不设外键。

CREATE TABLE inventory_ledger (
    shop_id             BIGINT NOT NULL COMMENT '店铺ID；租户隔离维度',
    inventory_item_id   BIGINT NOT NULL COMMENT '库存商品ID；在店铺内标识商品',
    location_id         BIGINT NOT NULL COMMENT '库存地点ID；不跨地点自动借库存',
    on_hand_quantity    BIGINT NOT NULL COMMENT 'H：尚未正式售出的账面库存，包含ACTIVE预留',
    allocated_quantity  BIGINT NOT NULL DEFAULT 0 COMMENT 'A：已发放额度，等于池行数P加ACTIVE预留量R',
    pool_capacity       INT NOT NULL DEFAULT 1000 COMMENT '单维度可用池行数上限；不是同时预留总量上限',
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '账本创建时间，UTC',
    updated_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '账本最近修改时间；正常预留不修改本行',
    PRIMARY KEY (shop_id, inventory_item_id, location_id),
    CONSTRAINT ck_ledger_identity CHECK
        (shop_id > 0 AND inventory_item_id > 0 AND location_id > 0),
    CONSTRAINT ck_ledger_quantity CHECK
        (on_hand_quantity >= 0 AND allocated_quantity >= 0
         AND allocated_quantity <= on_hand_quantity),
    CONSTRAINT ck_ledger_capacity CHECK (pool_capacity BETWEEN 1 AND 1000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='库存账本：维护真实库存及发放额度，供补充、确认、释放和库存调整使用';

CREATE TABLE reservation_units (
    shop_id             BIGINT NOT NULL COMMENT '店铺ID；与账本维度一致',
    inventory_item_id   BIGINT NOT NULL COMMENT '库存商品ID；与账本维度一致',
    location_id         BIGINT NOT NULL COMMENT '库存地点ID；与账本维度一致',
    unit_id             BINARY(16) NOT NULL COMMENT '可领取单位ID；Java生成UUID，一行代表一件额度',
    PRIMARY KEY (shop_id, inventory_item_id, location_id, unit_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='有界可用单位池：补充时插入，预留时通过SKIP LOCKED领取并删除';

CREATE TABLE reservations (
    shop_id             BIGINT NOT NULL COMMENT '店铺ID；预留及幂等键按店铺隔离',
    reservation_id      BINARY(16) NOT NULL COMMENT '预留ID；Java生成UUID，关联预留明细',
    idempotency_key     VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '创建请求幂等键；同店铺唯一',
    request_hash        BINARY(32) NOT NULL COMMENT '规范化创建请求的SHA-256；检测同键不同参数',
    status              VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'ACTIVE占用额度；CLAIMED已扣减；CANCELLED或EXPIRED已释放',
    ttl_seconds         INT NOT NULL COMMENT '请求预留有效期，5至900秒；参与请求hash',
    total_quantity      INT NOT NULL COMMENT '整单单位总量，等于所有明细quantity之和',
    line_count          INT NOT NULL COMMENT '合并重复商品和地点后的明细条数',
    payment_reference   VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '确认扣减的支付引用；仅CLAIMED非空，同店铺唯一',
    expires_at          DATETIME(6) NOT NULL COMMENT '预留到期时间，UTC；创建时用数据库时间计算',
    claimed_at          DATETIME(6) NULL COMMENT '转为CLAIMED的业务处理时间，UTC',
    cancelled_at        DATETIME(6) NULL COMMENT '转为CANCELLED的业务处理时间，UTC',
    expired_at          DATETIME(6) NULL COMMENT '到期释放处理时间，UTC；不等于计划到期时间',
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '预留创建时间，UTC',
    updated_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '预留头最近修改时间，UTC',
    PRIMARY KEY (shop_id, reservation_id),
    UNIQUE KEY uq_reservation_idempotency (shop_id, idempotency_key),
    UNIQUE KEY uq_reservation_payment (shop_id, payment_reference),
    KEY idx_reservation_expiry (status, expires_at, shop_id, reservation_id),
    CONSTRAINT ck_reservation_status CHECK
        (status IN ('ACTIVE', 'CLAIMED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_reservation_ttl CHECK (ttl_seconds BETWEEN 5 AND 900),
    CONSTRAINT ck_reservation_size CHECK
        (total_quantity BETWEEN 1 AND 1000 AND line_count BETWEEN 1 AND 20),
    CONSTRAINT ck_reservation_claim CHECK
        ((status = 'CLAIMED' AND payment_reference IS NOT NULL AND claimed_at IS NOT NULL)
         OR (status <> 'CLAIMED' AND payment_reference IS NULL AND claimed_at IS NULL)),
    CONSTRAINT ck_reservation_cancel CHECK
        ((status = 'CANCELLED' AND cancelled_at IS NOT NULL)
         OR (status <> 'CANCELLED' AND cancelled_at IS NULL)),
    CONSTRAINT ck_reservation_expired CHECK
        ((status = 'EXPIRED' AND expired_at IS NOT NULL)
         OR (status <> 'EXPIRED' AND expired_at IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='整单预留头：提供幂等、状态机、支付关联及到期释放的并发仲裁';

CREATE TABLE reserved_quantities (
    shop_id             BIGINT NOT NULL COMMENT '店铺ID；与预留头及库存账本一致',
    reservation_id      BINARY(16) NOT NULL COMMENT '所属预留ID；逻辑关联reservations',
    line_no             SMALLINT NOT NULL COMMENT '合并并按维度排序后的明细序号，从1开始',
    inventory_item_id   BIGINT NOT NULL COMMENT '本明细预留的库存商品ID',
    location_id         BIGINT NOT NULL COMMENT '本明细预留的库存地点ID',
    quantity            INT NOT NULL COMMENT '该商品和地点的预留件数；含义由预留头状态决定',
    PRIMARY KEY (shop_id, reservation_id, line_no),
    UNIQUE KEY uq_reserved_dimension
        (shop_id, reservation_id, inventory_item_id, location_id),
    KEY idx_reserved_inventory
        (shop_id, inventory_item_id, location_id, reservation_id),
    CONSTRAINT ck_reserved_quantity CHECK (quantity BETWEEN 1 AND 1000),
    CONSTRAINT ck_reserved_line CHECK (line_no BETWEEN 1 AND 20)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='不可变预留明细：按商品和地点聚合数量，供确认、释放及历史审计使用';

CREATE TABLE inventory_operations (
    shop_id             BIGINT NOT NULL COMMENT '店铺ID；管理操作幂等范围',
    operation_id        BINARY(16) NOT NULL COMMENT '调用方生成的操作UUID；同店铺唯一，重试必须复用',
    request_hash        BINARY(32) NOT NULL COMMENT '规范化管理请求的SHA-256；防止同操作ID更换参数',
    operation_type      VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'INITIALIZE初始化；ADJUST调整账面库存',
    inventory_item_id   BIGINT NOT NULL COMMENT '被操作的库存商品ID',
    location_id         BIGINT NOT NULL COMMENT '被操作的库存地点ID',
    delta_quantity      BIGINT NOT NULL COMMENT 'H的变动量；初始化为初始库存，调整正数补货负数减少',
    on_hand_after       BIGINT NOT NULL COMMENT '本次操作完成后的H快照，非当前库存',
    allocated_after     BIGINT NOT NULL COMMENT '本次操作完成后的A快照，供幂等返回',
    pool_capacity_after INT NOT NULL COMMENT '本次操作时的池容量快照',
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '操作记录创建时间，UTC',
    PRIMARY KEY (shop_id, operation_id),
    KEY idx_inventory_operation_history
        (shop_id, inventory_item_id, location_id, created_at, operation_id),
    CONSTRAINT ck_operation_type CHECK (operation_type IN ('INITIALIZE', 'ADJUST')),
    CONSTRAINT ck_operation_delta CHECK
        ((operation_type = 'INITIALIZE' AND delta_quantity >= 0)
         OR (operation_type = 'ADJUST' AND delta_quantity <> 0)),
    CONSTRAINT ck_operation_snapshot CHECK
        (on_hand_after >= allocated_after AND allocated_after >= 0
         AND pool_capacity_after BETWEEN 1 AND 1000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='库存初始化和调整记录：防止管理请求重复执行，并保存结果快照用于审计';

