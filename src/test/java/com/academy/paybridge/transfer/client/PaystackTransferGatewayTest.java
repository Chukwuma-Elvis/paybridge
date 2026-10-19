package com.academy.paybridge.transfer.client;

import com.academy.paybridge.shared.exception.ErrorCode;
import com.academy.paybridge.shared.exception.PayBridgeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The real Paystack client, tested against a pretend Paystack (no network, no key). The pretend answers follow Paystack's documented
 * shapes; whether the real service agrees is what the optional live smoke test is for.
 */
class PaystackTransferGatewayTest {

    private static final String KEY = "sk_test_not_a_real_key_0123456789";
    private static final String BASE = "https://api.paystack.co";

    private MockRestServiceServer server;
    private PaystackTransferGateway gateway;

    @BeforeEach
    void aGatewayTalkingToAPretendPaystack() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE).defaultHeader("Authorization", "Bearer " + KEY);
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new PaystackTransferGateway(properties(KEY), builder.build());
    }

    private static PaystackProperties properties(String key) {
        return new PaystackProperties(key, BASE, Duration.ofSeconds(3), Duration.ofSeconds(10));
    }

    private static TransferInstruction instruction(String reference) {
        return new TransferInstruction(reference, 250_000, "NGN", "RCP_abc123", "Rent");
    }

    private static void assertGatewayUnavailable(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(GatewayUnavailableException.class);
    }

    // ---- set-up rules

    @Test
    void itRefusesToStartWithoutAKey() {
        assertThatThrownBy(() -> new PaystackTransferGateway(properties(""), RestClient.create()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PAYSTACK_SECRET_KEY");
        assertThatThrownBy(() -> new PaystackTransferGateway(properties("   "), RestClient.create()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void referencesAreSentToPaystackInLowercase() {
        assertThat(PaystackTransferGateway.paystackReference("PO-RM-ABCDEF0123456789")).isEqualTo("po-rm-abcdef0123456789");
        assertThat(PaystackTransferGateway.paystackReference("pb-4f1c")).isEqualTo("pb-4f1c");
    }

    // ---- name enquiry

    @Test
    void aKnownAccountIsResolvedToItsName() {
        server.expect(requestTo(BASE + "/bank/resolve?account_number=0123456789&bank_code=058"))
                .andExpect(method(HttpMethod.GET)).andExpect(header("Authorization", "Bearer " + KEY))
                .andRespond(withSuccess("{\"status\":true,\"message\":\"ok\",\"data\":{\"account_number\":\"0123456789\",\"account_name\":\"ADA OBI\"}}",
                        MediaType.APPLICATION_JSON));

        ResolvedAccount account = gateway.resolveAccount("058", "0123456789");

        assertThat(account.accountName()).isEqualTo("ADA OBI");
        server.verify();
    }

    @Test
    void anAccountPaystackCannotResolveIsNotFound() {
        server.expect(requestTo(BASE + "/bank/resolve?account_number=0123456789&bank_code=058"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":false,\"message\":\"Could not resolve account name\"}"));

        assertThatThrownBy(() -> gateway.resolveAccount("058", "0123456789")).isInstanceOf(PayBridgeException.class)
                .extracting(e -> ((PayBridgeException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void aRejectedKeyOrAnOutageDuringNameEnquiryIsUnavailableNotNotFound() {
        server.expect(requestTo(BASE + "/bank/resolve?account_number=1&bank_code=058"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":false,\"message\":\"Invalid key\"}"));
        assertGatewayUnavailable(() -> gateway.resolveAccount("058", "1"));

        server.reset();
        server.expect(requestTo(BASE + "/bank/resolve?account_number=2&bank_code=058")).andRespond(withServerError());
        assertGatewayUnavailable(() -> gateway.resolveAccount("058", "2"));
    }

    // ---- recipients

    @Test
    void aRecipientIsCreatedAsANubanPayee() {
        server.expect(requestTo(BASE + "/transferrecipient")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.type").value("nuban")).andExpect(jsonPath("$.account_number").value("0123456789"))
                .andExpect(jsonPath("$.bank_code").value("058")).andExpect(jsonPath("$.currency").value("NGN"))
                .andRespond(withSuccess("{\"status\":true,\"data\":{\"recipient_code\":\"RCP_xyz\"}}", MediaType.APPLICATION_JSON));

        assertThat(gateway.createRecipient("058", "0123456789", "ADA OBI")).isEqualTo("RCP_xyz");
        server.verify();
    }

    @Test
    void aFailedRecipientCallIsUnavailableSoItIsRetriedNeverTreatedAsAPayment() {
        server.expect(requestTo(BASE + "/transferrecipient")).andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
                .body("{\"status\":false,\"message\":\"Invalid bank code\"}"));

        assertGatewayUnavailable(() -> gateway.createRecipient("999", "0123456789", "ADA OBI"));
    }

    // ---- sending

    @Test
    void aTransferIsSentInKoboWithALowercaseReferenceAndAcceptedAsPending() {
        server.expect(requestTo(BASE + "/transfer")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + KEY))
                .andExpect(jsonPath("$.source").value("balance")).andExpect(jsonPath("$.amount").value(250_000))
                .andExpect(jsonPath("$.recipient").value("RCP_abc123")).andExpect(jsonPath("$.reference").value("po-rm-abcdef0123456789"))
                .andRespond(withSuccess("{\"status\":true,\"data\":{\"status\":\"pending\",\"transfer_code\":\"TRF_1\",\"reference\":\"po-rm-abcdef0123456789\"}}",
                        MediaType.APPLICATION_JSON));

        GatewayTransferResult result = gateway.initiateTransfer(instruction("PO-RM-ABCDEF0123456789"));

        assertThat(result.outcome()).isEqualTo(GatewayOutcome.PENDING);
        assertThat(result.providerReference()).isEqualTo("TRF_1");
        server.verify();
    }

    @Test
    void anImmediateSuccessIsReportedAsSuccess() {
        server.expect(requestTo(BASE + "/transfer")).andRespond(withSuccess(
                "{\"status\":true,\"data\":{\"status\":\"success\",\"transfer_code\":\"TRF_2\"}}", MediaType.APPLICATION_JSON));

        assertThat(gateway.initiateTransfer(instruction("pb-aaaaaaaaaaaaaaaa")).outcome()).isEqualTo(GatewayOutcome.SUCCESS);
    }

    @Test
    void aTransferWaitingForAnOtpStaysPendingRatherThanBeingFailed() {
        server.expect(requestTo(BASE + "/transfer")).andRespond(withSuccess(
                "{\"status\":true,\"data\":{\"status\":\"otp\",\"transfer_code\":\"TRF_3\"}}", MediaType.APPLICATION_JSON));

        GatewayTransferResult result = gateway.initiateTransfer(instruction("pb-aaaaaaaaaaaaaaaa"));

        assertThat(result.outcome()).isEqualTo(GatewayOutcome.PENDING);
        assertThat(result.message()).contains("OTP");
    }

    @Test
    void aClearRefusalIsAFailureBecauseNothingWasSent() {
        server.expect(requestTo(BASE + "/transfer")).andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
                .body("{\"status\":false,\"message\":\"Your balance is not enough to fulfil this request\"}"));
        server.expect(requestTo(BASE + "/transfer/verify/pb-aaaaaaaaaaaaaaaa")).andRespond(withResourceNotFound());   // never created

        GatewayTransferResult result = gateway.initiateTransfer(instruction("pb-aaaaaaaaaaaaaaaa"));

        assertThat(result.outcome()).isEqualTo(GatewayOutcome.FAILED);
        assertThat(result.message()).contains("balance is not enough");
    }

    @Test
    void aReferenceUsedBeforeIsLookedUpNotSentAgain() {
        server.expect(requestTo(BASE + "/transfer")).andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
                .body("{\"status\":false,\"message\":\"some wording we do not depend on\"}"));
        server.expect(requestTo(BASE + "/transfer/verify/pb-aaaaaaaaaaaaaaaa")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"status\":true,\"data\":{\"status\":\"success\",\"transfer_code\":\"TRF_9\"}}", MediaType.APPLICATION_JSON));

        GatewayTransferResult result = gateway.initiateTransfer(instruction("pb-aaaaaaaaaaaaaaaa"));

        assertThat(result.outcome()).as("it was already sent and succeeded").isEqualTo(GatewayOutcome.SUCCESS);
        server.verify();
    }

    @Test
    void aRefusalIsOnlyBelievedIfThenCheckingTheReferenceIsAlsoClear() {
        server.expect(requestTo(BASE + "/transfer")).andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
                .body("{\"status\":false,\"message\":\"Sorry, we can't make the transfer to this recipient at the moment\"}"));
        server.expect(requestTo(BASE + "/transfer/verify/pb-aaaaaaaaaaaaaaaa")).andRespond(withServerError());

        assertGatewayUnavailable(() -> gateway.initiateTransfer(instruction("pb-aaaaaaaaaaaaaaaa")));
    }

    @Test
    void rejectedCredentialsRateLimitsOutagesAndTimeoutsAreUnknownNeverFailed() {
        server.expect(requestTo(BASE + "/transfer")).andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON).body("{\"status\":false,\"message\":\"Invalid key\"}"));
        assertGatewayUnavailable(() -> gateway.initiateTransfer(instruction("pb-aaaaaaaaaaaaaaaa")));

        server.reset();
        server.expect(requestTo(BASE + "/transfer")).andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));
        assertGatewayUnavailable(() -> gateway.initiateTransfer(instruction("pb-aaaaaaaaaaaaaaaa")));

        server.reset();
        server.expect(requestTo(BASE + "/transfer")).andRespond(withServerError());
        assertGatewayUnavailable(() -> gateway.initiateTransfer(instruction("pb-aaaaaaaaaaaaaaaa")));

        server.reset();
        server.expect(requestTo(BASE + "/transfer")).andRespond(withException(new IOException("read timed out")));
        assertGatewayUnavailable(() -> gateway.initiateTransfer(instruction("pb-aaaaaaaaaaaaaaaa")));
    }

    @Test
    void anAnswerThatIsNotJsonIsUnknownToo() {
        server.expect(requestTo(BASE + "/transfer")).andRespond(withSuccess("<html>gateway error</html>", MediaType.TEXT_HTML));

        assertGatewayUnavailable(() -> gateway.initiateTransfer(instruction("pb-aaaaaaaaaaaaaaaa")));
    }

    @Test
    void onlyNairaTransfersAreAccepted() {
        TransferInstruction dollars = new TransferInstruction("pb-aaaaaaaaaaaaaaaa", 100, "USD", "RCP_x", null);

        assertThatThrownBy(() -> gateway.initiateTransfer(dollars)).isInstanceOf(PayBridgeException.class)
                .extracting(e -> ((PayBridgeException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    // ---- asking what happened

    @Test
    void everyPaystackStatusMapsToTheRightOutcome() {
        String[][] cases = {{"success", "SUCCESS"}, {"failed", "FAILED"}, {"reversed", "REVERSED"}, {"pending", "PENDING"},
                {"processing", "PENDING"}, {"queued", "PENDING"}, {"received", "PENDING"}, {"otp", "PENDING"},
                {"abandoned", "FAILED"}, {"blocked", "FAILED"}, {"rejected", "FAILED"}};
        for (String[] c : cases) {
            server.reset();
            server.expect(requestTo(BASE + "/transfer/verify/pb-bbbbbbbbbbbbbbbb")).andRespond(withSuccess(
                    "{\"status\":true,\"data\":{\"status\":\"" + c[0] + "\",\"transfer_code\":\"TRF_5\"}}", MediaType.APPLICATION_JSON));

            assertThat(gateway.fetchTransferStatus("pb-bbbbbbbbbbbbbbbb").outcome()).as(c[0]).isEqualTo(GatewayOutcome.valueOf(c[1]));
        }
    }

    @Test
    void aTransferPaystackHasNeverHeardOfIsNotFound() {
        server.expect(requestTo(BASE + "/transfer/verify/pb-cccccccccccccccc")).andRespond(withResourceNotFound());

        assertThat(gateway.fetchTransferStatus("pb-cccccccccccccccc").outcome()).isEqualTo(GatewayOutcome.NOT_FOUND);
    }

    @Test
    void anOutageWhileCheckingIsUnknownNotNotFound() {
        server.expect(requestTo(BASE + "/transfer/verify/pb-dddddddddddddddd")).andRespond(withServerError());

        assertGatewayUnavailable(() -> gateway.fetchTransferStatus("pb-dddddddddddddddd"));
    }

    @Test
    void theVerifyAddressUsesTheLowercaseReference() {
        server.expect(requestTo(BASE + "/transfer/verify/po-rm-abcdef0123456789")).andRespond(withSuccess(
                "{\"status\":true,\"data\":{\"status\":\"pending\"}}", MediaType.APPLICATION_JSON));

        assertThat(gateway.fetchTransferStatus("PO-RM-ABCDEF0123456789").outcome()).isEqualTo(GatewayOutcome.PENDING);
        server.verify();
    }

    @Test
    void theSecretKeyNeverAppearsInAnErrorMessage() {
        server.expect(requestTo(BASE + "/transfer")).andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON).body("{\"status\":false,\"message\":\"Invalid key " + KEY + "\"}"));

        assertThatThrownBy(() -> gateway.initiateTransfer(instruction("pb-aaaaaaaaaaaaaaaa")))
                .isInstanceOf(GatewayUnavailableException.class).hasMessageNotContaining(KEY);
    }
}
