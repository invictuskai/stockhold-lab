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
