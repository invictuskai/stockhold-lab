package lab.inventory.application;

import java.util.function.Consumer;

import org.springframework.stereotype.Component;

/**
 * 事务内的可控故障 / 暂停点，供回滚与竞态测试使用。
 * 默认无任何动作，也不通过 HTTP API 暴露。
 */
@Component
public class FaultInjector {

    public enum Point {
        RESERVE_AFTER_UNIT_DELETE,
        RESERVE_AFTER_FIRST_LINE,
        CLAIM_AFTER_LEDGER_UPDATE,
        RELEASE_AFTER_LEDGER_UPDATE,
        REFILL_AFTER_COUNT,
        REFILL_AFTER_ALLOCATED_UPDATE,
        REFILL_AFTER_FIRST_BATCH,
        /** 迁移实验：旧系统确认的两步（Redis 与 MySQL 账本）之间。 */
        LEGACY_CLAIM_BETWEEN_SYSTEMS
    }

    private static final Consumer<Point> NONE = point -> {
    };

    private volatile Consumer<Point> hook = NONE;

    public void at(Point point) {
        hook.accept(point);
    }

    public void install(Consumer<Point> hook) {
        this.hook = hook;
    }

    public void clear() {
        this.hook = NONE;
    }
}
