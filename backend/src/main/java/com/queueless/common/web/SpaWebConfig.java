package com.queueless.common.web;

import java.io.IOException;
import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the production React build from {@code classpath:/static} on the same origin as the API.
 *
 * <ul>
 *   <li>{@code /assets/**} are content-hashed by Vite and cached for a year.</li>
 *   <li>Any other path that is not a file, an API or an actuator URL returns {@code index.html},
 *       so React Router can handle deep links such as {@code /tickets/123} after a reload.</li>
 * </ul>
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());

        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache())
                .resourceChain(true)
                .addResolver(new SpaFallbackResolver());
    }

    static final class SpaFallbackResolver extends PathResourceResolver {

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            if (!resourcePath.isEmpty() && !resourcePath.endsWith("/")) {
                Resource requested = location.createRelative(resourcePath);
                if (requested.exists() && requested.isReadable()) {
                    return requested;
                }
            }
            if (isServerPath(resourcePath) || looksLikeFile(resourcePath)) {
                return null; // genuine 404
            }
            Resource index = location.createRelative("index.html");
            return index.exists() && index.isReadable() ? index : null;
        }

        private static boolean isServerPath(String path) {
            return path.equals("api") || path.startsWith("api/")
                    || path.startsWith("actuator")
                    || path.startsWith("v3/api-docs")
                    || path.startsWith("swagger-ui");
        }

        private static boolean looksLikeFile(String path) {
            String lastSegment = path.substring(path.lastIndexOf('/') + 1);
            return lastSegment.contains(".");
        }
    }
}
