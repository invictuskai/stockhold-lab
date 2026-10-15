package lab.inventory.config;

import com.zaxxer.hikari.HikariDataSource;
import lab.inventory.observability.ConnectionAttributionDataSource;
import lab.inventory.observability.InventoryMetrics;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

    /** 用连接归因包装 Hikari；Hikari 自带的 active/idle/pending 指标仍由 Boot 解包后绑定。 */
    @Bean
    static BeanPostProcessor connectionAttributionPostProcessor(ObjectProvider<InventoryMetrics> metrics) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof HikariDataSource hikari) {
                    return new ConnectionAttributionDataSource(hikari, metrics::getIfAvailable);
                }
                return bean;
            }
        };
    }
}
