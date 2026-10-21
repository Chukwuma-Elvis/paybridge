#!/bin/bash
# ==============================================================================
# PayBridge live demo. Walks through the whole story against a running deployment.
#
#   Required environment variables (set them in YOUR terminal; never put them in a file):
#     URL      the service address, for example  https://paybridge-xxxx.onrender.com
#     ADMIN    your PAYBRIDGE_ADMIN_API_KEY
#     PSECRET  your FAKEPARTNER_SECRET (the same value as in Render)
#
#   Run it:      bash demo/demo.sh
#   No pauses:   DEMO_FAST=1 bash demo/demo.sh
#
# It uses the pretend bank, partner and rate source, so no real money moves.
# Needs only: bash, curl, openssl (all come with Git Bash).
# ==============================================================================
set -u
: "${URL:?Set URL first, e.g. export URL=https://paybridge-xxxx.onrender.com}"
: "${ADMIN:?Set ADMIN to your admin API key}"
: "${PSECRET:?Set PSECRET to your FAKEPARTNER_SECRET}"
URL="${URL%/}"
RUN=$(date +%s)
FAST="${DEMO_FAST:-0}"

B=$'\033[1m'; G=$'\033[32m'; Y=$'\033[33m'; R=$'\033[31m'; C=$'\033[36m'; N=$'\033[0m'
say()   { printf "\n${B}${C}== %s ==${N}\n" "$1"; }
note()  { printf "${Y}   %s${N}\n" "$1"; }
ok()    { printf "${G}   OK  %s${N}\n" "$1"; }
bad()   { printf "${R}   !!  %s${N}\n" "$1"; FAILED=1; }
pause() { [ "$FAST" = "1" ] || read -r -p "   (press Enter to continue) " _; }
FAILED=0

# Tiny JSON readers (flat fields only): first occurrence of "key":"text" or "key":number
str() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | sed 's/^[^:]*:"\(.*\)"$/\1/'; }
num() { grep -o "\"$1\":-\?[0-9]*" | head -1 | sed 's/^[^:]*://'; }
code() { curl -s -o /dev/null -w "%{http_code}" "$@"; }
jpost() { curl -s -X POST -H "Content-Type: application/json" "$@"; }
expect() { # expect "<label>" "<actual>" "<wanted>"
  if [ "$2" = "$3" ]; then ok "$1: $2"; else bad "$1: got '$2', expected '$3'"; fi
}
wait_status() { # wait_status <remittance-id> <wanted> <seconds>
  local s="" i
  for i in $(seq 1 $(( $3 / 3 ))); do
    s=$(curl -s -H "X-API-Key: $CK" "$URL/api/remittances/$1" | str status)
    [ "$s" = "$2" ] && { echo "$s"; return 0; }
    sleep 3
  done
  echo "$s"; return 1
}
sign() { printf '%s' "$1" | openssl dgst -sha512 -hmac "$PSECRET" -hex | sed 's/^.*= //'; }
partner_funding() { # partner_funding <reference> <amountMinor> <eventId>  -> prints the pipeline answer
  local body="{\"partner\":\"fakepartner\",\"eventId\":\"$3\",\"type\":\"funding.confirmed\",\"reference\":\"$1\",\"partnerReference\":\"p-$3\",\"amountMinor\":$2,\"currency\":\"USD\"}"
  curl -s -X POST -H "x-partner-signature: $(sign "$body")" -H "Content-Type: application/json" -d "$body" "$URL/api/webhooks/partner" | str result
}
history() { curl -s -H "X-API-Key: $ADMIN" "$URL/api/admin/remittances/$1/history" | sed 's/^\[//; s/\]$//; s/","/\n/g; s/"//g' | sed 's/^/        /'; }
new_remittance() { # new_remittance <sellMinor> <senderName> <recipientJson> <tag>  -> prints the JSON reply
  local quote qid
  quote=$(jpost -H "X-API-Key: $CK" -H "Idempotency-Key: demo-q-$4-$RUN" -d "{\"corridor\":\"USD_NGN\",\"sellMinor\":$1}" "$URL/api/fx/quotes")
  qid=$(echo "$quote" | str id)
  jpost -H "X-API-Key: $CK" -H "Idempotency-Key: demo-r-$4-$RUN" \
    -d "{\"partnerCode\":\"fakepartner\",\"partnerReference\":\"demo-$4-$RUN\",\"quoteId\":\"$qid\",\"sender\":{\"reference\":\"demo-sender-$4-$RUN\",\"fullName\":\"$2\",\"country\":\"NG\"},\"recipient\":$3}" \
    "$URL/api/remittances"
}

