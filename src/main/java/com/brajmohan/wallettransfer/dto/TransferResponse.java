package com.brajmohan.wallettransfer.dto;

public record TransferResponse(
        long transferId,
        String status,
        String fromUserId,
        String toUserId,
        long amountPaise,
        boolean idempotentReplay) {
}
