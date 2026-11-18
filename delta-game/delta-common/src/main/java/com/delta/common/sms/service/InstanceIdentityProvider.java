package com.delta.common.sms.service;

/** Supplies the platform state instance UUID without coupling common services to delta-admin. */
public interface InstanceIdentityProvider {
    String currentInstanceId();
    String authorityOrigin();
}
