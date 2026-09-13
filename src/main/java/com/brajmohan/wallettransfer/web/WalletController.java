package com.brajmohan.wallettransfer.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.brajmohan.wallettransfer.dto.CreateWalletRequest;
import com.brajmohan.wallettransfer.dto.DepositRequest;
import com.brajmohan.wallettransfer.dto.WalletResponse;
import com.brajmohan.wallettransfer.service.WalletService;

import jakarta.validation.Valid;

@RestController
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping("/wallets")
    public ResponseEntity<WalletResponse> createWallet(@Valid @RequestBody CreateWalletRequest req) {
        WalletResponse wallet = walletService.getOrCreate(req.userId());
        return ResponseEntity.status(HttpStatus.OK).body(wallet);
    }

    @GetMapping("/wallets/{id}")
    public WalletResponse getWallet(@PathVariable("id") long id) {
        return walletService.getById(id);
    }

    @PostMapping("/wallets/{userId}/deposit")
    public WalletResponse deposit(@PathVariable String userId, @Valid @RequestBody DepositRequest req) {
        return walletService.deposit(userId, req.amountPaise());
    }
}
