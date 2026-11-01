package com.delta.admin.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import java.nio.file.*;
import java.time.*;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

/** Fixture was signed by the independent Node.js authority, with a disposable test key. */
class AuthorityInteropTest {
    @TempDir Path directory;
    @Test void importsAnAuthorityIssuedLicenseAndEnforcesIndividualModuleExpiration() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        PlatformLicense.Envelope envelope;
        try (var input = new ClassPathResource("platform/authority-interop.json").getInputStream()) { envelope = json.readValue(input, PlatformLicense.Envelope.class); }
        var claims = json.readValue(java.util.Base64.getUrlDecoder().decode(envelope.payload()), PlatformLicense.Claims.class);
        Path publicKey = directory.resolve("public.txt");
        try (var input = new ClassPathResource("platform/authority-interop.public.txt").getInputStream()) { Files.copy(input, publicKey); }
        var properties = new PlatformProperties(); properties.setCustomerId(claims.customerId()); properties.setPublicKeyPath(publicKey.toString()); properties.setStatePath(directory.resolve("state.json").toString());
        new PlatformStateStore(properties.getStatePath(), json).save(new PlatformStateStore.State(1, claims.instanceId(), null, Set.of()));
        var service = new PlatformService(properties, json, Clock.fixed(claims.issuedAt(), ZoneOffset.UTC));
        service.importLicense(envelope); service.setEnabled("advanced_income_reports", true); service.requireFeature("advanced_income_reports", "jaxapi.kkimg.top");
        assertThatThrownBy(() -> service.requireFeature("advanced_income_reports", "unlicensed.example"))
                .isInstanceOf(com.delta.common.exception.BusinessException.class);
        assertThat(service.features().get(1).expiresAt()).isEqualTo(claims.featureExpiresAt().get("advanced_income_reports"));
        var expired = new PlatformService(properties, json, Clock.fixed(claims.featureExpiresAt().get("advanced_income_reports").plusSeconds(1), ZoneOffset.UTC));
        assertThatThrownBy(() -> expired.requireFeature("advanced_income_reports")).isInstanceOf(com.delta.common.exception.BusinessException.class);
        assertThat(expired.features().get(0).enabled()).isTrue();
    }
}