# ------------------------------------------------------------------------------
say "0. Is it alive, and is it locked down?"
note "On the free tier the first request can take a few minutes if the service was asleep."
H=""; for i in $(seq 1 100); do H=$(curl -s --max-time 20 "$URL/actuator/health"); echo "$H" | grep -q '"UP"' && break; printf "   waiting for the service to wake... (%s)\n" "$i"; sleep 6; done
echo "$H" | grep -q '"UP"' && ok "health: UP" || { bad "service is not healthy; stop here"; exit 1; }
expect "a request with NO key is refused" "$(code "$URL/api/customers")" "401"
expect "a made-up key is refused" "$(code -H 'X-API-Key: not-a-real-key-0123456789' "$URL/api/customers")" "401"
expect "the admin key is accepted" "$(code -H "X-API-Key: $ADMIN" "$URL/actuator/metrics")" "200"
expect "the 'print test money' endpoint is blocked in production" "$(code -X POST -H "X-API-Key: $ADMIN" -H 'Content-Type: application/json' -d '{"amountMinor":100}' "$URL/api/dev/accounts/00000000-0000-0000-0000-000000000000/fund")" "404"
pause

say "1. Admin creates a client key (a separate key for everyday use)"
CK=$(jpost -H "X-API-Key: $ADMIN" -d "{\"name\":\"demo-client-$RUN\",\"role\":\"CLIENT\"}" "$URL/api/admin/api-keys" | str apiKey)
[ -n "$CK" ] && ok "client key created (shown once; length ${#CK})" || { bad "could not create a client key"; exit 1; }
expect "a CLIENT key cannot use the admin area" "$(code -H "X-API-Key: $CK" "$URL/api/admin/compliance/cases")" "403"
pause

say "2. The exchange rate (an admin sets it; there is no live feed here)"
jpost -H "X-API-Key: $ADMIN" -d '{"corridor":"USD_NGN","midRate":1500,"note":"demo rate"}' "$URL/api/admin/fx/rates" >/dev/null
RATE=$(curl -s -H "X-API-Key: $CK" "$URL/api/fx/rates/USD_NGN")
note "market rate 1500, we keep a 1% margin"
expect "the customer rate" "$(echo "$RATE" | num customerRate)" "1485"
pause

say "3. A recipient in Nigeria opens an account"
CUST=$(jpost -H "X-API-Key: $CK" -d "{\"firstName\":\"Ada\",\"lastName\":\"Obi\",\"email\":\"ada.$RUN@example.com\",\"phone\":\"08012345678\"}" "$URL/api/customers" | str id)
ACCJ=$(jpost -H "X-API-Key: $CK" -d "{\"customerId\":\"$CUST\"}" "$URL/api/accounts")
ACC=$(echo "$ACCJ" | str id); ACCNO=$(echo "$ACCJ" | str accountNumber)
[ -n "$ACCNO" ] && ok "naira account $ACCNO opened" || bad "account not created"
pause

say "4. A sender abroad wants to send USD 1,000.00. We lock a price (a quote)"
Q=$(jpost -H "X-API-Key: $CK" -H "Idempotency-Key: demo-quote-main-$RUN" -d '{"corridor":"USD_NGN","sellMinor":100000}' "$URL/api/fx/quotes")
QID=$(echo "$Q" | str id)
note "USD 1,000.00 = 100000 cents. Fee 0.5% = 500 cents. The other 99500 cents x 1485 = kobo the recipient gets."
expect "fee in cents" "$(echo "$Q" | num feeMinor)" "500"
expect "recipient receives (kobo = NGN 1,477,575.00)" "$(echo "$Q" | num buyMinor)" "147757500"
note "the quote is valid for 5 minutes and can be used exactly once"
pause

say "5. The remittance is created (and screened)"
R=$(jpost -H "X-API-Key: $CK" -H "Idempotency-Key: demo-remit-main-$RUN" \
  -d "{\"partnerCode\":\"fakepartner\",\"partnerReference\":\"demo-main-$RUN\",\"quoteId\":\"$QID\",\"sender\":{\"reference\":\"demo-sender-main-$RUN\",\"fullName\":\"Chidi Okafor\",\"country\":\"NG\"},\"recipient\":{\"type\":\"PAYBRIDGE\",\"fullName\":\"Ada Obi\",\"country\":\"NG\",\"accountNumber\":\"$ACCNO\"}}" "$URL/api/remittances")
RID=$(echo "$R" | str id); REF=$(echo "$R" | str reference)
expect "status (screened clean, waiting for the partner's dollars)" "$(echo "$R" | str status)" "AWAITING_FUNDING"
note "reference: $REF   (the response shows only a masked account number: $(echo "$R" | str recipientAccountMasked))"
SAME=$(jpost -H "X-API-Key: $CK" -H "Idempotency-Key: demo-remit-main-$RUN" \
  -d "{\"partnerCode\":\"fakepartner\",\"partnerReference\":\"demo-main-$RUN\",\"quoteId\":\"$QID\",\"sender\":{\"reference\":\"demo-sender-main-$RUN\",\"fullName\":\"Chidi Okafor\",\"country\":\"NG\"},\"recipient\":{\"type\":\"PAYBRIDGE\",\"fullName\":\"Ada Obi\",\"country\":\"NG\",\"accountNumber\":\"$ACCNO\"}}" "$URL/api/remittances" | str id)
expect "sending the SAME request again returns the SAME remittance (no double payment)" "$SAME" "$RID"
pause

