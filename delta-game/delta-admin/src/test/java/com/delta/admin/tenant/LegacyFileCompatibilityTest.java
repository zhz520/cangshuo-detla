package com.delta.admin.tenant;

import com.delta.admin.config.SwaggerConfig;
import com.delta.common.file.controller.ImageThumbnailController;
import com.delta.common.tenant.TenantProperties;
import com.delta.common.tenant.TenantRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.mock.web.MockServletContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;
import org.springframework.web.servlet.resource.ResourceResolverChain;

import javax.imageio.ImageIO;
import jakarta.servlet.http.HttpServletRequest;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Historical /file/ URLs are only served when the shared deployment has exactly one active tenant. */
class LegacyFileCompatibilityTest {
    private static final String DATE = "2026/11/10";
    private static final String NAME = "a".repeat(32) + ".png";
    private static final ResourceResolverChain NO_CHAIN = new ResourceResolverChain() {
        @Override public Resource resolveResource(HttpServletRequest request, String requestPath,
                List<? extends Resource> locations) { return null; }
        @Override public String resolveUrlPath(String resourcePath, List<? extends Resource> locations) { return null; }
    };

    @TempDir Path uploads;

    @Test
    void oneActiveTenantServesLegacyThumbnailAndStaticFile() throws Exception {
        TenantProperties properties = properties("tenant-a");
        TenantRegistry registry = new TenantRegistry(properties);
        Path source = uploads.resolve(DATE).resolve(NAME);
        writePng(source);

        var response = controller(properties, registry).thumbnail(320, "2026", "11", "10", NAME);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().exists()).isTrue();
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(staticResolver(properties, registry, uploads)
                .resolveResource(new MockHttpServletRequest("GET", "/file/" + DATE + "/" + NAME),
                        DATE + "/" + NAME, List.of(uploadLocation()), NO_CHAIN))
                .isNotNull();
    }

    @Test
    void multipleActiveTenantsRejectLegacyThumbnailAndStaticFile() throws Exception {
        TenantProperties properties = properties("tenant-a", "tenant-b");
        TenantRegistry registry = new TenantRegistry(properties);
        Path source = uploads.resolve(DATE).resolve(NAME);
        writePng(source);

        assertThatThrownBy(() -> controller(properties, registry).thumbnail(320, "2026", "11", "10", NAME))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
        assertThat(staticResolver(properties, registry, uploads)
                .resolveResource(new MockHttpServletRequest("GET", "/file/" + DATE + "/" + NAME),
                        DATE + "/" + NAME, List.of(uploadLocation()), NO_CHAIN))
                .isNull();
    }

    @Test
    void multipleActiveTenantsKeepTenantScopedThumbnailAndStaticFile() throws Exception {
        TenantProperties properties = properties("tenant-a", "tenant-b");
        TenantRegistry registry = new TenantRegistry(properties);
        Path source = uploads.resolve("tenant-a").resolve(DATE).resolve(NAME);
        writePng(source);

        var response = controller(properties, registry).tenantThumbnail(320, "tenant-a", "2026", "11", "10", NAME);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().exists()).isTrue();
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(staticResolver(properties, registry, uploads)
                .resolveResource(new MockHttpServletRequest("GET", "/file/tenant-a/" + DATE + "/" + NAME),
                        "tenant-a/" + DATE + "/" + NAME, List.of(uploadLocation()), NO_CHAIN))
                .isNotNull();
        assertThat(staticResolver(properties, registry, uploads)
                .resolveResource(new MockHttpServletRequest("GET", "/file/tenant-b/" + DATE + "/" + NAME),
                        "tenant-b/" + DATE + "/" + NAME, List.of(uploadLocation()), NO_CHAIN))
                .isNull();
    }

    @Test
    void inactiveTenantCannotReadScopedFiles() throws Exception {
        TenantProperties properties = properties("tenant-a", "tenant-b");
        properties.getTenants().get("tenant-b").setActive(false);
        TenantRegistry registry = new TenantRegistry(properties);
        writePng(uploads.resolve("tenant-b").resolve(DATE).resolve(NAME));

        assertThatThrownBy(() -> controller(properties, registry)
                .tenantThumbnail(320, "tenant-b", "2026", "11", "10", NAME))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
        assertThat(staticResolver(properties, registry, uploads)
                .resolveResource(new MockHttpServletRequest("GET", "/file/tenant-b/" + DATE + "/" + NAME),
                        "tenant-b/" + DATE + "/" + NAME, List.of(uploadLocation()), NO_CHAIN))
                .isNull();
    }

    private static TenantProperties properties(String... activeTenants) {
        TenantProperties properties = new TenantProperties();
        properties.setEnabled(true);
        for (String id : activeTenants) {
            TenantProperties.Tenant tenant = new TenantProperties.Tenant();
            tenant.setHosts(List.of(id + ".example.test"));
            properties.getTenants().put(id, tenant);
        }
        return properties;
    }

    private ImageThumbnailController controller(TenantProperties properties, TenantRegistry registry) {
        ImageThumbnailController controller = new ImageThumbnailController();
        ReflectionTestUtils.setField(controller, "uploadPath", uploads.toString());
        ReflectionTestUtils.setField(controller, "tenantProperties", properties);
        ReflectionTestUtils.setField(controller, "tenantRegistry", registry);
        return controller;
    }

    private UrlResource uploadLocation() throws Exception {
        return new UrlResource(uploads.toUri());
    }

    private PathResourceResolver staticResolver(TenantProperties properties, TenantRegistry registry, Path root)
            throws Exception {
        SwaggerConfig config = new SwaggerConfig();
        ReflectionTestUtils.setField(config, "uploadPath", root.toString());
        ReflectionTestUtils.setField(config, "tenantProperties", properties);
        ReflectionTestUtils.setField(config, "tenantRegistry", registry);
        ResourceHandlerRegistry handlers = new ResourceHandlerRegistry(
                new StaticApplicationContext(), new MockServletContext());
        config.addResourceHandlers(handlers);
        @SuppressWarnings("unchecked")
        List<ResourceHandlerRegistration> registrations =
                (List<ResourceHandlerRegistration>) ReflectionTestUtils.getField(handlers, "registrations");
        ResourceHandlerRegistration fileRegistration = registrations.stream()
                .filter(registration -> {
                    String[] patterns = ReflectionTestUtils.invokeMethod(registration, "getPathPatterns");
                    return patterns != null && List.of(patterns).contains("/file/**");
                })
                .findFirst().orElseThrow();
        ResourceHttpRequestHandler handler = ReflectionTestUtils.invokeMethod(fileRegistration, "getRequestHandler");
        return handler.getResourceResolvers().stream()
                .filter(PathResourceResolver.class::isInstance)
                .map(PathResourceResolver.class::cast)
                .findFirst().orElseThrow();
    }

    private static void writePng(Path target) throws Exception {
        Files.createDirectories(target.getParent());
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, "png", target.toFile());
    }
}
