/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.manager;

import alfio.model.*;
import alfio.manager.system.ConfigurationManager;
import alfio.model.Event;
import alfio.model.system.Configuration;
import alfio.model.system.ConfigurationKeys;
import alfio.repository.TicketRepository;
import alfio.repository.TicketReservationRepository;
import alfio.util.MonetaryUtil;
import com.paypal.api.payments.*;
import com.paypal.api.payments.Transaction;
import com.paypal.base.rest.APIContext;
import com.paypal.base.rest.PayPalRESTException;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.codec.digest.HmacUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static alfio.util.MonetaryUtil.formatCents;

@Component
@Log4j2
public class PaypalManager {

    private final ConfigurationManager configurationManager;
    private final MessageSource messageSource;
    private final ConcurrentHashMap<String, String> cachedWebProfiles = new ConcurrentHashMap<>();
    private final TicketReservationRepository ticketReservationRepository;

    @Autowired
    public PaypalManager(ConfigurationManager configurationManager,
                         TicketReservationRepository ticketReservationRepository,
                         MessageSource messageSource,
                         TicketRepository ticketRepository) {
        this.configurationManager = configurationManager;
        this.messageSource = messageSource;
        this.ticketReservationRepository = ticketReservationRepository;
    }

    private APIContext getApiContext(Event event) {
        int orgId = event.getOrganizationId();
        boolean isLive = configurationManager.getBooleanConfigValue(Configuration.from(orgId, ConfigurationKeys.PAYPAL_LIVE_MODE), false);
        String clientId = configurationManager.getRequiredValue(Configuration.from(orgId, ConfigurationKeys.PAYPAL_CLIENT_ID));
        String clientSecret = configurationManager.getRequiredValue(Configuration.from(orgId, ConfigurationKeys.PAYPAL_CLIENT_SECRET));
        return new APIContext(clientId, clientSecret, isLive ? "live" : "sandbox");
    }

    private static String toWebProfileName(Event event, Locale locale) {
        return "ALFIO-" + event.getId() + "-" + event.getShortName() + "-" + locale.toString();
    }

    private Optional<WebProfile> getWebProfile(Event event, Locale locale) {
        try {
            String webProfileName = toWebProfileName(event, locale);
            return WebProfile.getList(getApiContext(event)).stream().filter(webProfile -> webProfileName.equals(webProfile.getName())).findFirst();
        } catch(PayPalRESTException e) {
            return Optional.empty();
        }
    }

    private Optional<String> getOrCreateWebProfile(Event event, Locale locale) {
        String webProfileName = toWebProfileName(event, locale);

        if(!cachedWebProfiles.containsKey(webProfileName)) {
            getWebProfile(event, locale).ifPresent(p -> {
                cachedWebProfiles.put(webProfileName, p.getId());
            });
        }

        if(!cachedWebProfiles.containsKey(webProfileName)) {
            WebProfile webProfile = new WebProfile(webProfileName);
            webProfile.setInputFields(new InputFields().setNoShipping(1).setAddressOverride(0).setAllowNote(false));
            // meh
            // webProfile.setPresentation(new Presentation().setLocaleCode(locale.toString()));
            //
            try {
                cachedWebProfiles.put(webProfileName, webProfile.create(getApiContext(event)).getId());
            } catch(PayPalRESTException e) {
                log.warn("error while creating web experience", e);
                //do absolutely nothing, worst case: the web experience will not be optimal
            }
            //
        }
        return Optional.ofNullable(cachedWebProfiles.get(webProfileName));
    }

    private List<Transaction> buildPaymentDetails(Event event, OrderSummary orderSummary, String reservationId, Locale locale) {
        Amount amount = new Amount()
            .setCurrency(event.getCurrency())
            .setTotal(orderSummary.getTotalPrice());


        Transaction transaction = new Transaction();
        String description = messageSource.getMessage("reservation-email-subject", new Object[] {configurationManager.getShortReservationID(event, reservationId), event.getDisplayName()}, locale);
        transaction.setDescription(description).setAmount(amount);


        List<Item> items = new ArrayList<>();
        items.add(new Item(description, "1", orderSummary.getTotalPrice(), event.getCurrency()));
        transaction.setItemList(new ItemList().setItems(items));

        List<Transaction> transactions = new ArrayList<>();
        transactions.add(transaction);
        return transactions;
    }

