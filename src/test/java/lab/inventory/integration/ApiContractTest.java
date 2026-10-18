package lab.inventory.integration;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import com.jayway.jsonpath.JsonPath;
import com.zaxxer.hikari.HikariDataSource;
import lab.inventory.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 接口契约 v1 的可执行示例：状态码、响应头、响应体结构与错误格式。 */
@AutoConfigureMockMvc
class ApiContractTest extends IntegrationTestBase {

    private static final String MICROS = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private DataSource dataSource;

    private static MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder builder) {
        return builder.header("X-Admin-Token", "test-token").contentType(MediaType.APPLICATION_JSON);
    }

    private String createBody(UUID operationId, long quantity, int capacity) {
        return """
                {"operationId":"%s","inventoryItemId":2001,"locationId":3001,
                 "initialQuantity":%d,"poolCapacity":%d}""".formatted(operationId, quantity, capacity);
    }

    private void createInventoryViaApi(long shop, long quantity, int capacity) throws Exception {
        mvc.perform(admin(post("/api/v1/shops/{shop}/inventory", shop))
                .content(createBody(UUID.randomUUID(), quantity, capacity)))
                .andExpect(status().isCreated());
    }

    @Test
    void createInventoryFollowsIdempotencyContract() throws Exception {
        long shop = newShop();
        UUID operationId = UUID.randomUUID();

        mvc.perform(admin(post("/api/v1/shops/{shop}/inventory", shop))
                .content(createBody(operationId, 100000, 1000)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.operationId").value(operationId.toString()))
                .andExpect(jsonPath("$.shopId").value(shop))
                .andExpect(jsonPath("$.onHandAfter").value(100000))
                .andExpect(jsonPath("$.allocatedAfter").value(0))
                .andExpect(jsonPath("$.poolCapacityAfter").value(1000));

        mvc.perform(admin(post("/api/v1/shops/{shop}/inventory", shop))
                .content(createBody(operationId, 100000, 1000)))
                .andExpect(status().isOk());
        mvc.perform(admin(post("/api/v1/shops/{shop}/inventory", shop))
                .content(createBody(operationId, 5, 1000)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        mvc.perform(admin(post("/api/v1/shops/{shop}/inventory", shop))
                .content(createBody(UUID.randomUUID(), 100000, 1000)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVENTORY_ALREADY_EXISTS"));
    }

    @Test
    void adminEndpointsRequireTheToken() throws Exception {
        long shop = newShop();
        String body = createBody(UUID.randomUUID(), 10, 10);

        mvc.perform(post("/api/v1/shops/{shop}/inventory", shop).contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_UNAUTHORIZED"));
        mvc.perform(post("/api/v1/shops/{shop}/inventory", shop).contentType(MediaType.APPLICATION_JSON)
                .header("X-Admin-Token", "wrong").content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/api/v1/admin/invariants/check").contentType(MediaType.APPLICATION_JSON)
                .content("{\"shopId\":1,\"inventoryItemId\":1,\"locationId\":1}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/shops/{shop}/inventory/2001/locations/3001/refill", shop))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void fullReservationFlowOverHttp() throws Exception {
        long shop = newShop();
        createInventoryViaApi(shop, 100000, 1000);

        mvc.perform(admin(post("/api/v1/shops/{shop}/inventory/2001/locations/3001/refill", shop)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insertedUnits").value(1000))
                .andExpect(jsonPath("$.observedPoolBefore").value(0))
                .andExpect(jsonPath("$.allocatedAfter").value(1000))
                .andExpect(jsonPath("$.poolCapacity").value(1000));

        String reserveBody = """
                {"ttlSeconds":300,"lines":[
                  {"inventoryItemId":2001,"locationId":3001,"quantity":2},
                  {"inventoryItemId":2001,"locationId":3001,"quantity":1}]}""";
        String created = mvc.perform(post("/api/v1/shops/{shop}/reservations", shop)
                .header("Idempotency-Key", "checkout-20260923-001")
                .header("X-Request-Id", "req-01")
                .contentType(MediaType.APPLICATION_JSON).content(reserveBody))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/shops/" + shop + "/reservations/")))
                .andExpect(header().string("X-Request-Id", "req-01"))
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.shopId").value(shop))
                .andExpect(jsonPath("$.totalQuantity").value(3))
                .andExpect(jsonPath("$.createdAt", matchesPattern(MICROS)))
                .andExpect(jsonPath("$.expiresAt", matchesPattern(MICROS)))
                .andExpect(jsonPath("$.paymentReference").value(nullValue()))
                .andExpect(jsonPath("$.claimedAt").value(nullValue()))
                .andExpect(jsonPath("$.lines", hasSize(1)))
                .andExpect(jsonPath("$.lines[0].lineNo").value(1))
                .andExpect(jsonPath("$.lines[0].quantity").value(3))
                .andReturn().getResponse().getContentAsString();
        String reservationId = JsonPath.read(created, "$.reservationId");

        mvc.perform(post("/api/v1/shops/{shop}/reservations", shop)
                .header("Idempotency-Key", "checkout-20260923-001")
                .contentType(MediaType.APPLICATION_JSON).content(reserveBody))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.reservationId").value(reservationId));

        mvc.perform(get("/api/v1/shops/{shop}/reservations/by-idempotency-key", shop)
                .param("key", "checkout-20260923-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value(reservationId));
        mvc.perform(get("/api/v1/shops/{shop}/reservations/{id}", shop, reservationId))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/shops/{shop}/inventory/2001/locations/3001", shop))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onHandQuantity").value(100000))
                .andExpect(jsonPath("$.allocatedQuantity").value(1000))
                .andExpect(jsonPath("$.poolAvailableQuantity").value(997))
                .andExpect(jsonPath("$.activeReservedQuantity").value(3))
                .andExpect(jsonPath("$.unallocatedQuantity").value(99000))
                .andExpect(jsonPath("$.effectiveAvailableQuantity").value(99997))
                .andExpect(jsonPath("$.poolCapacity").value(1000))
                .andExpect(jsonPath("$.observedAt", matchesPattern(MICROS)));

        mvc.perform(post("/api/v1/shops/{shop}/reservations/{id}/claim", shop, reservationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentReference\":\"payment-20260923-001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLAIMED"))
                .andExpect(jsonPath("$.paymentReference").value("payment-20260923-001"))
                .andExpect(jsonPath("$.claimedAt", matchesPattern(MICROS)));

        mvc.perform(post("/api/v1/shops/{shop}/reservations/{id}/cancel", shop, reservationId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_RESERVATION_STATE"))
                .andExpect(jsonPath("$.retryable").value(false));

        mvc.perform(admin(post("/api/v1/admin/invariants/check"))
                .content("{\"shopId\":%d,\"inventoryItemId\":2001,\"locationId\":3001}".formatted(shop)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.onHandQuantity").value(99997))
                .andExpect(jsonPath("$.allocatedQuantity").value(997))
                .andExpect(jsonPath("$.poolAvailableQuantity").value(997))
                .andExpect(jsonPath("$.activeReservedQuantity").value(0))
                .andExpect(jsonPath("$.violations", hasSize(0)));
    }

    @Test
    void adjustmentAndCancelOverHttp() throws Exception {
        long shop = newShop();
        createInventoryViaApi(shop, 10, 1000);
        UUID operationId = UUID.randomUUID();
        String adjust = "{\"operationId\":\"%s\",\"deltaQuantity\":500}".formatted(operationId);

        mvc.perform(admin(post("/api/v1/shops/{shop}/inventory/2001/locations/3001/adjustments", shop))
                .content(adjust))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onHandAfter").value(510));
        mvc.perform(admin(post("/api/v1/shops/{shop}/inventory/2001/locations/3001/adjustments", shop))
                .content(adjust))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onHandAfter").value(510));

        String created = mvc.perform(post("/api/v1/shops/{shop}/reservations", shop)
                .header("Idempotency-Key", "cancel-me")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[{\"inventoryItemId\":2001,\"locationId\":3001,\"quantity\":4}]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reservationId = JsonPath.read(created, "$.reservationId");

        mvc.perform(post("/api/v1/shops/{shop}/reservations/{id}/cancel", shop, reservationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt", matchesPattern(MICROS)));
        mvc.perform(post("/api/v1/shops/{shop}/reservations/{id}/cancel", shop, reservationId))
                .andExpect(status().isOk());

        mvc.perform(admin(post("/api/v1/shops/{shop}/inventory/2001/locations/3001/adjustments", shop))
                .content("{\"operationId\":\"%s\",\"deltaQuantity\":-10}".formatted(UUID.randomUUID())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_ALLOCATED"));
    }

    @Test
    void errorsUseTheContractFormat() throws Exception {
        long shop = newShop();
        createInventoryViaApi(shop, 100000, 50);

        // 超过池容量：422，details 指出维度。
        mvc.perform(post("/api/v1/shops/{shop}/reservations", shop)
                .header("Idempotency-Key", "too-many")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[{\"inventoryItemId\":2001,\"locationId\":3001,\"quantity\":51}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("QUANTITY_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.details.inventoryItemId").value(2001))
                .andExpect(jsonPath("$.details.locationId").value(3001));

        mvc.perform(post("/api/v1/shops/{shop}/reservations", shop)
                .header("Idempotency-Key", "unknown-item")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[{\"inventoryItemId\":9999,\"locationId\":3001,\"quantity\":1}]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVENTORY_NOT_FOUND"));

        // 数量必须是整数：小数、字符串都不接受。
        mvc.perform(post("/api/v1/shops/{shop}/reservations", shop)
                .header("Idempotency-Key", "decimal")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[{\"inventoryItemId\":2001,\"locationId\":3001,\"quantity\":1.5}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(content().string(not(containsString("Exception"))));
        mvc.perform(post("/api/v1/shops/{shop}/reservations", shop)
                .header("Idempotency-Key", "string-quantity")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[{\"inventoryItemId\":2001,\"locationId\":3001,\"quantity\":\"1\"}]}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/shops/{shop}/reservations", shop)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[{\"inventoryItemId\":2001,\"locationId\":3001,\"quantity\":1}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        mvc.perform(get("/api/v1/shops/{shop}/reservations/{id}", shop, UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
        mvc.perform(get("/api/v1/shops/{shop}/reservations/not-a-uuid", shop))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        mvc.perform(get("/api/v1/shops/{shop}/reservations/by-idempotency-key", shop).param("key", "missing"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/shops/{shop}/inventory/9999/locations/3001", shop))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVENTORY_NOT_FOUND"));
        mvc.perform(get("/api/v1/shops/0/inventory/2001/locations/3001"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void insufficientStockIsAConflictNotBusy() throws Exception {
        long shop = newShop();
        createInventoryViaApi(shop, 2, 1000);

        mvc.perform(post("/api/v1/shops/{shop}/reservations", shop)
                .header("Idempotency-Key", "want-three")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[{\"inventoryItemId\":2001,\"locationId\":3001,\"quantity\":3}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(header().doesNotExist("Retry-After"));
    }

    @Test
    void exhaustedConnectionPoolIsServiceUnavailableNotInsufficientStock() throws Exception {
        long shop = newShop();
        createInventoryViaApi(shop, 10, 1000);
        String created = mvc.perform(post("/api/v1/shops/{shop}/reservations", shop)
                .header("Idempotency-Key", "pool-exhausted")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[{\"inventoryItemId\":2001,\"locationId\":3001,\"quantity\":1}]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reservationId = JsonPath.read(created, "$.reservationId");

        // 占满连接池：之后的请求在获取连接时超时。
        List<Connection> held = new ArrayList<>();
        try {
            int poolSize = dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
            for (int i = 0; i < poolSize; i++) {
                held.add(dataSource.getConnection());
            }

            mvc.perform(get("/api/v1/shops/{shop}/inventory/2001/locations/3001", shop))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"))
                    .andExpect(jsonPath("$.retryable").value(true));
            mvc.perform(post("/api/v1/shops/{shop}/reservations/{id}/claim", shop, reservationId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"paymentReference\":\"pay-while-down\"}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"));
        } finally {
            for (Connection connection : held) {
                connection.close();
            }
        }

        // 连接恢复后同一个 claim 正常成功：之前的失败没有留下部分状态。
        mvc.perform(post("/api/v1/shops/{shop}/reservations/{id}/claim", shop, reservationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentReference\":\"pay-while-down\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLAIMED"));
    }

    /** 接口契约 7.2：候选地点经由 HTTP 的完整语义。 */
    @Test
    void candidateLocationsOverHttp() throws Exception {
        long shop = newShop();
        createInventory(shop, ITEM_A, LOCATION_X, 2, 1000);
        createInventory(shop, ITEM_A, LOCATION_Y, 10, 1000);
        String body = """
                {"lines":[{"inventoryItemId":%d,"locationIds":[%d,%d],"quantity":3}]}"""
                .formatted(ITEM_A, LOCATION_X, LOCATION_Y);

        // 首选地点只有 2 件，整行落在第二个候选。
        mvc.perform(post("/api/v1/shops/{shop}/reservations", shop).header("Idempotency-Key", "cand-1")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines", hasSize(1)))
                .andExpect(jsonPath("$.lines[0].locationId").value((int) LOCATION_Y))
                .andExpect(jsonPath("$.lines[0].quantity").value(3));
        mvc.perform(post("/api/v1/shops/{shop}/reservations", shop).header("Idempotency-Key", "cand-1")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.lines[0].locationId").value((int) LOCATION_Y));

        // 同一幂等键、候选顺序不同：不同意图。
        mvc.perform(post("/api/v1/shops/{shop}/reservations", shop).header("Idempotency-Key", "cand-1")
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"lines":[{"inventoryItemId":%d,"locationIds":[%d,%d],"quantity":3}]}"""
                        .formatted(ITEM_A, LOCATION_Y, LOCATION_X)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        // locationId 与 locationIds 同时给出：参数错误。
        mvc.perform(post("/api/v1/shops/{shop}/reservations", shop).header("Idempotency-Key", "cand-2")
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"lines":[{"inventoryItemId":%d,"locationId":%d,"locationIds":[%d],"quantity":1}]}"""
                        .formatted(ITEM_A, LOCATION_X, LOCATION_Y)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        // 没有任何一个候选能单独满足：不拆分，报库存不足并列出候选。
        mvc.perform(post("/api/v1/shops/{shop}/reservations", shop).header("Idempotency-Key", "cand-3")
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"lines":[{"inventoryItemId":%d,"locationIds":[%d,%d],"quantity":9}]}"""
                        .formatted(ITEM_A, LOCATION_X, LOCATION_Y)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.details.inventoryItemId").value((int) ITEM_A))
                .andExpect(jsonPath("$.details.locationIds", hasSize(2)));
    }

    /** 接口契约第 5、8 节：consistency 参数与 X-Read-Source 响应头（本上下文未配置副本，eventual 读主库）。 */
    @Test
    void readConsistencyOverHttp() throws Exception {
        long shop = newShop();
        createInventory(shop, ITEM_A, LOCATION_X, 10, 1000);
        String reservationId = JsonPath.read(mvc.perform(post("/api/v1/shops/{shop}/reservations", shop)
                .header("Idempotency-Key", "read-1").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"lines":[{"inventoryItemId":%d,"locationId":%d,"quantity":2}]}"""
                        .formatted(ITEM_A, LOCATION_X)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.reservationId");

        String snapshot = "/api/v1/shops/{shop}/inventory/{item}/locations/{location}";
        mvc.perform(get(snapshot, shop, ITEM_A, LOCATION_X))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Read-Source", "primary"))
                .andExpect(jsonPath("$.activeReservedQuantity").value(2));
        mvc.perform(get(snapshot + "?consistency=eventual", shop, ITEM_A, LOCATION_X))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Read-Source", "primary"));
        mvc.perform(get("/api/v1/shops/{shop}/reservations/{id}?consistency=eventual", shop, reservationId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Read-Source", "primary"))
                .andExpect(jsonPath("$.reservationId").value(reservationId));
        mvc.perform(get("/api/v1/shops/{shop}/reservations/by-idempotency-key?key=read-1&consistency=strong", shop))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Read-Source", "primary"));
        mvc.perform(get(snapshot + "?consistency=stale", shop, ITEM_A, LOCATION_X))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void openApiDocumentIsPublished() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/shops/{shopId}/reservations']").exists());
    }
}
