package lab.inventory.observability;

/** 固定白名单业务标签；禁止由用户输入、UUID 或商品 ID 拼接。 */
public enum BusinessOperation {
    RESERVE("reserve"),
    CLAIM("claim"),
    CANCEL("cancel"),
    REFILL("refill"),
    EXPIRY("expiry"),
    ADMIN("admin"),
    QUERY("query"),
    BENCHMARK("benchmark"),
    /** 压测注入：模拟文章中结账路径上其他代码的长事务（文章的标签示例即 checkout_completion）。 */
    CHECKOUT_COMPLETION("checkout_completion"),
    /** 压测：与预留共用数据库的其他业务（购物车更新 + 下单）。 */
    NEIGHBOR("neighbor"),
    UNTAGGED("untagged");

    private final String tag;
    private final String sqlComment;

    BusinessOperation(String tag) {
        this.tag = tag;
        this.sqlComment = "/* conn_tag:" + tag + " */ ";
    }

    public String tag() {
        return tag;
    }

    public String sqlComment() {
        return sqlComment;
    }
}
