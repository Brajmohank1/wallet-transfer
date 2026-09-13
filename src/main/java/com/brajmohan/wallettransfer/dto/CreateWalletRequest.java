package com.brajmohan.wallettransfer.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateWalletRequest(@NotBlank String userId) {
}
