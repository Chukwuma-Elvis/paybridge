package com.academy.paybridge.security.repository;

import com.academy.paybridge.security.domain.ApiKey;
import com.academy.paybridge.security.domain.ApiKeyRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    Optional<ApiKey> findByKeyHash(String keyHash);

    boolean existsByKeyHash(String keyHash);

    boolean existsByRoleAndRevokedAtIsNull(ApiKeyRole role);

    List<ApiKey> findAllByOrderByCreatedAtDesc();
}
