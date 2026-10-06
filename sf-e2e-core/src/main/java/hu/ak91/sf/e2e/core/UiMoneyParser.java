package hu.ak91.sf.e2e.core;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.*;
import java.util.Locale;
import java.util.Currency;
import java.text.*;
public final class UiMoneyParser {
    private UiMoneyParser() {}
    public static BigDecimal money(String text) {
        String value=text.trim();
        Failure.require(value.matches("-?\\$?(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]{1,2})?"),"ORG_PROFILE_MISMATCH","Malformed en-US monetary value");
        return new BigDecimal(value.replace("$","").replace(",",""));
    }
    /** Explicit locale/currency parsing; no org-wide default is inferred from a display string. */
    public static BigDecimal money(String text,Locale locale,Currency currency) {
        var format=(DecimalFormat)NumberFormat.getCurrencyInstance(locale);format.setCurrency(currency);format.setParseBigDecimal(true);
        format.setMinimumFractionDigits(currency.getDefaultFractionDigits());format.setMaximumFractionDigits(currency.getDefaultFractionDigits());
        var position=new ParsePosition(0);String value=text.strip();var parsed=format.parse(value,position);
        Failure.require(parsed instanceof BigDecimal && position.getIndex()==value.length(),"VALUE_FORMAT_INVALID","Monetary value does not match its declared locale/currency");
        String canonical=format.format(parsed);char separator=format.getDecimalFormatSymbols().getGroupingSeparator();
        Failure.require(normalize(value).equals(normalize(canonical)) || normalize(value).equals(normalize(canonical.replace(String.valueOf(separator),""))),
            "VALUE_FORMAT_INVALID","Monetary grouping or precision is invalid");
        return (BigDecimal)parsed;
    }
    private static String normalize(String value) { return value.replace('\u00a0',' ').replace('\u202f',' '); }
    public static BigDecimal percent(String text) {
        Failure.require(text.matches("[0-9]+(?:\\.[0-9]+)?%?"),"ORG_PROFILE_MISMATCH","Malformed percentage");
        var value=new BigDecimal(text.replace("%",""));
        Failure.require(value.signum()>=0 && value.compareTo(new BigDecimal("100"))<=0,"VALUE_MISMATCH","Probability outside 0–100"); return value;
    }
    public static LocalDate date(String text) {
        try { return LocalDate.parse(text,DateTimeFormatter.ofPattern("M/d/uuuu",Locale.US).withResolverStyle(ResolverStyle.STRICT)); }
        catch(DateTimeParseException e) { throw new Failure("ORG_PROFILE_MISMATCH","Malformed en-US date"); }
    }
    public static LocalDate date(String text,DateTimeFormatter formatter) {
        try { return LocalDate.parse(text,formatter.withResolverStyle(ResolverStyle.STRICT)); }
        catch(DateTimeParseException e) { throw new Failure("VALUE_FORMAT_INVALID","Date does not match its declared format"); }
    }
}
