package com.academy.paybridge.transfer.client;

import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A pretend bank. Real sandboxes are optimistic and almost always say "success", so they never show you the
 * hard cases. This one lets you provoke every case on purpose: its behaviour is decided by the LAST FOUR DIGITS
 * of the beneficiary's account number.
 *
 * <pre>
 *   ...1111  the provider refuses the transfer (FAILED straight away)
 *   ...2222  timeout BEFORE the provider got the request (nothing was sent)
 *   ...3333  success straight away
 *   ...4444  accepted (pending), later reported FAILED
 *   ...5555  timeout AFTER the provider got the request (it IS being sent; a retry must not send it twice)
 *   ...6666  accepted and stays pending for ever
 *   ...7777  success straight away, later reported REVERSED
 *   ...9999  name enquiry cannot find the account
 *   anything else: accepted (pending), later reported SUCCESS
 * </pre>
 * Active unless {@code paybridge.gateway} is set to something else (for example {@code paystack}).
 */
@Component
@ConditionalOnProperty(name = "paybridge.gateway", havingValue = "fake", matchIfMissing = true)
public class FakeTransferGateway implements TransferGateway {

    private final Map<String, String> executed = new ConcurrentHashMap<>();   // reference -> recipient code
    private final Map<String, AtomicInteger> initiateCalls = new ConcurrentHashMap<>();

    @Override
    public ResolvedAccount resolveAccount(String bankCode, String accountNumber) {
        if (suffixOf(accountNumber).equals("9999")) {
            throw new PayBridgeException(ErrorCode.RESOURCE_NOT_FOUND,
                    "Could not resolve account " + accountNumber + " at bank " + bankCode);
        }
        return new ResolvedAccount(bankCode, accountNumber, "TEST ACCOUNT " + suffixOf(accountNumber));
    }

    @Override
    public String createRecipient(String bankCode, String accountNumber, String accountName) {
        return "RCP_" + accountNumber;
    }

    @Override
    public GatewayTransferResult initiateTransfer(TransferInstruction instruction) {
        String reference = instruction.reference();
        initiateCalls.computeIfAbsent(reference, key -> new AtomicInteger()).incrementAndGet();
        String suffix = suffixOfRecipient(instruction.recipientCode());

        if (suffix.equals("2222")) {
            throw new GatewayUnavailableException("Simulated timeout: the provider never received the request");
        }
        if (suffix.equals("5555") && !executed.containsKey(reference)) {
            executed.put(reference, instruction.recipientCode());
            throw new GatewayUnavailableException("Simulated timeout: the provider DID receive the request");
        }

        // idempotent per reference: asking again never "sends" it again
        executed.putIfAbsent(reference, instruction.recipientCode());
        String providerReference = "fake-" + reference;

        return switch (suffix) {
            case "1111" -> GatewayTransferResult.of(GatewayOutcome.FAILED, null, "Simulated refusal by the provider");
            case "3333", "7777" -> GatewayTransferResult.of(GatewayOutcome.SUCCESS, providerReference, "Transferred");
            default -> GatewayTransferResult.of(GatewayOutcome.PENDING, providerReference, "Accepted, processing");
        };
    }

    @Override
    public GatewayTransferResult fetchTransferStatus(String reference) {
        String recipientCode = executed.get(reference);
        if (recipientCode == null) {
            return GatewayTransferResult.of(GatewayOutcome.NOT_FOUND, null, "No such transfer");
        }
        String providerReference = "fake-" + reference;
        return switch (suffixOfRecipient(recipientCode)) {
            case "1111", "4444" -> GatewayTransferResult.of(GatewayOutcome.FAILED, providerReference, "Simulated failure");
            case "6666" -> GatewayTransferResult.of(GatewayOutcome.PENDING, providerReference, "Still processing");
            case "7777" -> GatewayTransferResult.of(GatewayOutcome.REVERSED, providerReference, "Simulated reversal");
            default -> GatewayTransferResult.of(GatewayOutcome.SUCCESS, providerReference, "Transferred");
        };
    }

    /** For tests: how many times the provider was asked to send this reference. */
    public int initiateCalls(String reference) {
        AtomicInteger calls = initiateCalls.get(reference);
        return calls == null ? 0 : calls.get();
    }

    /** For tests: did the provider actually take this transfer (as opposed to merely being asked)? */
    public boolean hasExecuted(String reference) {
        return executed.containsKey(reference);
    }

    private static String suffixOfRecipient(String recipientCode) {
        return suffixOf(recipientCode.substring(recipientCode.lastIndexOf('_') + 1));
    }

    private static String suffixOf(String accountNumber) {
        return accountNumber.length() < 4 ? accountNumber : accountNumber.substring(accountNumber.length() - 4);
    }
}
