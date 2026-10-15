package lab.inventory.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lab.inventory.config.StockholdProperties;
import lab.inventory.domain.exception.ApiException;
import lab.inventory.domain.exception.ErrorCode;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String REQUEST_ID_ATTRIBUTE = "stockhold.requestId";
    public static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";

    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    private final StockholdProperties.Admin admin;

    public WebConfig(StockholdProperties properties) {
        this.admin = properties.admin();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (handler instanceof HandlerMethod method
                        && (AnnotatedElementUtils.hasAnnotation(method.getMethod(), AdminApi.class)
                                || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), AdminApi.class))) {
                    requireAdmin(request.getHeader(ADMIN_TOKEN_HEADER));
                }
                return true;
            }
        });
    }

    private void requireAdmin(String presented) {
        if (!admin.enabled() || admin.token().isBlank()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Admin API is not enabled in this profile.");
        }
        if (presented == null || presented.isEmpty()) {
            throw new ApiException(ErrorCode.ADMIN_UNAUTHORIZED, "X-Admin-Token is required.");
        }
        if (!MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8),
                admin.token().getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Invalid admin token.");
        }
    }

    /** X-Request-Id 只用于追踪，不作为幂等键；不合规的输入值直接丢弃并重新生成。 */
    @Bean
    OncePerRequestFilter requestIdFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain chain) throws ServletException, IOException {
                String requestId = request.getHeader(REQUEST_ID_HEADER);
                if (requestId == null || !SAFE_REQUEST_ID.matcher(requestId).matches()) {
                    requestId = "req-" + UUID.randomUUID();
                }
                request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
                response.setHeader(REQUEST_ID_HEADER, requestId);
                MDC.put("requestId", requestId);
                try {
                    chain.doFilter(request, response);
                } finally {
                    MDC.remove("requestId");
                }
            }
        };
    }
}
