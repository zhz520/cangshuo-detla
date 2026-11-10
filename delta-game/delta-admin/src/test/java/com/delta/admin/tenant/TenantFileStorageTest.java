package com.delta.admin.tenant;

import com.delta.common.exception.BusinessException;
import com.delta.common.tenant.TenantContext;
import com.delta.common.tenant.TenantProperties;
import com.delta.common.file.service.impl.LocalFileServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantFileStorageTest {
    @Test
    void sharedUploadsAreWrittenUnderTheTenantDirectoryAndUrl() throws Exception {
        Path root = Files.createTempDirectory("delta-tenant-upload-");
        LocalFileServiceImpl files = new LocalFileServiceImpl();
        ReflectionTestUtils.setField(files, "uploadPath", root.toString());
        ReflectionTestUtils.setField(files, "urlPrefix", "/file/");
        TenantProperties properties = new TenantProperties();
        properties.setEnabled(true);
        ReflectionTestUtils.setField(files, "tenantProperties", properties);
        byte[] png = png();

        String url = TenantContext.callAs("tenant-a", () -> files.upload(
                new MockMultipartFile("file", "avatar.png", "image/png", png)));

        String date = LocalDate.now().toString().replace('-', '/');
        assertThat(url).matches("/file/tenant-a/" + date + "/[a-f0-9]{32}\\.(png|jpg)");
        Path stored = root.resolve("tenant-a").resolve(date).resolve(url.substring(url.lastIndexOf('/') + 1));
        assertThat(stored).exists();
        assertThat(root.resolve("tenant-b")).doesNotExist();
    }

    @Test
    void sharedUploadFailsClosedWithoutTrustedTenantContext() throws Exception {
        LocalFileServiceImpl files = new LocalFileServiceImpl();
        ReflectionTestUtils.setField(files, "uploadPath", Files.createTempDirectory("delta-tenant-upload-").toString());
        TenantProperties properties = new TenantProperties();
        properties.setEnabled(true);
        ReflectionTestUtils.setField(files, "tenantProperties", properties);

        assertThatThrownBy(() -> files.upload(new MockMultipartFile("file", "avatar.png", "image/png", png())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("租户上下文");
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
