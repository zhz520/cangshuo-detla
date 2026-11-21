package com.delta.admin.tenant;

import com.delta.admin.config.SwaggerConfig;
import com.delta.common.config.SecurityConfig;
import com.delta.common.exception.GlobalExceptionHandler;
import com.delta.common.file.controller.ImageThumbnailController;
import com.delta.common.security.filter.JwtAuthenticationFilter;
import com.delta.common.security.handler.AccessDeniedHandlerImpl;
import com.delta.common.security.handler.AuthenticationEntryPointImpl;
import com.delta.common.security.service.TokenService;
import com.delta.common.tenant.TenantContext;
import com.delta.common.tenant.TenantProperties;
import com.delta.common.tenant.TenantRegistry;
import com.delta.common.tenant.TenantRequestFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Servlet slice for the existing path and tenant rules, not an attachment ownership policy. */
class FileAccessBoundaryTest {
    private static final String A = "tenant-a";
    private static final String B = "tenant-b";
    private static final String INACTIVE = "tenant-inactive";
    private static final String IMAGE_NAME = "a".repeat(32) + ".png";
    private static final String IMAGE_PATH = "2026/11/10/" + IMAGE_NAME;
    private static final String ORIGINAL = "/file/";
    private static final String THUMBNAIL = "/file/thumbnail/160/";
    private static final int RED = 0xffff0000;
    private static final int BLUE = 0xff0000ff;
    private static final String TEST_TOKEN = "synthetic-admin-session";
    private static final String TEST_SESSION = "test-session";

