package com.brajmohan.wallettransfer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record TransferRequest(
        @NotBlank String idempotencyKey,
        @NotBlank String fromUserId,
        @NotBlank String toUserId,
        @Positive long amountPaise) {
}
