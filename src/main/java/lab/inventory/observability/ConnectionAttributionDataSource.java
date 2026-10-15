package lab.inventory.observability;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * 记录连接获取等待，以及从获取成功到逻辑连接 close 的持有时间。
 * 业务标签在获取连接时固定，之后线程上下文变化不影响已借出连接的归因。
 * 这是应用侧 Hikari 逻辑连接的持有时间，不等同于代理后端连接占用。
 */
public class ConnectionAttributionDataSource extends DelegatingDataSource {

    private final Supplier<InventoryMetrics> metrics;

    public ConnectionAttributionDataSource(DataSource target, Supplier<InventoryMetrics> metrics) {
        super(target);
        this.metrics = metrics;
    }

    @Override
    public Connection getConnection() throws SQLException {
        BusinessOperation operation = BusinessOperationContext.current();
        long start = System.nanoTime();
        Connection connection;
        try {
            connection = obtainTargetDataSource().getConnection();
        } finally {
            record(m -> m.recordConnectionAcquire(operation, System.nanoTime() - start));
        }
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                new ConnectionHandler(connection, operation));
    }

    private void record(java.util.function.Consumer<InventoryMetrics> action) {
        InventoryMetrics target = metrics.get();
        if (target != null) {
            action.accept(target);
        }
    }

    private final class ConnectionHandler implements InvocationHandler {
        private final Connection target;
        private final BusinessOperation operation;
        private final long acquiredAt = System.nanoTime();
        private boolean closed;

        private ConnectionHandler(Connection target, BusinessOperation operation) {
            this.target = target;
            this.operation = operation;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.equals("close")) {
                try {
                    return method.invoke(target, args);
                } catch (InvocationTargetException e) {
                    throw e.getTargetException();
                } finally {
                    if (!closed) {
                        closed = true;
                        record(m -> m.recordConnectionHold(operation, System.nanoTime() - acquiredAt));
                    }
                }
            }
            if (name.equals("unwrap") && args != null && args.length == 1 && ((Class<?>) args[0]).isInstance(target)) {
                return target;
            }
            Object result;
            try {
                result = method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
            if (result instanceof Statement statement && method.getReturnType().isInterface()) {
                return Proxy.newProxyInstance(
                        Statement.class.getClassLoader(),
                        new Class<?>[] {method.getReturnType()},
                        new StatementHandler(statement, operation));
            }
            return result;
        }
    }

    /** 只测量 execute* 调用，得到与事务时长、连接持有时间分开的 SQL 执行时间。 */
    private final class StatementHandler implements InvocationHandler {
        private final Statement target;
        private final BusinessOperation operation;

        private StatementHandler(Statement target, BusinessOperation operation) {
            this.target = target;
            this.operation = operation;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            boolean execute = method.getName().startsWith("execute");
            long start = execute ? System.nanoTime() : 0;
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            } finally {
                if (execute) {
                    record(m -> m.recordSql(operation, System.nanoTime() - start));
                }
            }
        }
    }
}