say "6. The partner abroad says 'we got the sender's dollars' (a signed message)"
BODY="{\"partner\":\"fakepartner\",\"eventId\":\"demo-evt-$RUN\",\"type\":\"funding.confirmed\",\"reference\":\"$REF\",\"partnerReference\":\"p1\",\"amountMinor\":100000,\"currency\":\"USD\"}"
expect "a FORGED message (bad signature) is refused" "$(code -X POST -H 'x-partner-signature: deadbeef' -H 'Content-Type: application/json' -d "$BODY" "$URL/api/webhooks/partner")" "401"
expect "the same message with the REAL signature is applied" "$(curl -s -X POST -H "x-partner-signature: $(sign "$BODY")" -H 'Content-Type: application/json' -d "$BODY" "$URL/api/webhooks/partner" | str result)" "APPLIED"
expect "the partner sending it AGAIN is recognised as a duplicate" "$(curl -s -X POST -H "x-partner-signature: $(sign "$BODY")" -H 'Content-Type: application/json' -d "$BODY" "$URL/api/webhooks/partner" | str result)" "DUPLICATE"
pause

say "7. PayBridge converts the dollars and pays the recipient (the background engine)"
note "waiting for the engine (it runs every few seconds; the free tier is slow)..."
S=$(wait_status "$RID" PAID 180); expect "status" "$S" "PAID"
BAL=$(curl -s -H "X-API-Key: $CK" "$URL/api/accounts/$ACC" | num balanceMinor)
expect "recipient balance in kobo (NGN 1,477,575.00)" "$BAL" "147757500"
note "every step the remittance went through (this history can never be edited):"
history "$RID"
pause

say "8. When something goes wrong: the payout fails and the sender is refunded in full"
note "this recipient is at another bank; the pretend bank refuses account numbers ending 1111"
R2=$(new_remittance 50000 "Chidi Okafor" '{"type":"BANK","fullName":"Test Recipient","country":"NG","bankCode":"058","accountNumber":"0000001111"}' refund)
RID2=$(echo "$R2" | str id); REF2=$(echo "$R2" | str reference)
expect "created" "$(echo "$R2" | str status)" "AWAITING_FUNDING"
partner_funding "$REF2" 50000 "demo-evt-refund-$RUN" >/dev/null
S=$(wait_status "$RID2" REFUNDED 240); expect "status after the bank refuses" "$S" "REFUNDED"
note "the dollars were converted, the payout was refused, and both steps were reversed:"
history "$RID2"
pause

say "9. Compliance: a name that resembles a watch-list entry is held for a person"
note "(the watch list here is invented; 'Alina Samplova' is one of its fake entries)"
R3=$(new_remittance 20000 "Alina Samplova" "{\"type\":\"PAYBRIDGE\",\"fullName\":\"Ada Obi\",\"country\":\"NG\",\"accountNumber\":\"$ACCNO\"}" hold)
RID3=$(echo "$R3" | str id); REF3=$(echo "$R3" | str reference)
expect "status (nothing moves until a person decides)" "$(echo "$R3" | str status)" "UNDER_REVIEW"
expect "the partner's money for it is HELD, not applied" "$(partner_funding "$REF3" 20000 "demo-evt-hold-$RUN")" "MANUAL_REVIEW"
note "a compliance officer reviews and approves, with a written reason:"
APPR=$(jpost -H "X-API-Key: $ADMIN" -d '{"decision":"APPROVE","reason":"Demo: different person, birth dates differ"}' "$URL/api/admin/remittances/$RID3/review")
expect "after approval" "$(echo "$APPR" | str status)" "AWAITING_FUNDING"
pause

say "10. End of day: settling with the partner"
CLOSE=$(curl -s -X POST -H "X-API-Key: $ADMIN" "$URL/api/admin/settlement/partners/fakepartner/close")
BID=$(echo "$CLOSE" | str id)
if [ -n "$BID" ]; then
  ok "a settlement batch was cut from the partner's account: $(echo "$CLOSE" | num lineCount) ledger lines, net $(echo "$CLOSE" | num movementsMinor) cents"
  note "negative means the partner is holding that much USD on our behalf"
  jpost -H "X-API-Key: $ADMIN" "$URL/api/admin/settlement/batches/$BID/instruct" >/dev/null
  PAY=$(jpost -H "X-API-Key: $ADMIN" "$URL/api/admin/settlement/batches/$BID/confirm")
  expect "batch status after the partner pays" "$(echo "$PAY" | str status)" "CONFIRMED"
  POS=$(curl -s -H "X-API-Key: $ADMIN" "$URL/api/admin/settlement/partners/fakepartner/position")
  note "partner account balance now: $(echo "$POS" | num accountBalanceMinor) cents (the payment brought it back toward zero)"
else
  note "nothing new to settle in this batch"
fi

say "Done"
if [ "$FAILED" = "0" ]; then printf "${G}${B}   Every check passed.${N}\n"; else printf "${R}${B}   Some checks FAILED; scroll up for the lines marked !!${N}\n"; fi
exit $FAILED
