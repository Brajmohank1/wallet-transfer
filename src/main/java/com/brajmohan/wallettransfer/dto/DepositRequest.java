package com.brajmohan.wallettransfer.dto;

import jakarta.validation.constraints.Positive;

public record DepositRequest(@Positive long amountPaise) {
}
