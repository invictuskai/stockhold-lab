package lab.inventory.benchmark;

import static lab.inventory.observability.BusinessOperationContext.tag;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import lab.inventory.application.TransactionRunner;
import lab.inventory.observability.BusinessOperation;
import lab.inventory.persistence.Codec;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 文章的“安全的邻居”：预留与购物车更新、支付、下单共用同一个数据库，目标是持续吞吐而不拖垮其他业务。
 * 这里用一个短事务模拟邻居：读购物车、更新购物车版本、写一条订单。仅 benchmark profile。
 */
@Component
@Profile("benchmark")
public class NeighborWorkload {

    private static final int CARTS = 10_000;

    private final TransactionRunner transactions;
    private final JdbcTemplate jdbc;

    public NeighborWorkload(TransactionRunner transactions, JdbcTemplate jdbc) {
        this.transactions = transactions;
        this.jdbc = jdbc;
    }

    public long checkout() {
        long cartId = ThreadLocalRandom.current().nextLong(1, CARTS + 1);
        return transactions.execute(BusinessOperation.NEIGHBOR, () -> {
            Long version = jdbc.queryForObject(tag("SELECT version FROM baseline_neighbor_carts WHERE cart_id = ?"),
                    Long.class, cartId);
            jdbc.update(tag("UPDATE baseline_neighbor_carts SET version = version + 1 WHERE cart_id = ?"), cartId);
            jdbc.update(tag("INSERT INTO baseline_neighbor_orders (order_id, cart_id, amount_cents) VALUES (?, ?, ?)"),
                    Codec.uuidToBytes(UUID.randomUUID()), cartId, ThreadLocalRandom.current().nextLong(100, 100_000));
            return version == null ? 0 : version + 1;
        });
    }
}
