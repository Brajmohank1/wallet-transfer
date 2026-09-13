package com.brajmohan.wallettransfer.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.brajmohan.wallettransfer.dto.TransferRequest;
import com.brajmohan.wallettransfer.dto.TransferResponse;
import com.brajmohan.wallettransfer.service.TransferService;

import jakarta.validation.Valid;

@RestController
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping("/transfers")
    public ResponseEntity<TransferResponse> createTransfer(@Valid @RequestBody TransferRequest req) {
        TransferResponse result = transferService.transfer(req);
        HttpStatus status = "DECLINED_INSUFFICIENT_FUNDS".equals(result.status())
                ? HttpStatus.UNPROCESSABLE_ENTITY
                : HttpStatus.OK;
        return ResponseEntity.status(status).body(result);
    }

    // 200 regardless of whether the outcome was COMPLETED or
    // DECLINED_INSUFFICIENT_FUNDS; 404 only if the id doesn't exist.
    @GetMapping("/transfers/{id}")
    public TransferResponse getTransfer(@PathVariable long id) {
        return transferService.getById(id);
    }
}
