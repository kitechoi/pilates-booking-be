package com.pilaslot.pass.repository;

import com.pilaslot.pass.domain.PassProduct;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PassProductRepository extends JpaRepository<PassProduct, Long> {
}

