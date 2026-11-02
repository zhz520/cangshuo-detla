package com.delta.admin;

import com.delta.admin.controller.IdentityVerificationController;
import com.delta.admin.platform.PlatformService;
import com.delta.admin.service.IdentityVerificationCleanup;
import com.delta.common.config.SecurityConfig;
import com.delta.common.exception.GlobalExceptionHandler;
import com.delta.common.security.filter.JwtAuthenticationFilter;
import com.delta.common.security.handler.AccessDeniedHandlerImpl;
import com.delta.common.security.handler.AuthenticationEntryPointImpl;
import com.delta.common.security.service.RateLimitService;
import com.delta.common.security.service.StaffPermissions;
import com.delta.common.security.service.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(classes = IdentityVerificationIntegrationTest.Config.class, properties = {
        "spring.profiles.active=test",
        "spring.datasource.url=jdbc:h2:mem:identity;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER,LEVEL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.type=com.zaxxer.hikari.HikariDataSource",
        "identity.hash-pepper=test-pepper",
        "identity.encryption-key=test-encryption-key"
})
@AutoConfigureMockMvc
@Sql(scripts = "/identity-schema.sql", config = @org.springframework.test.context.jdbc.SqlConfig(encoding = "UTF-8"))
class IdentityVerificationIntegrationTest {
    @Configuration
    @EnableAutoConfiguration(excludeName = "com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure")
    @Import({SecurityConfig.class, JwtAuthenticationFilter.class, AuthenticationEntryPointImpl.class,
            AccessDeniedHandlerImpl.class, GlobalExceptionHandler.class, StaffPermissions.class,
            IdentityVerificationController.class, IdentityVerificationCleanup.class})
    static class Config {}

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired IdentityVerificationCleanup cleanup;
    @MockBean TokenService tokens;
    @MockBean PlatformService platform;
    @MockBean RateLimitService limits;

    @BeforeEach void enableFeature() {
        doNothing().when(platform).requireFeature(eq("identity_verification"), anyString());
    }