    public String createCheckoutRequest(Event event, String reservationId, OrderSummary orderSummary, CustomerName customerName,
                                        String email, String billingAddress, Locale locale, boolean postponeAssignment) throws Exception {

        Optional<String> experienceProfileId = getOrCreateWebProfile(event, locale);

        List<Transaction> transactions = buildPaymentDetails(event, orderSummary, reservationId, locale);
        String eventName = event.getShortName();

        Payer payer = new Payer();
        payer.setPaymentMethod("paypal");

        Payment payment = new Payment();
        payment.setIntent("sale");
        payment.setPayer(payer);
        payment.setTransactions(transactions);
        RedirectUrls redirectUrls = new RedirectUrls();

        String baseUrl = StringUtils.removeEnd(configurationManager.getRequiredValue(Configuration.from(event.getOrganizationId(), event.getId(), ConfigurationKeys.BASE_URL)), "/");
        String bookUrl = baseUrl+"/event/" + eventName + "/reservation/" + reservationId + "/book";

        UriComponentsBuilder bookUrlBuilder = UriComponentsBuilder.fromUriString(bookUrl)
            .queryParam("fullName", customerName.getFullName())
            .queryParam("firstName", customerName.getFirstName())
            .queryParam("lastName", customerName.getLastName())
            .queryParam("email", email)
            .queryParam("billingAddress", billingAddress)
            .queryParam("postponeAssignment", postponeAssignment)
            .queryParam("hmac", computeHMAC(customerName, email, billingAddress, event));
        String finalUrl = bookUrlBuilder.toUriString();

        redirectUrls.setCancelUrl(finalUrl + "&paypal-cancel=true");
        redirectUrls.setReturnUrl(finalUrl + "&paypal-success=true");
        payment.setRedirectUrls(redirectUrls);

        experienceProfileId.ifPresent(payment::setExperienceProfileId);

        Payment createdPayment = payment.create(getApiContext(event));


        TicketReservation reservation = ticketReservationRepository.findReservationById(reservationId);
        //add 15 minutes of validity in case the paypal flow is slow
        ticketReservationRepository.updateValidity(reservationId, DateUtils.addMinutes(reservation.getValidity(), 15));

        if(!"created".equals(createdPayment.getState())) {
            throw new Exception(createdPayment.getFailureReason());
        }

        //extract url for approval
        return createdPayment.getLinks().stream().filter(l -> "approval_url".equals(l.getRel())).findFirst().map(Links::getHref).orElseThrow(IllegalStateException::new);

    }

    private static String computeHMAC(CustomerName customerName, String email, String billingAddress, Event event) {
        return HmacUtils.hmacSha256Hex(event.getPrivateKey(), StringUtils.trimToEmpty(customerName.getFullName()) + StringUtils.trimToEmpty(email) + StringUtils.trimToEmpty(billingAddress));
    }

    public static boolean isValidHMAC(CustomerName customerName, String email, String billingAddress, String hmac, Event event) {
        String computedHmac = computeHMAC(customerName, email, billingAddress, event);
        return MessageDigest.isEqual(hmac.getBytes(StandardCharsets.UTF_8), computedHmac.getBytes(StandardCharsets.UTF_8));
    }

    public Pair<String, String> commitPayment(String reservationId, String token, String payerId, Event event) throws PayPalRESTException {

        Payment payment = new Payment().setId(token);
        PaymentExecution paymentExecute = new PaymentExecution();
        paymentExecute.setPayerId(payerId);
        Payment result = payment.execute(getApiContext(event), paymentExecute);


        // state can only be "created", "approved" or "failed".
        // if we are at this stage, the only possible options are approved or failed, thus it's safe to re transition the reservation to a pending status: no payment has been made!
        if(!"approved".equals(result.getState())) {
            log.warn("error in state for reservationId {}, expected 'approved' state, but got '{}', failure reason is {}", reservationId, result.getState(), result.getFailureReason());
            throw new PayPalRESTException(result.getFailureReason());
        }

        // navigate the object graph (ideally taking the first Sale object) result.getTransactions().get(0).getRelatedResources().get(0).getSale().getId()
        String captureId = result.getTransactions().stream()
            .map(Transaction::getRelatedResources)
            .flatMap(List::stream)
            .map(RelatedResources::getSale)
            .filter(Objects::nonNull)
            .map(Sale::getId)
            .filter(Objects::nonNull)
            .findFirst().orElseThrow(IllegalStateException::new);

        return Pair.of(captureId, payment.getId());
    }

    public Optional<PaymentInformations> getInfo(alfio.model.transaction.Transaction transaction, Event event) {
        try {
            String refund = null;

            //check for backward compatibility  reason...
            if(transaction.getPaymentId() != null) {
                //navigate in all refund objects and sum their amount
                refund = Payment.get(getApiContext(event), transaction.getPaymentId()).getTransactions().stream()
                    .map(Transaction::getRelatedResources)
                    .flatMap(List::stream)
                    .filter(f -> f.getRefund() != null)
                    .map(RelatedResources::getRefund)
                    .map(Refund::getAmount)
                    .map(Amount::getTotal)
                    .map(BigDecimal::new)
                    .reduce(BigDecimal.ZERO, BigDecimal::add).toPlainString();
                //
            }
            Capture c = Capture.get(getApiContext(event), transaction.getTransactionId());
            return Optional.ofNullable(new PaymentInformations(c.getAmount().getTotal(), refund));
        } catch (PayPalRESTException ex) {
            log.warn("Paypal: error while fetching information for payment id " + transaction.getTransactionId(), ex);
            return Optional.empty();
        }
    }

    public boolean refund(alfio.model.transaction.Transaction transaction, Event event, Optional<Integer> amount) {
        String captureId = transaction.getTransactionId();
        try {
            String amountOrFull = amount.map(MonetaryUtil::formatCents).orElse("full");
            log.info("Paypal: trying to do a refund for payment {} with amount: {}", captureId, amountOrFull);
            Capture capture = Capture.get(getApiContext(event), captureId);
            RefundRequest refundRequest = new RefundRequest();
            amount.ifPresent(a -> refundRequest.setAmount(new Amount(capture.getAmount().getCurrency(), formatCents(a))));
            DetailedRefund res = capture.refund(getApiContext(event), refundRequest);
            log.info("Paypal: refund for payment {} executed with success for amount: {}", captureId, amountOrFull);
            return true;
        } catch(PayPalRESTException ex) {
            log.warn("Paypal: was not able to refund payment with id " + captureId, ex);
            return false;
        }
    }
}
