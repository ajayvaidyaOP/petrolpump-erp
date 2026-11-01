package in.petrolflow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/** Pure financial rules shared by API handlers and regression tests. No database side effects. */
public final class FinanceRules {
    public static final Set<String> PAYMENT_MODES = Set.of("CASH", "UPI", "CARD", "BANK", "CHEQUE");
    public static final Set<String> DIGITAL_MODES = Set.of("UPI", "CARD", "BANK");
    private FinanceRules() {}

    public record PaymentParty(String partyType, String direction, String customerId, String supplierId) {}

    public static PaymentParty validatePayment(String rawPartyType, String rawDirection,
                                               String rawCustomerId, String rawSupplierId, String rawMode) {
        String partyType = norm(rawPartyType);
        String direction = norm(rawDirection);
        String customerId = blank(rawCustomerId);
        String supplierId = blank(rawSupplierId);
        if (!Set.of("CUSTOMER", "SUPPLIER").contains(partyType)) {
            throw ApiException.validation("partyType", "partyType must be CUSTOMER or SUPPLIER");
        }
        if (!Set.of("RECEIPT", "PAYOUT").contains(direction)) {
            throw ApiException.validation("direction", "direction must be RECEIPT or PAYOUT");
        }
        if (("CUSTOMER".equals(partyType) && !"RECEIPT".equals(direction))
                || ("SUPPLIER".equals(partyType) && !"PAYOUT".equals(direction))) {
            throw ApiException.validation("direction", "Customer payments must be receipts; supplier payments must be payouts");
        }
        if ("CUSTOMER".equals(partyType)) {
            if (customerId == null) throw ApiException.validation("customerId", "Customer is required for a receipt");
            if (supplierId != null) throw ApiException.validation("supplierId", "A customer receipt cannot also name a supplier");
        } else {
            if (supplierId == null) throw ApiException.validation("supplierId", "Supplier is required for a payout");
            if (customerId != null) throw ApiException.validation("customerId", "A supplier payout cannot also name a customer");
        }
        if (!PAYMENT_MODES.contains(norm(rawMode))) {
            throw ApiException.validation("mode", "Payment mode must be CASH, UPI, CARD, BANK or CHEQUE. Credit is not a settled payment.");
        }
        return new PaymentParty(partyType, direction, customerId, supplierId);
    }

    public static BigDecimal positiveMoney(String raw, String field) {
        BigDecimal value = decimal(raw, field);
        if (value.signum() <= 0) throw ApiException.validation(field, field + " must be greater than zero");
        if (value.stripTrailingZeros().scale() > 2) {
            throw ApiException.validation(field, field + " cannot have more than two decimal places");
        }
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    public static BigDecimal nonNegativeMoney(String raw, String field) {
        BigDecimal value = decimal(raw, field);
        if (value.signum() < 0) throw ApiException.validation(field, field + " cannot be negative");
        if (value.stripTrailingZeros().scale() > 2) {
            throw ApiException.validation(field, field + " cannot have more than two decimal places");
        }
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    public static BigDecimal creditAmount(String rawAmount, String rawQuantity, String rawRate) {
        BigDecimal quantity = blank(rawQuantity) == null ? null : decimal(rawQuantity, "quantityLitres");
        BigDecimal rate = blank(rawRate) == null ? null : decimal(rawRate, "ratePerLitre");
        if (quantity != null && quantity.signum() <= 0) throw ApiException.validation("quantityLitres", "quantityLitres must be greater than zero");
        if (rate != null && rate.signum() < 0) throw ApiException.validation("ratePerLitre", "ratePerLitre cannot be negative");
        if (quantity != null && rate != null) {
            BigDecimal expected = quantity.multiply(rate).setScale(2, RoundingMode.HALF_UP);
            if (blank(rawAmount) != null) {
                BigDecimal supplied = positiveMoney(rawAmount, "amount");
                if (supplied.compareTo(expected) != 0) {
                    throw ApiException.validation("amount", "amount must equal quantityLitres × ratePerLitre (" + Money.money(expected) + ")");
                }
            }
            return expected;
        }
        if (quantity != null) throw ApiException.validation("ratePerLitre", "An effective price or ratePerLitre is required for a quantity-based credit sale");
        return positiveMoney(rawAmount, "amount");
    }

    public record Variances(BigDecimal cash, BigDecimal digital, boolean balanced, String status, BigDecimal displayed) {}

    public static Variances reconciliation(BigDecimal cashExpected, BigDecimal cashCounted,
                                           BigDecimal digitalExpected, BigDecimal digitalCounted) {
        BigDecimal cash = cashCounted.subtract(cashExpected).setScale(2, RoundingMode.HALF_UP);
        BigDecimal digital = digitalCounted.subtract(digitalExpected).setScale(2, RoundingMode.HALF_UP);
        boolean cashBalanced = cash.abs().compareTo(BigDecimal.ONE) <= 0;
        boolean digitalBalanced = digital.abs().compareTo(BigDecimal.ONE) <= 0;
        boolean balanced = cashBalanced && digitalBalanced;
        BigDecimal net = cash.add(digital);
        // Never let offsetting errors hide a cash shortage or digital excess.
        BigDecimal displayed = !balanced && net.abs().compareTo(BigDecimal.ONE) <= 0 ? cash : net;
        return new Variances(cash, digital, balanced, balanced ? "BALANCED" : "VARIANCE", displayed);
    }

    private static BigDecimal decimal(String raw, String field) {
        if (blank(raw) == null) throw ApiException.validation(field, field + " is required");
        try { return new BigDecimal(raw.trim()); }
        catch (NumberFormatException ex) { throw ApiException.validation(field, field + " must be a valid number"); }
    }
    private static String blank(String raw) { return raw == null || raw.isBlank() ? null : raw.trim(); }
    private static String norm(String raw) { return raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT); }
}
