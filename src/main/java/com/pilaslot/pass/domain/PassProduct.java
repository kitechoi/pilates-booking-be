package com.pilaslot.pass.domain;

import com.pilaslot.global.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "pass_product")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PassProduct extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "default_price", nullable = false)
    private int defaultPrice;

    @Column(name = "default_count", nullable = false)
    private int defaultCount;

    @Column(name = "default_validity_days", nullable = false)
    private int defaultValidityDays;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PassProductStatus status;

    public PassProduct(
            String code,
            String name,
            int defaultPrice,
            int defaultCount,
            int defaultValidityDays
    ) {
        if (code == null || code.isBlank() || name == null || name.isBlank()) {
            throw new IllegalArgumentException("수강권 상품 코드와 이름은 필수입니다.");
        }
        if (defaultPrice < 0 || defaultCount <= 0 || defaultValidityDays <= 0) {
            throw new IllegalArgumentException("수강권 상품 기본값이 올바르지 않습니다.");
        }
        this.code = code;
        this.name = name;
        this.defaultPrice = defaultPrice;
        this.defaultCount = defaultCount;
        this.defaultValidityDays = defaultValidityDays;
        this.status = PassProductStatus.ACTIVE;
    }
}

