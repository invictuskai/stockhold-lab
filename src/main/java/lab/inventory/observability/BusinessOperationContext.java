package lab.inventory.observability;

/**
 * 当前线程的业务操作标签。必须用 try-with-resources 打开，
 * 保证异常、回滚和线程复用后恢复到之前的值，避免标签串线。
 */
public final class BusinessOperationContext {

    private static final ThreadLocal<BusinessOperation> CURRENT = new ThreadLocal<>();

    private BusinessOperationContext() {
    }

    public static BusinessOperation current() {
        BusinessOperation operation = CURRENT.get();
        return operation == null ? BusinessOperation.UNTAGGED : operation;
    }

    public static Scope open(BusinessOperation operation) {
        BusinessOperation previous = CURRENT.get();
        CURRENT.set(operation);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    /** 给 SQL 加上当前操作的固定 conn_tag 注释。 */
    public static String tag(String sql) {
        return current().sqlComment() + sql;
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
