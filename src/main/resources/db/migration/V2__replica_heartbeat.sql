-- 只读副本的复制延迟心跳（pt-heartbeat 的做法）：主库定期写入当前时间，经复制到达副本；
-- 副本上“最新心跳的年龄”即副本落后程度的上界。只有一行。依赖主库与副本的时钟同步（NTP）。
CREATE TABLE replica_heartbeat (
    id       TINYINT NOT NULL COMMENT '固定为 1',
    beat_at  DATETIME(6) NOT NULL COMMENT '主库最近一次写入心跳的 UTC 时间',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='副本延迟心跳：读副本前据此判断副本是否足够新';

INSERT INTO replica_heartbeat (id, beat_at) VALUES (1, UTC_TIMESTAMP(6));
