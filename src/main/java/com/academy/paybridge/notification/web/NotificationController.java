package com.academy.paybridge.notification.web;

import com.academy.paybridge.notification.api.NotificationView;
import com.academy.paybridge.notification.service.NotificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private static final int MAX_LIMIT = 200;

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** The alerts we have sent to a customer, newest first. */
    @GetMapping
    public List<NotificationView> list(@RequestParam UUID customerId, @RequestParam(defaultValue = "50") int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_LIMIT));
        return notificationService.listForCustomer(customerId, bounded).stream()
                .map(n -> new NotificationView(n.getId(), n.getCustomerId(), n.getChannel().name(), n.getRecipient(),
                        n.getEventType(), n.getReference(), n.getMessage(), n.getCreatedAt()))
                .toList();
    }
}
