-- StockHold Lab / MySQL 8.4 LTS
-- 设计稿：实现时迁移为 Flyway V1__create_inventory_schema.sql。
-- v1.1：新增账本冻结字段、claim_deadline_at / claim_mode（宽限与迟到确认）、池回收计数。
-- v1.2（秒杀适配）：账本结算模式、待结算分录表 ledger_pending_entries、买家限购、预留头 buyer_id。
-- v1.3（逻辑库存分区）：库存维度第三列由“地点”改为“逻辑库存分区” stock_partition_id；
--       新增 stock_partitions、多范围限购规则 purchase_limit_rules、预留额度占用 reservation_quota_usages；
--       操作记录支持分区间划拨（TRANSFER_OUT / TRANSFER_IN）。
-- 验证状态：本文件已在 MySQL 8.4.9（Windows 免安装包，本地实验）完整执行，
--       CHECK 约束正反例、核心锁行为与并发测试见 实现与验证计划.md 第 9 节；尚未在生产规格环境验证。
-- 应用、迁移和运维会话统一使用 UTC；业务写事务显式 READ COMMITTED。
-- 无 CREATE DATABASE / DROP TABLE，避免误操作宿主数据库。
-- 核心表刻意不设外键，原因见 总体技术方案.md。
-- 本文件的数据模型是本项目的设计，不是 Shopify 公布的原始表结构。
--
-- ========================= 表间关系与业务流程 =========================
-- 库存维度：同一商家 shop_id 下，一个 SKU（inventory_item_id）在一个逻辑库存分区（stock_partition_id）。
--   逻辑库存分区是“可售额度的逻辑划分”，与货物实际存放在哪个物理仓库无关；
--   分区 0 是默认可售分区。营销活动、渠道等上游概念可以映射为独立分区，但库存服务不感知这些概念。
-- stock_partitions     ：分区的开启 / 关闭状态。
-- inventory_ledger     ：该维度的库存账本，决定还有多少额度可以发放。
-- reservation_units    ：该维度已发放、尚未被领取的库存单位池，一行代表一件。
-- reservations         ：一次整单预留的业务头，管理幂等、过期时间和状态。
-- reserved_quantities  ：预留头的 SKU/分区 明细，一行记录该维度预留了几件。
-- inventory_operations ：初始化、调整与分区间划拨的幂等及审计记录，不是全部库存变动流水。
-- ledger_pending_entries：BATCHED 结算模式下 claim/释放产生的待结算账本分录，合并后写入账本。
-- purchase_limit_rules ：每买家限购规则，范围为“分区+SKU”“分区合计”或“SKU 跨全部分区”。
-- buyer_quotas         ：买家在某条规则下的 ACTIVE / 已确认数量，用于原子校验限购。
-- reservation_quota_usages：某个预留在各条规则上实际占用的数量，确认 / 释放时按此原路调整。
--
-- 逻辑关系（不创建物理外键）：
-- inventory_ledger 1:N reservation_units，关联键为完整库存维度。
-- reservations 1:N reserved_quantities / reservation_quota_usages，关联键为 (shop_id, reservation_id)。
-- inventory_ledger 1:N reserved_quantities / inventory_operations，按完整库存维度关联。
-- purchase_limit_rules 1:N buyer_quotas / reservation_quota_usages，按 rule_id 关联。
--
-- 数量约定：H=on_hand_quantity，A=allocated_quantity，P=池行数，
--           R=状态为 ACTIVE 的预留明细数量之和，F=H-A（尚未发放的额度）。
-- 已提交的一致性快照必须满足：H >= A >= 0，A = P + R，P <= pool_capacity。
-- ACTIVE 即使已超过 expires_at，释放事务提交前仍占额度、仍计入 R。
-- BATCHED 模式下记待结算分录的账本增量合计为 dH、dA（均 <= 0），则不变量写作：
--   H+dH >= A+dA >= 0，A+dA = P + R；SYNC 模式下 dH = dA = 0，二者统一。
-- 每个维度：本维度操作记录 delta 之和 − 本维度 CLAIMED 明细之和 = H + dH；
-- 每个 SKU 跨全部分区：划拨两侧相互抵消，Σ(H+dH) = 初始 + 调整 − 已确认。
--
-- 操作         账本变化           单位池变化         预留头/明细变化
-- 初始化       H=初始库存,A=0     不自动填池         无；另写初始化操作记录
-- 补充 n       A+=n               插入 n 行          无
-- 预留 n       不修改账本         删除 n 行          新增 ACTIVE 头及数量明细
-- 确认 n       H-=n,A-=n          不操作池           头转 CLAIMED，明细保留
-- 取消/到期 n  A-=n，H不变        不直接插回池       头转 CANCELLED/EXPIRED，明细保留
-- 迟到确认 n   H-=n；从池取的部分  可能删除 k 行      头 EXPIRED 转 CLAIMED(LATE)，明细保留
--  (EXPIRED)   另 A-=k
-- 调整 delta   H+=delta           不直接操作池       无；另写调整操作记录
-- 回收调整     H+=delta,A-=k      删除 k 个未领取行  无；操作记录写 pool_reclaimed_quantity=k
-- 划拨 n       源 H-=n（必要时回收 源池可能删除 k 行  无；同一 operation_id 写 OUT/IN 两行
--              池单位 A-=k），目标 H+=n
-- 冻结/解冻    status 变化        不操作池           无
-- BATCHED 确认 不改账本，写分录     不操作池           头转 CLAIMED；分录 dH=-n,dA=-n
-- BATCHED 释放 不改账本，写分录     不操作池           头转 CANCELLED/EXPIRED；分录 dH=0,dA=-n
-- 结算         H+=ΣdH,A+=ΣdA      不操作池           删除已合并分录
-- 上述每一项的多表修改必须在同一个本地事务中提交。
-- 取消/到期回收的是 F，后续补充才将额度重新变为池行。
-- 详细锁顺序与重试协议见 总体技术方案.md 第7节，不能仅按此表随意交换SQL顺序。

