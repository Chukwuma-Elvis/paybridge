package com.academy.paybridge.notification.repository;

import com.academy.paybridge.notification.domain.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    boolean existsByCustomerIdAndEventTypeAndReferenceAndChannel(
            UUID customerId, String eventType, String reference, Notification.Channel channel);

    List<Notification> findByCustomerIdOrderByCreatedAtDesc(UUID customerId, Pageable pageable);
}
