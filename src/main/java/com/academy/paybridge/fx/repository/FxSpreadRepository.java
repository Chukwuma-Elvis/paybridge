package com.academy.paybridge.fx.repository;

import com.academy.paybridge.fx.api.Corridor;
import com.academy.paybridge.fx.domain.FxSpread;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FxSpreadRepository extends JpaRepository<FxSpread, UUID> {

    Optional<FxSpread> findFirstByCorridorOrderByCreatedAtDescIdDesc(Corridor corridor);
}