-- ========================= 0. 逻辑库存分区 =========================
-- 一行含义：商家下的一个逻辑库存分区。分区 0 为默认可售分区，不可关闭。
-- 使用场景：
--   1) 上游（如营销服务）为一场活动 / 一个渠道创建分区，再从分区 0 划拨库存进来。
--   2) 关闭：status=CLOSED 后不再接受新预留、不再补充、不接受划入；已有预留照常确认 / 释放；
--      该分区 ACTIVE 预留全部终结后，把余量划拨回其他分区（通常是分区 0）。
-- 库存服务不保存价格、时间窗口、参与资格等营销信息。
CREATE TABLE stock_partitions (
    shop_id             BIGINT NOT NULL COMMENT '商家ID',
    stock_partition_id  BIGINT NOT NULL COMMENT '逻辑库存分区ID；0=默认可售分区',
    status              VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN可预留；CLOSED停止新预留与补充，等待退回余量',
    external_ref        VARCHAR(128) CHARACTER SET utf8mb4 NULL COMMENT '上游引用（如营销活动ID），仅用于对账与排查，库存逻辑不读取',
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间，UTC',
    closed_at           DATETIME(6) NULL COMMENT '关闭时间，UTC',
    PRIMARY KEY (shop_id, stock_partition_id),
    CONSTRAINT ck_partition_identity CHECK (shop_id > 0 AND stock_partition_id >= 0),
    CONSTRAINT ck_partition_status CHECK
        ((status = 'OPEN' AND closed_at IS NULL)
         OR (status = 'CLOSED' AND closed_at IS NOT NULL AND stock_partition_id <> 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='逻辑库存分区：可售额度的逻辑划分，不代表物理仓库';

-- ========================= 1. 库存账本 =========================
-- 一行含义：一个商家、SKU、分区的真实账面库存及已发放额度。
-- 使用场景：
--   1) 初始化、调整、分区间划拨：维护 H；减少后必须保证 H >= A（必要时回收池单位）。
--   2) 池补充：SELECT ... FOR UPDATE 锁该行，只能从 H-A 中发放新单位。
--   3) 支付确认：同步减少 H 和 A；取消/到期只减少 A。
--   4) 库存查询与审计：结合池行数和 ACTIVE 明细验证 A=P+R。
--   5) 冻结：发现不变量违例时置 FROZEN，拒绝补充与迟到确认；claim/cancel/expiry 仍允许。
-- 并发要点：正常预留只领取单位行，不更新本表，避免所有预留竞争同一计数行。
--           补充、确认、释放、调整、划拨仍会竞争本表行锁，这是本方案的性能边界（秒杀用 BATCHED 缓解）。
-- 生命周期：初始化或首次划入时创建，长期保留；第一版不提供删除或在线修改池容量接口。
-- 示例：H=10000,A=1200,P=1000,R=200；还能发放8800件，可再预留总量9800件。
CREATE TABLE inventory_ledger (
    shop_id             BIGINT NOT NULL COMMENT '商家ID；库存、预留与幂等的隔离维度',
    inventory_item_id   BIGINT NOT NULL COMMENT 'SKU ID；在商家内标识一个可售库存单元',
    stock_partition_id  BIGINT NOT NULL COMMENT '逻辑库存分区ID；0=默认可售分区；分区之间不自动借库存',
    on_hand_quantity    BIGINT NOT NULL COMMENT 'H：尚未正式售出的账面库存，包含ACTIVE预留',
    allocated_quantity  BIGINT NOT NULL DEFAULT 0 COMMENT 'A：已发放额度，等于池行数P加ACTIVE预留量R',
    pool_capacity       INT NOT NULL DEFAULT 1000 COMMENT '单维度可用池行数上限；不是同时预留总量上限',
    status              VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE正常；FROZEN疑似数据损坏，停止补充与迟到确认',
    frozen_reason       VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '冻结原因枚举，如INVARIANT_VIOLATION、AUDIT_FAILED、POISON_RESERVATION、MANUAL',
    frozen_at           DATETIME(6) NULL COMMENT '冻结时间，UTC；解冻后置空',
    settlement_mode     VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'SYNC' COMMENT 'SYNC确认/释放同步改账本；BATCHED写待结算分录后合并，秒杀热点使用；可随时切换',
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '账本创建时间，UTC',
    updated_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '账本最近修改时间；正常预留不修改本行',
    PRIMARY KEY (shop_id, inventory_item_id, stock_partition_id),
    CONSTRAINT ck_ledger_identity CHECK
        (shop_id > 0 AND inventory_item_id > 0 AND stock_partition_id >= 0),
    CONSTRAINT ck_ledger_quantity CHECK
        (on_hand_quantity >= 0 AND allocated_quantity >= 0
         AND allocated_quantity <= on_hand_quantity),
    CONSTRAINT ck_ledger_capacity CHECK (pool_capacity BETWEEN 1 AND 1000),
    CONSTRAINT ck_ledger_status CHECK
        ((status = 'ACTIVE' AND frozen_reason IS NULL AND frozen_at IS NULL)
         OR (status = 'FROZEN' AND frozen_reason IS NOT NULL AND frozen_at IS NOT NULL)),
    CONSTRAINT ck_ledger_settlement CHECK (settlement_mode IN ('SYNC', 'BATCHED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='库存账本：维护真实库存及发放额度，供补充、确认、释放、调整和划拨使用';

-- ========================= 2. 可用库存单位池 =========================
-- 一行含义：一件已发放、尚未预留的库存额度，不代表有独立序列号的实物。
-- 使用场景：
--   1) 补充服务批量 INSERT：账本 A 增加多少，本表就在同一事务新增多少行。
--   2) 预留服务 SELECT ... FOR UPDATE SKIP LOCKED：跳过其他请求已锁定的单位。
--   3) 全部维度足量后，按刚才锁定的精确主键 DELETE，并写入预留明细。
--   4) 回收（负向调整 / 划出 / 分区退回）：SKIP LOCKED 锁定未领取单位后删除，A 同步减少。
-- 生命周期：补充时创建，成功预留或回收时删除；事务回滚则删除撤销，单位恢复可领取。
--           claim/cancel/expiry 不操作本表；释放额度由后续补充重新生成新单位ID。
-- 容量边界：每个 SKU/分区 最多 pool_capacity 行，不是全表最多1000行。
-- 主键用途：查询以 shop/item/partition 定位，以 unit_id 排序和锁定，避免另建维度二级索引。
-- 示例：预留3件，删除3条单位行，但 reserved_quantities 只新增一条 quantity=3 的明细。
CREATE TABLE reservation_units (
    shop_id             BIGINT NOT NULL COMMENT '商家ID；与账本维度一致',
    inventory_item_id   BIGINT NOT NULL COMMENT 'SKU ID；与账本维度一致',
    stock_partition_id  BIGINT NOT NULL COMMENT '逻辑库存分区ID；与账本维度一致',
    unit_id             BINARY(16) NOT NULL COMMENT '可领取单位ID；Java生成UUID，一行代表一件额度',
    PRIMARY KEY (shop_id, inventory_item_id, stock_partition_id, unit_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='有界可用单位池：补充时插入，预留时通过SKIP LOCKED领取并删除';

-- ========================= 3. 预留业务头 =========================
-- 一行含义：一次整单预留请求，可包含多个 SKU/分区，必须全成功或全失败。
-- 使用场景：
--   1) 创建预留：先用 SKIP LOCKED 领齐单位，再插入 ACTIVE 头，与池删除、明细插入一起提交；失败则头也回滚。
--      先领单位再插头，避免池不足回滚后，同幂等键重试在唯一索引删除标记记录上加 S 间隙锁导致死锁（已实测）。
--   2) 幂等查询：同 shop+idempotency_key 返回原预留，request_hash 检测换参数重用键。
--   3) claim/cancel/expiry：先 FOR UPDATE 锁本行，再验证状态、锁账本并完成转换。
--   4) 到期任务：通过 status+claim_deadline_at 索引找候选，获得头锁后重新校验。
-- 状态转换：ACTIVE -> CLAIMED / CANCELLED / EXPIRED；CLAIMED、CANCELLED 为绝对终态。
--           EXPIRED 只能经显式迟到确认转为 CLAIMED(claim_mode=LATE)，且必须重新取得额度。
--           claim 与 expiry 统一以创建时持久化的 claim_deadline_at 为判定线（expires_at+宽限）。
--           ACTIVE 即使已过截止时间，释放提交前仍占额度。
-- 并发要点：本行是确认、取消、到期之间的仲裁锁；获得锁后另取数据库时间判断到期。
-- 索引用途：幂等唯一键防重复预留；支付唯一键防同一支付关联两份预留；到期索引支持扫描。
-- 生命周期：成功预留时创建，终态保留用于幂等与审计；第一版不自动清理。
--           不能单独清理本表，否则会丢失明细状态及幂等依据。
CREATE TABLE reservations (
    shop_id             BIGINT NOT NULL COMMENT '商家ID；预留及幂等键按商家隔离',
    reservation_id      BINARY(16) NOT NULL COMMENT '预留ID；Java生成UUID，关联预留明细',
    idempotency_key     VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '创建请求幂等键；同商家唯一',
    request_hash        BINARY(32) NOT NULL COMMENT '规范化创建请求的SHA-256；检测同键不同参数',
    status              VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'ACTIVE占用额度；CLAIMED已扣减；CANCELLED或EXPIRED已释放',
    buyer_id            VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '买家标识；命中限购规则时必填，参与请求hash',
    ttl_seconds         INT NOT NULL COMMENT '请求预留有效期，5至900秒；参与请求hash',
    total_quantity      INT NOT NULL COMMENT '整单单位总量，等于所有明细quantity之和',
    line_count          INT NOT NULL COMMENT '合并重复 SKU/分区 后的明细条数',
    payment_reference   VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '确认扣减的支付引用；仅CLAIMED非空，同商家唯一',
    expires_at          DATETIME(6) NOT NULL COMMENT '对调用方承诺的保留截止时间，UTC；创建时用数据库时间计算',
    claim_deadline_at   DATETIME(6) NOT NULL COMMENT '普通claim截止及到期释放判定线=expires_at+宽限(0..120秒)；创建时持久化',
    claim_mode          VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'RESERVED截止前确认；LATE截止后迟到确认；仅CLAIMED非空',
    claimed_at          DATETIME(6) NULL COMMENT '转为CLAIMED的业务处理时间，UTC',
    cancelled_at        DATETIME(6) NULL COMMENT '转为CANCELLED的业务处理时间，UTC',
    expired_at          DATETIME(6) NULL COMMENT '到期释放处理时间，UTC；迟到确认后保留用于审计',
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '预留创建时间，UTC',
    updated_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '预留头最近修改时间，UTC',
    PRIMARY KEY (shop_id, reservation_id),
    UNIQUE KEY uq_reservation_idempotency (shop_id, idempotency_key),
    UNIQUE KEY uq_reservation_payment (shop_id, payment_reference),
    KEY idx_reservation_expiry (status, claim_deadline_at, shop_id, reservation_id),
    CONSTRAINT ck_reservation_status CHECK
        (status IN ('ACTIVE', 'CLAIMED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_reservation_ttl CHECK (ttl_seconds BETWEEN 5 AND 900),
    CONSTRAINT ck_reservation_deadline CHECK
        (claim_deadline_at BETWEEN expires_at AND expires_at + INTERVAL 120 SECOND),
    CONSTRAINT ck_reservation_size CHECK
        (total_quantity BETWEEN 1 AND 1000 AND line_count BETWEEN 1 AND 20),
    -- 注意：CHECK 结果为 UNKNOWN（NULL）时视为通过，可空列必须显式 IS NOT NULL / COALESCE，
    -- 不能只写 claim_mode IN (...)（实测：缺少 IS NOT NULL 时 CLAIMED + claim_mode=NULL 会被接受）。
    CONSTRAINT ck_reservation_claim CHECK
        ((status = 'CLAIMED' AND payment_reference IS NOT NULL AND claimed_at IS NOT NULL
          AND claim_mode IS NOT NULL AND claim_mode IN ('RESERVED', 'LATE'))
         OR (status <> 'CLAIMED' AND payment_reference IS NULL AND claimed_at IS NULL
          AND claim_mode IS NULL)),
    CONSTRAINT ck_reservation_cancel CHECK
        ((status = 'CANCELLED' AND cancelled_at IS NOT NULL)
         OR (status <> 'CANCELLED' AND cancelled_at IS NULL)),
    -- expired_at 非空只可能是 EXPIRED，或由 EXPIRED 迟到确认而来的 CLAIMED(LATE)
    CONSTRAINT ck_reservation_expired CHECK
        ((status = 'EXPIRED' AND expired_at IS NOT NULL)
         OR (status IN ('ACTIVE', 'CANCELLED') AND expired_at IS NULL)
         OR (status = 'CLAIMED' AND (expired_at IS NULL OR COALESCE(claim_mode, '') = 'LATE')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='整单预留头：提供幂等、状态机、支付关联及到期释放的并发仲裁';

-- ========================= 4. 预留数量明细 =========================
-- 一行含义：某个预留在一个 SKU/分区 上占用了多少件；不是一件一行。
-- 使用场景：
--   1) 创建预留：删除单位池中n行后，插入quantity=n的明细；同一事务提交。
--   2) claim：锁预留头后读取明细，按维度排序锁账本，确定每个维度扣减的H和A。
--   3) cancel/expiry：依据明细释放各维度的A，不减少H。
--   4) 预留详情与库存审计：JOIN预留头，只有status=ACTIVE的数量计入R。
-- 生命周期：成功创建后明细不可变；终态仍保留，不因claim或释放而删除。
--           不能直接SUM全表当成当前预留量，否则会把已确认和已释放历史重复计入。
-- 索引用途：主键读取整单；唯一键保证合并后的维度不重复；维度索引用于库存反查、审计与分区退回前的 ACTIVE 检查。
-- 示例：一单购买 SKU A 在分区 0 的3件、SKU B 在分区 7 的2件，本表保存2行，而非5行。
CREATE TABLE reserved_quantities (
    shop_id             BIGINT NOT NULL COMMENT '商家ID；与预留头及库存账本一致',
    reservation_id      BINARY(16) NOT NULL COMMENT '所属预留ID；逻辑关联reservations',
    line_no             SMALLINT NOT NULL COMMENT '合并并按维度排序后的明细序号，从1开始',
    inventory_item_id   BIGINT NOT NULL COMMENT '本明细预留的 SKU ID',
    stock_partition_id  BIGINT NOT NULL COMMENT '本明细预留的逻辑库存分区ID',
    quantity            INT NOT NULL COMMENT '该 SKU/分区 的预留件数；含义由预留头状态决定',
    PRIMARY KEY (shop_id, reservation_id, line_no),
    UNIQUE KEY uq_reserved_dimension
        (shop_id, reservation_id, inventory_item_id, stock_partition_id),
    KEY idx_reserved_inventory
        (shop_id, inventory_item_id, stock_partition_id, reservation_id),
    CONSTRAINT ck_reserved_quantity CHECK (quantity BETWEEN 1 AND 1000),
    CONSTRAINT ck_reserved_line CHECK (line_no BETWEEN 1 AND 20)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='不可变预留明细：按 SKU 和分区聚合数量，供确认、释放及历史审计使用';

-- ========================= 5. 库存管理操作记录 =========================
-- 一行含义：一次成功提交的管理操作在一个库存维度上的影响。初始化 / 调整各写 1 行；
--           分区间划拨在同一 operation_id 下写 2 行（line_no=1 为 TRANSFER_OUT，line_no=2 为 TRANSFER_IN）。
--           不记录 reserve/refill/claim/cancel/expiry。
-- 使用场景：
--   1) 管理端初始化 / 调整 / 划拨：先插入操作行，锁账本并修改，保存结果快照后一起提交。
--   2) 管理请求重试：同shop+operation_id及相同hash返回原快照，不重复执行。
--   3) 审计：每个维度 Σdelta − 本维度 CLAIMED 明细 = H+dH；划拨两行在 SKU 合计中相互抵消。
--   4) 负向调整 / 划出带回收时，记录回收的池单位数；回收永不影响ACTIVE预留。
-- 并发要点：操作记录和账本必须同事务；失败操作不留记录，禁止保留半完成操作。
-- 快照语义：after字段是当次操作结果，不是当前值；重试时库存可能已被其他业务改变。
-- 生命周期：成功操作后长期保留用于幂等及审计；第一版不自动归档或删除。
-- 示例：INITIALIZE delta=10000；从分区 0 划拨 100 到分区 7：OUT 行(分区0, -100) + IN 行(分区7, +100)。
CREATE TABLE inventory_operations (
    shop_id             BIGINT NOT NULL COMMENT '商家ID；管理操作幂等范围',
    operation_id        BINARY(16) NOT NULL COMMENT '调用方生成的操作UUID；同商家唯一，重试必须复用',
    line_no             TINYINT NOT NULL DEFAULT 1 COMMENT '操作内的行号：划拨 1=OUT 2=IN，其余为 1',
    request_hash        BINARY(32) NOT NULL COMMENT '规范化管理请求的SHA-256；防止同操作ID更换参数',
    operation_type      VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'INITIALIZE初始化；ADJUST调整；TRANSFER_OUT划出；TRANSFER_IN划入',
    inventory_item_id   BIGINT NOT NULL COMMENT '被操作的 SKU ID',
    stock_partition_id  BIGINT NOT NULL COMMENT '被操作的逻辑库存分区ID',
    delta_quantity      BIGINT NOT NULL COMMENT 'H的变动量；初始化为初始库存，调整正数补货负数减少，划出为负、划入为正',
    on_hand_after       BIGINT NOT NULL COMMENT '本次操作完成后的H快照，非当前库存',
    allocated_after     BIGINT NOT NULL COMMENT '本次操作完成后的A快照，供幂等返回',
    pool_capacity_after INT NOT NULL COMMENT '本次操作时的池容量快照',
    pool_reclaimed_quantity INT NOT NULL DEFAULT 0 COMMENT '减少H时从池中回收并删除的未领取单位数；只减A，不影响H的审计等式',
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '操作记录创建时间，UTC',
    PRIMARY KEY (shop_id, operation_id, line_no),
    KEY idx_inventory_operation_history
        (shop_id, inventory_item_id, stock_partition_id, created_at, operation_id),
    CONSTRAINT ck_operation_type CHECK
        (operation_type IN ('INITIALIZE', 'ADJUST', 'TRANSFER_OUT', 'TRANSFER_IN')),
    CONSTRAINT ck_operation_line CHECK
        ((operation_type = 'TRANSFER_OUT' AND line_no = 1)
         OR (operation_type = 'TRANSFER_IN' AND line_no = 2)
         OR (operation_type IN ('INITIALIZE', 'ADJUST') AND line_no = 1)),
    CONSTRAINT ck_operation_delta CHECK
        ((operation_type = 'INITIALIZE' AND delta_quantity >= 0)
         OR (operation_type = 'ADJUST' AND delta_quantity <> 0)
         OR (operation_type = 'TRANSFER_OUT' AND delta_quantity < 0)
         OR (operation_type = 'TRANSFER_IN' AND delta_quantity > 0)),
    CONSTRAINT ck_operation_snapshot CHECK
        (on_hand_after >= allocated_after AND allocated_after >= 0
         AND pool_capacity_after BETWEEN 1 AND 1000),
    CONSTRAINT ck_operation_reclaim CHECK
        (pool_reclaimed_quantity BETWEEN 0 AND 1000
         AND (pool_reclaimed_quantity = 0
              OR (operation_type = 'ADJUST' AND delta_quantity < 0)
              OR operation_type = 'TRANSFER_OUT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='库存初始化、调整与分区间划拨记录：防止管理请求重复执行，并保存结果快照用于审计';

-- ========================= 6. 待结算账本分录（BATCHED 模式） =========================
-- 一行含义：一次 claim 或释放在一个库存维度上尚未合并进账本的增量。
-- 为什么需要：秒杀时同一 SKU 的确认与超时释放集中爆发，若每次都更新同一账本行，
--             账本行锁重新成为热点（文章消除的是 reserve 热点，claim 仍同步扣账本）。
--             BATCHED 模式下 claim/释放只插入本表（每个预留独立主键，无行竞争），
--             结算任务或补充事务持账本锁时一次合并多条分录。
-- 使用场景：
--   1) claim / cancel / expiry（BATCHED）：持预留头锁后插入分录，与头状态变更同事务提交。
--   2) 结算：锁账本 -> 普通读取已提交分录 -> 合并更新账本 -> 按精确主键删除已合并分录。
--   3) 补充、迟到确认、调整、划拨、分区退回：持账本锁后先内联结算，再基于准确的 H/A 计算。
--   4) 审计与快照：把未结算分录合计纳入不变量。
-- 正确性：SYNC 与 BATCHED 两条路径在任意时刻都维持同一组不变量，因此结算模式可随时切换；
--         未结算的释放额度暂不可再售（少卖不超卖），由下一次补充内联结算立即回收。
-- 锁顺序：头 -> 买家额度 -> 账本 -> 分录 -> 单位（reserve 例外：先 SKIP LOCKED 领单位）；分录只由持账本锁的事务删除。
CREATE TABLE ledger_pending_entries (
    shop_id             BIGINT NOT NULL COMMENT '商家ID',
    inventory_item_id   BIGINT NOT NULL COMMENT 'SKU ID',
    stock_partition_id  BIGINT NOT NULL COMMENT '逻辑库存分区ID',
    reservation_id      BINARY(16) NOT NULL COMMENT '产生分录的预留ID',
    entry_type          VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'CLAIM确认扣减；RELEASE取消或到期释放',
    on_hand_delta       BIGINT NOT NULL COMMENT 'dH：CLAIM为-n，RELEASE为0',
    allocated_delta     BIGINT NOT NULL COMMENT 'dA：恒为-n',
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '分录创建时间，UTC；用于结算延迟监控',
    PRIMARY KEY (shop_id, inventory_item_id, stock_partition_id, reservation_id, entry_type),
    CONSTRAINT ck_pending_type CHECK (entry_type IN ('CLAIM', 'RELEASE')),
    CONSTRAINT ck_pending_delta CHECK
        (allocated_delta BETWEEN -1000 AND -1
         AND ((entry_type = 'CLAIM' AND on_hand_delta = allocated_delta)
              OR (entry_type = 'RELEASE' AND on_hand_delta = 0)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='BATCHED结算模式的待合并账本分录：消除秒杀时确认与释放对单一账本行的争抢';

-- ========================= 7. 限购规则 =========================
-- 一行含义：一条“买家在某范围内最多持有 N 件（ACTIVE + 已确认）”的规则。没有命中任何规则即不限购。
-- 范围（scope_type）：
--   PARTITION_ITEM      某分区的某 SKU            stock_partition_id >= 0，inventory_item_id > 0
--   PARTITION_TOTAL     某分区的全部 SKU 合计      stock_partition_id >= 0，inventory_item_id = 0
--   ITEM_ALL_PARTITIONS 某 SKU 跨全部分区          stock_partition_id = -1，inventory_item_id > 0
--   用 0 / -1 作哨兵而不是 NULL：唯一键中 NULL 互不相等，无法防止重复规则。
-- 一个预留行可能同时命中多条规则，全部满足才算通过。
-- 生命周期：修改限购值直接更新本行（只影响之后的校验）；停用置 status=DISABLED，保留以便已有占用原路退回。
--           删除后重建会得到新 rule_id，计数从零开始，这是有意的语义。
-- 上游（如营销服务）负责配置；库存服务不知道规则来自哪场活动。
CREATE TABLE purchase_limit_rules (
    rule_id             BIGINT NOT NULL AUTO_INCREMENT COMMENT '规则ID',
    shop_id             BIGINT NOT NULL COMMENT '商家ID',
    scope_type          VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'PARTITION_ITEM / PARTITION_TOTAL / ITEM_ALL_PARTITIONS',
    stock_partition_id  BIGINT NOT NULL COMMENT '分区ID；ITEM_ALL_PARTITIONS 为 -1',
    inventory_item_id   BIGINT NOT NULL COMMENT 'SKU ID；PARTITION_TOTAL 为 0',
    per_buyer_limit     INT NOT NULL COMMENT '每买家在本范围内最多 ACTIVE + 已确认 数量',
    status              VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE生效；DISABLED停用（不再校验，已有占用仍可退回）',
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间，UTC',
    updated_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '修改时间，UTC',
    PRIMARY KEY (rule_id),
    UNIQUE KEY uq_limit_scope (shop_id, scope_type, stock_partition_id, inventory_item_id),
    CONSTRAINT ck_limit_value CHECK (per_buyer_limit BETWEEN 1 AND 1000),
    CONSTRAINT ck_limit_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_limit_scope CHECK
        ((scope_type = 'PARTITION_ITEM' AND stock_partition_id >= 0 AND inventory_item_id > 0)
         OR (scope_type = 'PARTITION_TOTAL' AND stock_partition_id >= 0 AND inventory_item_id = 0)
         OR (scope_type = 'ITEM_ALL_PARTITIONS' AND stock_partition_id = -1 AND inventory_item_id > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='每买家限购规则：分区+SKU、分区合计、SKU跨分区三种范围';

-- ========================= 8. 买家额度 =========================
-- 一行含义：一个买家在一条限购规则下当前 ACTIVE 预留量与已确认量。
-- 使用场景：
--   1) reserve：领齐单位并插入头之后、删除单位之前，按 rule_id 升序 INSERT ... ON DUPLICATE KEY UPDATE
--      增加 active，持行锁复查 active + claimed <= 限购，否则整单回滚 409 BUYER_LIMIT_EXCEEDED。
--   2) claim：active -= n，claimed += n；cancel/expiry：active -= n（n 取自 reservation_quota_usages）。
--   3) lateClaim(EXPIRED)：claimed += n 并对仍 ACTIVE 的规则复查限购。
-- 并发要点：每买家每规则一行，不同买家互不竞争；同一买家并发下单在本行上串行化，正是限购需要的语义。
CREATE TABLE buyer_quotas (
    shop_id             BIGINT NOT NULL COMMENT '商家ID',
    rule_id             BIGINT NOT NULL COMMENT '限购规则ID',
    buyer_id            VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '买家标识',
    active_quantity     INT NOT NULL DEFAULT 0 COMMENT 'ACTIVE预留占用量',
    claimed_quantity    INT NOT NULL DEFAULT 0 COMMENT '已确认购买量',
    updated_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '修改时间，UTC',
    PRIMARY KEY (shop_id, rule_id, buyer_id),
    CONSTRAINT ck_buyer_quota CHECK (active_quantity >= 0 AND claimed_quantity >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='买家限购额度：与预留同事务原子校验';

-- ========================= 9. 预留额度占用 =========================
-- 一行含义：某个预留在某条规则上占用了多少件。与预留同事务写入，之后不可变。
-- 为什么需要：确认 / 释放时必须按“创建时实际占用了哪些额度行”原路调整，
--             不能按当时的规则重新计算——规则可能在活动中途被新增、停用或改值，
--             重新计算会去扣一个从未增加过的额度行，导致计数错乱。
CREATE TABLE reservation_quota_usages (
    shop_id             BIGINT NOT NULL COMMENT '商家ID',
    reservation_id      BINARY(16) NOT NULL COMMENT '预留ID',
    rule_id             BIGINT NOT NULL COMMENT '命中的限购规则ID',
    quantity            INT NOT NULL COMMENT '本预留在该规则下占用的件数',
    PRIMARY KEY (shop_id, reservation_id, rule_id),
    CONSTRAINT ck_quota_usage CHECK (quantity BETWEEN 1 AND 1000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='预留在各限购规则上的占用明细：确认与释放按此原路调整买家额度';

-- 跨表不变量 A = P + R 不能由 CHECK 保证，必须依靠事务协议和一致性审计。
-- inventory_operations 的 after 字段为当次操作提交时快照，不是当前库存。
-- 创建操作行时可填合法占位快照，在同一事务锁定账本后写入最终快照；
-- 任何失败必须整个事务回滚，不允许保留未完成操作。
