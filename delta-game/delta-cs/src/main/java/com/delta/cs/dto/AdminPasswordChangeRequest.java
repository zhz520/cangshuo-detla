package com.delta.cs.dto;

public record AdminPasswordChangeRequest(String oldPassword, String newPassword, String confirmation) {}
