package lab.inventory.migration;

/**
 * 文章中的迁移阶段（按店铺设置，存于 Redis，所有应用实例共享）：
 * 先以 Redis 为事实来源并影子写 MySQL 比对，再切换为以 MySQL 为准、保留 Redis 双写作为回切开关。
 */
public enum MigrationMode {
    /** 迁移前：只有旧 Redis 系统。 */
    REDIS_ONLY(false, false),
    /** 影子阶段：Redis 决定结果，MySQL 用同一请求影子执行并比对。 */
    REDIS_PRIMARY(false, true),
    /** 切换后：MySQL 决定结果，Redis 继续双写，回切时它仍持有完整的预留视图。 */
    MYSQL_PRIMARY(true, true),
    /** 迁移完成：只有 MySQL。 */
    MYSQL_ONLY(true, false);

    private final boolean mysqlPrimary;
    private final boolean shadowed;

    MigrationMode(boolean mysqlPrimary, boolean shadowed) {
        this.mysqlPrimary = mysqlPrimary;
        this.shadowed = shadowed;
    }

    public boolean mysqlPrimary() {
        return mysqlPrimary;
    }

    public boolean shadowed() {
        return shadowed;
    }
}
