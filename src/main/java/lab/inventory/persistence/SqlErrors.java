package lab.inventory.persistence;

import java.sql.SQLException;

/** 直接按 MySQL 错误码分类，不依赖 Spring 异常翻译器在不同版本下的映射差异。 */
public final class SqlErrors {

    private static final int ER_DUP_ENTRY = 1062;
    private static final int ER_LOCK_WAIT_TIMEOUT = 1205;
    private static final int ER_LOCK_DEADLOCK = 1213;

    private SqlErrors() {
    }

    /** 死锁 1213 / SQLSTATE 40001 或锁等待超时 1205：必须回滚整个事务后才能重试。 */
    public static boolean isTransientLockFailure(Throwable error) {
        SQLException sql = findSqlException(error);
        return sql != null && (sql.getErrorCode() == ER_LOCK_DEADLOCK
                || sql.getErrorCode() == ER_LOCK_WAIT_TIMEOUT
                || "40001".equals(sql.getSQLState()));
    }

    public static boolean isDuplicateKey(Throwable error) {
        SQLException sql = findSqlException(error);
        return sql != null && sql.getErrorCode() == ER_DUP_ENTRY;
    }

    /** 重复键错误是否来自指定索引；MySQL 消息形如 "... for key 'table.index'"。 */
    public static boolean isDuplicateKey(Throwable error, String indexName) {
        SQLException sql = findSqlException(error);
        return sql != null && sql.getErrorCode() == ER_DUP_ENTRY
                && sql.getMessage() != null
                && (sql.getMessage().endsWith("." + indexName + "'")
                        || sql.getMessage().endsWith("'" + indexName + "'"));
    }

    private static SQLException findSqlException(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql) {
                return sql;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return null;
    }
}
