package com.delta.admin;

import com.delta.common.membership.MembershipTierRules;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MembershipTierRulesTest {
    @Test
    void validRulesAreNormalizedInAscendingThresholdOrder() {
        var tiers = MembershipTierRules.parseStrict("["
                + "{\"code\":\"GOLD\",\"name\":\"黄金\",\"minSpend\":2000,\"discountRate\":0.95},"
                + "{\"code\":\"NORMAL\",\"name\":\"普通\",\"minSpend\":0,\"discountRate\":1}]");
        assertThat(tiers).extracting(MembershipTierRules.Tier::code).containsExactly("NORMAL", "GOLD");
    }

    @Test
    void missingZeroTierOrDuplicateThresholdIsRejected() {
        assertThatThrownBy(() -> MembershipTierRules.parseStrict(
                "[{\"code\":\"SILVER\",\"name\":\"白银\",\"minSpend\":100,\"discountRate\":0.98}]"))
                .hasMessageContaining("必须为 0");
        assertThatThrownBy(() -> MembershipTierRules.parseStrict(
                "[{\"code\":\"A\",\"name\":\"甲\",\"minSpend\":0,\"discountRate\":1},"
                        + "{\"code\":\"B\",\"name\":\"乙\",\"minSpend\":0,\"discountRate\":0.9}]"))
                .hasMessageContaining("逐级递增");
    }

    @Test
    void invalidDiscountOrDuplicateCodeIsRejected() {
        assertThatThrownBy(() -> MembershipTierRules.parseStrict(
                "[{\"code\":\"A\",\"name\":\"甲\",\"minSpend\":0,\"discountRate\":0}]"))
                .hasMessageContaining("折扣率");
        assertThatThrownBy(() -> MembershipTierRules.parseStrict(
                "[{\"code\":\"A\",\"name\":\"甲\",\"minSpend\":0,\"discountRate\":1},"
                        + "{\"code\":\"A\",\"name\":\"乙\",\"minSpend\":100,\"discountRate\":0.9}]"))
                .hasMessageContaining("不能重复");
    }

    @Test
    void higherTierCannotWorsenTheDiscount() {
        assertThatThrownBy(() -> MembershipTierRules.parseStrict(
                "[{\"code\":\"NORMAL\",\"name\":\"普通\",\"minSpend\":0,\"discountRate\":0.95},"
                        + "{\"code\":\"GOLD\",\"name\":\"黄金\",\"minSpend\":1000,\"discountRate\":1}]"))
                .isInstanceOfSatisfying(com.delta.common.exception.BusinessException.class,
                        error -> assertThat(error.getMessage()).contains("折扣率不能高于"));
    }
}