    RequestPostProcessor role(String type, long id) {
        return authentication(new UsernamePasswordAuthenticationToken(Map.of("userId", id, "userType", type), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + type))));
    }

    @Test void userCanSubmitPrivateIdentityDocumentsAndAdminCanApprove() throws Exception {
        mvc.perform(multipart("/user/identity/image").file(image("front.png")).param("side", "front").with(role("USER", 7)))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(multipart("/user/identity/image").file(image("back.png")).param("side", "back").with(role("USER", 7)))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(post("/user/identity/submit").with(role("USER", 7)).contentType("application/json")
                        .content("{\"realName\":\"张三\",\"idNumber\":\"11010519491231002X\",\"privacyAccepted\":true}"))
                .andExpect(jsonPath("$.code").value(200));

        var row = jdbc.queryForMap("SELECT status,id_number_hash,id_number_masked,front_image,privacy_version FROM identity_verification WHERE user_id=7");
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("id_number_hash")).isNotEqualTo("11010519491231002X");
        assertThat(row.get("id_number_masked")).isEqualTo("110***********002X");
        assertThat(row.get("front_image")).isNotNull();
        assertThat(String.valueOf(row.get("front_image"))).doesNotStartWith("/9j/");
        assertThat(row.get("privacy_version")).isEqualTo("2026-11-02");

        mvc.perform(get("/admin/identity/applications?status=PENDING").with(role("ADMIN", 1)))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data[0].user_id").value(7));
        mvc.perform(get("/admin/identity/applications/7").with(role("ADMIN", 1)))
                .andExpect(result -> assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store"))
                .andExpect(jsonPath("$.data.frontImage").value(org.hamcrest.Matchers.startsWith("data:image/jpeg;base64,")));
        mvc.perform(put("/admin/identity/applications/7").with(role("ADMIN", 1)).contentType("application/json")
                        .content("{\"approved\":true,\"note\":\"资料一致\"}"))
                .andExpect(jsonPath("$.code").value(200));

        var reviewed = jdbc.queryForMap("SELECT status,real_name,review_note,front_image,back_image FROM identity_verification WHERE user_id=7");
        assertThat(reviewed.get("status")).isEqualTo("APPROVED");
        assertThat(reviewed.get("real_name")).isEqualTo("张三");
        assertThat(reviewed.get("front_image")).isNull();
        assertThat(reviewed.get("back_image")).isNull();
    }

    @Test void unavailableFeatureDoesNotAddNavigationEntryAndCannotSubmit() throws Exception {
        doThrow(new com.delta.common.exception.BusinessException(403, "实名认证功能尚未获得有效授权"))
                .when(platform).requireFeature(eq("identity_verification"), anyString());
        mvc.perform(get("/user/identity/status").with(role("USER", 7)))
                .andExpect(jsonPath("$.data.available").value(false))
                .andExpect(jsonPath("$.data.status").value("UNAVAILABLE"));
        mvc.perform(post("/user/identity/submit").with(role("USER", 7)).contentType("application/json")
                        .content("{\"realName\":\"张三\",\"idNumber\":\"11010519491231002X\",\"privacyAccepted\":true}"))
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test void duplicateDocumentIsRejectedAndApprovedIdentityCannotBeResubmitted() throws Exception {
        uploadBoth(7);
        mvc.perform(post("/user/identity/submit").with(role("USER", 7)).contentType("application/json")
                        .content("{\"realName\":\"张三\",\"idNumber\":\"11010519491231002X\",\"privacyAccepted\":true}"))
                .andExpect(jsonPath("$.code").value(200));
        uploadBoth(8);
        mvc.perform(post("/user/identity/submit").with(role("USER", 8)).contentType("application/json")
                        .content("{\"realName\":\"李四\",\"idNumber\":\"11010519491231002X\",\"privacyAccepted\":true}"))
                .andExpect(jsonPath("$.code").value(409));
        mvc.perform(put("/admin/identity/applications/7").with(role("ADMIN", 1)).contentType("application/json")
                        .content("{\"approved\":true,\"note\":\"资料一致\"}"))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(post("/user/identity/submit").with(role("USER", 7)).contentType("application/json")
                        .content("{\"realName\":\"张三\",\"idNumber\":\"11010519491231002X\",\"privacyAccepted\":true}"))
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test void abandonedDraftPhotosArePurgedAndForwardedHostCannotChooseFeatureDomain() throws Exception {
        jdbc.update("INSERT INTO identity_verification(user_id,status,front_image,front_mime,updated_at) VALUES(7,'DRAFT','cipher','image/jpeg',?)", java.time.LocalDateTime.now().minusHours(25));
        jdbc.update("INSERT INTO identity_verification(user_id,status,front_image,front_mime,submitted_at,updated_at) VALUES(8,'PENDING','cipher','image/jpeg',?,?)", java.time.LocalDateTime.now().minusDays(31), java.time.LocalDateTime.now().minusDays(31));
        cleanup.clearAbandonedDrafts();
        assertThat(jdbc.queryForObject("SELECT front_image FROM identity_verification WHERE user_id=7",String.class)).isNull();
        var expired=jdbc.queryForMap("SELECT status,front_image,review_note FROM identity_verification WHERE user_id=8");
        assertThat(expired.get("status")).isEqualTo("REJECTED");
        assertThat(expired.get("front_image")).isNull();
        mvc.perform(get("/user/identity/status").header("X-Forwarded-Host","attacker.example").with(role("USER",7)))
                .andExpect(jsonPath("$.code").value(200));
        verify(platform).requireFeature("identity_verification", "localhost");
        mvc.perform(get("/admin/identity/applications").with(role("CS",2)))
                .andExpect(jsonPath("$.code").value(403));
    }

    private void uploadBoth(long userId) throws Exception {
        mvc.perform(multipart("/user/identity/image").file(image("front.png")).param("side", "front").with(role("USER", userId)))
                .andExpect(jsonPath("$.code").value(200));
        mvc.perform(multipart("/user/identity/image").file(image("back.png")).param("side", "back").with(role("USER", userId)))
                .andExpect(jsonPath("$.code").value(200));
    }

    private MockMultipartFile image(String name) throws Exception {
        BufferedImage image = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
        try (var output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return new MockMultipartFile("file", name, "image/png", output.toByteArray());
        }
    }
}
