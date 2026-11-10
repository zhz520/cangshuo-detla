package com.delta.admin.tenant;

import com.delta.common.exception.BusinessException;
import com.delta.common.tenant.TenantContext;
import com.delta.common.tenant.TenantRoutingDataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantRoutingDataSourceTest {
    private TenantRoutingDataSource router;

    @AfterEach
    void tearDown() {
        if (router != null) router.close();
        TenantContext.clear();
    }

    @Test
    void identicalQueriesAreRoutedToDifferentDatabases() throws Exception {
        router = new TenantRoutingDataSource(Map.of(
                "tenant-a", database("A"),
                "tenant-b", database("B")));

        assertThat(TenantContext.callAs("tenant-a", () -> marker(router))).isEqualTo("A");
        assertThat(TenantContext.callAs("tenant-b", () -> marker(router))).isEqualTo("B");
        assertThat(TenantContext.callAs("tenant-a", () -> marker(router))).isEqualTo("A");
    }

    @Test
    void missingContextAndUnknownTenantFailWithoutFallingBack() throws Exception {
        router = new TenantRoutingDataSource(Map.of("tenant-a", database("A")));

        assertThatThrownBy(() -> marker(router))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("租户上下文");
        assertThatThrownBy(() -> TenantContext.callAs("tenant-missing", () -> marker(router)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("数据源不可用");
    }

    private static DataSource database(String value) throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:tenant_" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE marker(marker_value VARCHAR(20))");
            statement.execute("INSERT INTO marker VALUES ('" + value + "')");
        }
        return source;
    }

    private static String marker(DataSource source) throws Exception {
        try (Connection connection = source.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT marker_value FROM marker")) {
            result.next();
            return result.getString(1);
        }
    }
}
