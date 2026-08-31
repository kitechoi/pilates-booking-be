package com.pilaslot.pass.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PassProductTest {

    @Test
    void createsReusableCatalogProductWithConfiguredDefaults() {
        PassProduct product = new PassProduct(
                "PILATES_30_3M",
                "필라테스 30회 3개월",
                600000,
                30,
                90
        );

        assertThat(product.getCode()).isEqualTo("PILATES_30_3M");
        assertThat(product.getName()).isEqualTo("필라테스 30회 3개월");
        assertThat(product.getDefaultPrice()).isEqualTo(600000);
        assertThat(product.getDefaultCount()).isEqualTo(30);
        assertThat(product.getDefaultValidityDays()).isEqualTo(90);
        assertThat(product.getStatus()).isEqualTo(PassProductStatus.ACTIVE);
    }

    @Test
    void rejectsBlankCodeOrName() {
        assertThatThrownBy(() -> new PassProduct(" ", "수강권", 100000, 10, 30))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PassProduct("PASS", null, 100000, 10, 30))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativePrice() {
        assertThatThrownBy(() -> new PassProduct("PASS", "수강권", -1, 10, 30))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveCountOrValidityDays() {
        assertThatThrownBy(() -> new PassProduct("PASS", "수강권", 100000, 0, 30))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PassProduct("PASS", "수강권", 100000, 10, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
