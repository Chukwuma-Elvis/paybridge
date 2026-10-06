package com.academy.paybridge.shared.exception;

public class ResourceNotFoundException extends PayBridgeException {

    public ResourceNotFoundException(String resource, Object id) {
        super(ErrorCode.RESOURCE_NOT_FOUND, resource + " not found: " + id);
    }
}