    @TempDir Path uploads;
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "synthetic-uploads", Map.of("file.upload.path", uploads.toString())));
        // A prebuilt test double keeps Redis/account services out of this servlet slice.
        TokenService tokens = mock(TokenService.class);
        when(tokens.getToken(any())).thenCallRealMethod();
        context.addBeanFactoryPostProcessor(factory -> factory.registerSingleton("tokenService", tokens));
        context.register(Config.class);
        context.refresh();
        // In the servlet container the tenant filter precedes the Spring Security proxy.
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(TenantRequestFilter.class))
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void anonymousLegacyImagesAndThumbnailsStayIsolatedAcrossHosts() throws Exception {
        byte[] a = writeImage(A + "/" + IMAGE_PATH, RED);
        byte[] b = writeImage(B + "/" + IMAGE_PATH, BLUE);
        assertThat(a).isNotEqualTo(b);

        // Same URL and one MVC instance: A/B/A catches a path-only resource cache.
        for (String tenant : List.of(A, B, A)) {
            assertOriginal(request(tenant, ORIGINAL + IMAGE_PATH), tenant.equals(A) ? a : b);
            assertThumbnail(request(tenant, THUMBNAIL + IMAGE_PATH), tenant.equals(A) ? RED : BLUE);
        }
        assertThat(TenantContext.currentIdOrNull()).isNull();
    }

    @Test
    void scopedOriginalAndThumbnailRequireTheMatchingHostTenant() throws Exception {
        String scoped = A + "/" + IMAGE_PATH;
        byte[] a = writeImage(scoped, RED);
        assertOriginal(request(A, ORIGINAL + scoped), a);
        assertThumbnail(request(A, THUMBNAIL + scoped), RED);

        stubAdminSession(B);
        for (String prefix : List.of(ORIGINAL, THUMBNAIL)) {
            mvc.perform(asAdmin(request(B, prefix + scoped)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(404));
        }
        // A historical URL must not fall back to the shared root either.
        writeImage(IMAGE_PATH, RED);
        for (String prefix : List.of(ORIGINAL, THUMBNAIL)) {
            mvc.perform(request(B, prefix + IMAGE_PATH))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(404));
        }
    }

    @Test
    void unregisteredAndInactiveHostsCannotReachTheFileHandler() throws Exception {
        writeImage(A + "/" + IMAGE_PATH, RED);
        writeImage(INACTIVE + "/" + IMAGE_PATH, BLUE);
        assertBeforeMvc(mvc.perform(request("unregistered", ORIGINAL + A + "/" + IMAGE_PATH)), 404);
        assertBeforeMvc(mvc.perform(request(INACTIVE, ORIGINAL + INACTIVE + "/" + IMAGE_PATH)), 503);
    }

    @Test
    void reservedDirectoriesAreDeniedBeforeMvcEvenForAnAuthenticatedAdmin() throws Exception {
        stubAdminSession(A);
        for (String directory : List.of("wxpay", ".thumbnails")) {
            String relative = directory + "/" + IMAGE_NAME;
            writeImage(relative, RED);
            assertBeforeMvc(mvc.perform(request(A, ORIGINAL + relative)), 401);
            assertBeforeMvc(mvc.perform(asAdmin(request(A, ORIGINAL + relative))), 403);
        }
    }

    @Test
    void adminSessionFromAnotherTenantIsNotAcceptedByTheFileSecurityChain() throws Exception {
        stubAdminSession(B);

        assertBeforeMvc(mvc.perform(asAdmin(request(A, ORIGINAL + "wxpay/" + IMAGE_NAME))), 401);

        verify(context.getBean(TokenService.class), never()).getUserContext(TEST_SESSION);
    }

    @Test
    void privateStorageAndNonMediaFilesAreNotResolvedAsPublicResources() throws Exception {
        for (String relative : List.of(
                "private/wxpay/" + A + "/" + IMAGE_NAME,
                A + "/.thumbnails/" + IMAGE_NAME,
                A + "/" + IMAGE_PATH.replace(".png", ".txt"))) {
            writeImage(relative, RED);
            mvc.perform(request(A, ORIGINAL + relative))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(404));
        }
    }

    @Test
    void complaintPrivateDirectoriesAreNeverServedByThePublicFileRoute() throws Exception {
        String privateEvidence = A + "/.private/complaints/" + IMAGE_NAME;
        writeImage(privateEvidence, RED);

        assertBeforeMvc(mvc.perform(request(A, ORIGINAL + privateEvidence)), 401);
        stubAdminSession(A);
        assertBeforeMvc(mvc.perform(asAdmin(request(A, ORIGINAL + privateEvidence))), 403);
    }

    private void assertOriginal(MockHttpServletRequestBuilder request, byte[] expected) throws Exception {
        mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("public")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(expected));
    }

    private void assertThumbnail(MockHttpServletRequestBuilder request, int color) throws Exception {
        byte[] bytes = mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("public")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse().getContentAsByteArray();
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(image).isNotNull();
        assertThat(image.getRGB(1, 1)).isEqualTo(color);
    }

    private static void assertBeforeMvc(ResultActions response, int code) throws Exception {
        response.andExpect(status().is(code))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(result -> assertThat(result.getHandler()).isNull());
    }

    private byte[] writeImage(String relative, int color) throws Exception {
        Path path = uploads.resolve(relative);
        Files.createDirectories(path.getParent());
        // An alpha channel keeps generated thumbnails in lossless PNG for exact pixel assertions.
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(1, 1, color);
        assertThat(ImageIO.write(image, "png", path.toFile())).isTrue();
        return Files.readAllBytes(path);
    }

    private void stubAdminSession(String tenant) {
        TokenService tokens = context.getBean(TokenService.class);
        when(tokens.parseToken(TEST_TOKEN)).thenReturn(Jwts.claims()
                .add("uuid", TEST_SESSION).add("tenantId", tenant)
                .add("userId", 1L).add("userType", "ADMIN").build());
        when(tokens.getUserContext(TEST_SESSION)).thenReturn(
                Map.of("tenantId", tenant, "userId", 1L, "userType", "ADMIN"));
    }

    private static MockHttpServletRequestBuilder request(String tenant, String path) {
        return get(path).with(request -> {
            request.setServerName(host(tenant));
            return request;
        });
    }

    private static MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + TEST_TOKEN);
    }

    private static String host(String tenant) {
        return tenant + ".example.test";
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({SecurityConfig.class, JwtAuthenticationFilter.class, AuthenticationEntryPointImpl.class,
            AccessDeniedHandlerImpl.class, TenantRequestFilter.class, TenantRegistry.class,
            SwaggerConfig.class, ImageThumbnailController.class, GlobalExceptionHandler.class})
    static class Config {
        @Bean
        TenantProperties tenantProperties() {
            TenantProperties properties = new TenantProperties();
            properties.setEnabled(true);
            for (String id : List.of(A, B, INACTIVE)) {
                TenantProperties.Tenant tenant = new TenantProperties.Tenant();
                tenant.setHosts(List.of(host(id)));
                tenant.setActive(!id.equals(INACTIVE));
                properties.getTenants().put(id, tenant);
            }
            return properties;
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
