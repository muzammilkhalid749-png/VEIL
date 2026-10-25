package com.m3.veil.utils;

import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class PhoneNumberUtils {

    // Comprehensive country phone number formats
    private static final Map<String, PhoneNumberFormat> COUNTRY_FORMATS = new HashMap<>();

    static {
        // ===== ASIA =====

        // Pakistan - Mobile: 10 digits starting with 3 (with/without leading 0)
        COUNTRY_FORMATS.put("+92", new PhoneNumberFormat(
                "XXX-XXXXXXX",
                10,
                Pattern.compile("^(0)?3[0-9]{9}$"),
                "Pakistani mobile numbers should be 10 digits starting with 3 (3123456789) or 11 digits with leading zero (03123456789)",
                "3123456789"
        ));

        // India - Mobile: 10 digits starting with 6-9
        COUNTRY_FORMATS.put("+91", new PhoneNumberFormat(
                "XXXXX-XXXXX",
                10,
                Pattern.compile("^[6-9][0-9]{9}$"),
                "Indian mobile numbers should be 10 digits starting with 6, 7, 8, or 9",
                "9876543210"
        ));

        // China - Mobile: 11 digits starting with 1
        COUNTRY_FORMATS.put("+86", new PhoneNumberFormat(
                "XXX-XXXX-XXXX",
                11,
                Pattern.compile("^1[3-9][0-9]{9}$"),
                "Chinese mobile numbers should be 11 digits starting with 1",
                "13123456789"
        ));

        // Bangladesh - Mobile: 10 digits starting with 1
        COUNTRY_FORMATS.put("+880", new PhoneNumberFormat(
                "XXXX-XXXXXX",
                10,
                Pattern.compile("^1[0-9]{9}$"),
                "Bangladeshi mobile numbers should be 10 digits starting with 1",
                "1712345678"
        ));

        // Sri Lanka - Mobile: 9 digits starting with 7
        COUNTRY_FORMATS.put("+94", new PhoneNumberFormat(
                "XX-XXXXXXX",
                9,
                Pattern.compile("^7[0-9]{8}$"),
                "Sri Lankan mobile numbers should be 9 digits starting with 7",
                "771234567"
        ));

        // UAE - Mobile: 9 digits starting with 5
        COUNTRY_FORMATS.put("+971", new PhoneNumberFormat(
                "XX-XXX-XXXX",
                9,
                Pattern.compile("^5[0-9]{8}$"),
                "UAE mobile numbers should be 9 digits starting with 5",
                "501234567"
        ));

        // Saudi Arabia - Mobile: 9 digits starting with 5
        COUNTRY_FORMATS.put("+966", new PhoneNumberFormat(
                "X-XXX-XXXX",
                9,
                Pattern.compile("^5[0-9]{8}$"),
                "Saudi mobile numbers should be 9 digits starting with 5",
                "512345678"
        ));

        // ===== NORTH AMERICA =====

        // United States & Canada - 10 digits, area code 2-9
        COUNTRY_FORMATS.put("+1", new PhoneNumberFormat(
                "(XXX) XXX-XXXX",
                10,
                Pattern.compile("^[2-9][0-9]{9}$"),
                "US/Canada numbers should be 10 digits, area code cannot start with 0 or 1",
                "2015550123"
        ));

        // Mexico - 10 digits starting with 1, 2, 5, 6, 8, 9
        COUNTRY_FORMATS.put("+52", new PhoneNumberFormat(
                "XXX-XXX-XXXX",
                10,
                Pattern.compile("^[125689][0-9]{9}$"),
                "Mexican numbers should be 10 digits starting with 1, 2, 5, 6, 8, or 9",
                "5512345678"
        ));

        // ===== EUROPE =====

        // United Kingdom - Mobile: 10 digits starting with 7
        COUNTRY_FORMATS.put("+44", new PhoneNumberFormat(
                "XXXX XXX XXX",
                10,
                Pattern.compile("^7[0-9]{9}$"),
                "UK mobile numbers should be 10 digits starting with 7",
                "7123456789"
        ));

        // Germany - Mobile: 10-11 digits starting with 15, 16, 17
        COUNTRY_FORMATS.put("+49", new PhoneNumberFormat(
                "XXXX-XXXXXXX",
                10,
                Pattern.compile("^(15|16|17)[0-9]{8,9}$"),
                "German mobile numbers should be 10-11 digits starting with 15, 16, or 17",
                "15123456789"
        ));

        // France - Mobile: 9 digits starting with 6 or 7
        COUNTRY_FORMATS.put("+33", new PhoneNumberFormat(
                "X XX XX XX XX",
                9,
                Pattern.compile("^[67][0-9]{8}$"),
                "French mobile numbers should be 9 digits starting with 6 or 7",
                "612345678"
        ));

        // Italy - Mobile: 10 digits starting with 3
        COUNTRY_FORMATS.put("+39", new PhoneNumberFormat(
                "XXX-XXXXXXX",
                10,
                Pattern.compile("^3[0-9]{9}$"),
                "Italian mobile numbers should be 10 digits starting with 3",
                "3123456789"
        ));

        // Spain - Mobile: 9 digits starting with 6 or 7
        COUNTRY_FORMATS.put("+34", new PhoneNumberFormat(
                "XXX-XX-XX-XX",
                9,
                Pattern.compile("^[67][0-9]{8}$"),
                "Spanish mobile numbers should be 9 digits starting with 6 or 7",
                "612345678"
        ));

        // ===== OTHER REGIONS =====

        // Australia - Mobile: 9 digits starting with 4
        COUNTRY_FORMATS.put("+61", new PhoneNumberFormat(
                "X XXXX XXXX",
                9,
                Pattern.compile("^4[0-9]{8}$"),
                "Australian mobile numbers should be 9 digits starting with 4",
                "412345678"
        ));

        // Brazil - Mobile: 10-11 digits starting with area code + 9
        COUNTRY_FORMATS.put("+55", new PhoneNumberFormat(
                "(XX) XXXXX-XXXX",
                10,
                Pattern.compile("^[1-9][0-9]{9,10}$"),
                "Brazilian numbers should be 10-11 digits",
                "11987654321"
        ));

        // Russia - Mobile: 10 digits starting with 9
        COUNTRY_FORMATS.put("+7", new PhoneNumberFormat(
                "XXX-XXX-XXXX",
                10,
                Pattern.compile("^9[0-9]{9}$"),
                "Russian mobile numbers should be 10 digits starting with 9",
                "9123456789"
        ));

        // Nigeria - Mobile: 10 digits starting with 7, 8, or 9
        COUNTRY_FORMATS.put("+234", new PhoneNumberFormat(
                "XXX-XXXXXXX",
                10,
                Pattern.compile("^[789][0-9]{9}$"),
                "Nigerian mobile numbers should be 10 digits starting with 7, 8, or 9",
                "7012345678"
        ));

        // South Africa - Mobile: 9 digits starting with 6, 7, or 8
        COUNTRY_FORMATS.put("+27", new PhoneNumberFormat(
                "XX-XXX-XXXX",
                9,
                Pattern.compile("^[678][0-9]{8}$"),
                "South African mobile numbers should be 9 digits starting with 6, 7, or 8",
                "712345678"
        ));

        // Default format for other countries
        COUNTRY_FORMATS.put("default", new PhoneNumberFormat(
                "XXX-XXX-XXXX",
                7,
                Pattern.compile("^[0-9]{7,15}$"),
                "Please enter a valid phone number",
                "1234567890"
        ));
    }

    public static class PhoneNumberFormat {
        public String displayFormat;
        public int minLength;
        public Pattern validationPattern;
        public String errorMessage;
        public String example;

        public PhoneNumberFormat(String displayFormat, int minLength, Pattern validationPattern,
                                 String errorMessage, String example) {
            this.displayFormat = displayFormat;
            this.minLength = minLength;
            this.validationPattern = validationPattern;
            this.errorMessage = errorMessage;
            this.example = example;
        }
    }

    /**
     * Format phone number for display based on country code
     */
    public static String formatPhoneNumberForDisplay(String countryCode, String rawNumber) {
        if (rawNumber == null || rawNumber.isEmpty()) {
            return rawNumber;
        }

        // Remove all non-digit characters
        String digitsOnly = rawNumber.replaceAll("[^0-9]", "");

        if (digitsOnly.isEmpty()) {
            return rawNumber;
        }

        // Country-specific preprocessing
        digitsOnly = preprocessForDisplay(countryCode, digitsOnly);

        PhoneNumberFormat format = COUNTRY_FORMATS.get(countryCode);
        if (format == null) {
            format = COUNTRY_FORMATS.get("default");
        }

        return applyFormat(digitsOnly, format.displayFormat);
    }

    /**
     * Preprocess phone number for display
     */
    private static String preprocessForDisplay(String countryCode, String digits) {
        switch (countryCode) {
            case "+92": // Pakistan - remove leading zero for consistent display
                if (digits.startsWith("0") && digits.length() > 1) {
                    return digits.substring(1);
                }
                break;
            case "+44": // UK - remove leading 0
            case "+49": // Germany - remove leading 0
            case "+33": // France - remove leading 0
            case "+61": // Australia - remove leading 0
                if (digits.startsWith("0") && digits.length() > 1) {
                    return digits.substring(1);
                }
                break;
        }
        return digits;
    }

    /**
     * Validate phone number for Firebase requirements
     */
    public static ValidationResult validatePhoneNumberForFirebase(String countryCode, String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            return new ValidationResult(false, "Phone number cannot be empty");
        }

        // Remove all non-digit characters
        String digitsOnly = phoneNumber.replaceAll("[^0-9]", "");

        if (digitsOnly.isEmpty()) {
            return new ValidationResult(false, "Please enter a valid phone number");
        }

        PhoneNumberFormat format = COUNTRY_FORMATS.get(countryCode);
        if (format == null) {
            format = COUNTRY_FORMATS.get("default");
        }

        // Country-specific preprocessing for validation
        String processedDigits = preprocessForValidation(countryCode, digitsOnly);

        // Check minimum length
        if (processedDigits.length() < format.minLength) {
            return new ValidationResult(false,
                    String.format("Phone number should be at least %d digits. Example: %s",
                            format.minLength, format.example));
        }

        // Check maximum length (Firebase limit)
        if (processedDigits.length() > 15) {
            return new ValidationResult(false,
                    "Phone number is too long for Firebase authentication");
        }

        // Country-specific validation
        if (!format.validationPattern.matcher(processedDigits).matches()) {
            return new ValidationResult(false, format.errorMessage + " Example: " + format.example);
        }

        // Prepare number for Firebase (country code + processed digits)
        String firebaseNumber = countryCode + processedDigits;

        return new ValidationResult(true, "Valid", firebaseNumber);
    }

    /**
     * Preprocess for validation
     */
    private static String preprocessForValidation(String countryCode, String digits) {
        switch (countryCode) {
            case "+92": // Pakistan
                // Handle both formats: with zero (11 digits) and without (10 digits)
                if (digits.startsWith("0") && digits.length() == 11) {
                    String withoutZero = digits.substring(1);
                    if (withoutZero.startsWith("3")) {
                        return withoutZero;
                    }
                } else if (digits.startsWith("3") && digits.length() == 10) {
                    return digits;
                }
                break;

            case "+44": // UK - remove leading 0 if present
            case "+49": // Germany - remove leading 0
            case "+33": // France - remove leading 0
            case "+61": // Australia - remove leading 0
                if (digits.startsWith("0") && digits.length() > 1) {
                    return digits.substring(1);
                }
                break;

            case "+1": // US/Canada - ensure no leading 0 or 1 in area code
                if (digits.length() == 10 && digits.charAt(0) >= '2' && digits.charAt(0) <= '9') {
                    return digits;
                }
                break;
        }
        return digits;
    }

    /**
     * Apply specific format to digits
     */
    private static String applyFormat(String digits, String format) {
        StringBuilder formatted = new StringBuilder();
        int digitIndex = 0;

        for (int i = 0; i < format.length() && digitIndex < digits.length(); i++) {
            char formatChar = format.charAt(i);
            if (formatChar == 'X') {
                formatted.append(digits.charAt(digitIndex));
                digitIndex++;
            } else {
                formatted.append(formatChar);
            }
        }

        // Add remaining digits without formatting
        if (digitIndex < digits.length()) {
            formatted.append(digits.substring(digitIndex));
        }

        return formatted.toString();
    }

    /**
     * Add text watcher to format phone number as user types
     */
    public static void addPhoneNumberFormatter(EditText phoneEditText, String countryCode) {
        phoneEditText.addTextChangedListener(new TextWatcher() {
            private boolean isFormatting = false;
            private String previousText = "";

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                previousText = s.toString();
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (isFormatting) return;

                isFormatting = true;

                String currentText = s.toString();
                String digitsOnly = currentText.replaceAll("[^0-9]", "");

                // If user is deleting, don't reformat
                if (currentText.length() < previousText.length() && !digitsOnly.isEmpty()) {
                    isFormatting = false;
                    return;
                }

                if (!digitsOnly.isEmpty()) {
                    String formatted = formatPhoneNumberForDisplay(countryCode, digitsOnly);
                    if (!currentText.equals(formatted)) {
                        s.replace(0, s.length(), formatted);
                    }
                }

                isFormatting = false;
            }
        });
    }

    /**
     * Remove formatting for Firebase (country code + digits only)
     */
    public static String prepareForFirebase(String countryCode, String formattedNumber) {
        if (formattedNumber == null) return "";

        String digitsOnly = formattedNumber.replaceAll("[^0-9]", "");

        // Country-specific processing for Firebase
        switch (countryCode) {
            case "+92": // Pakistan - remove leading zero if present
                if (digitsOnly.startsWith("0") && digitsOnly.length() == 11) {
                    digitsOnly = digitsOnly.substring(1);
                }
                break;
            case "+44": // UK - remove leading 0
            case "+49": // Germany - remove leading 0
            case "+33": // France - remove leading 0
            case "+61": // Australia - remove leading 0
                if (digitsOnly.startsWith("0") && digitsOnly.length() > 1) {
                    digitsOnly = digitsOnly.substring(1);
                }
                break;
        }

        return countryCode + digitsOnly;
    }

    /**
     * Get country-specific requirements for hint text
     */
    public static String getPhoneNumberHint(String countryCode) {
        PhoneNumberFormat format = COUNTRY_FORMATS.get(countryCode);
        if (format != null) {
            return format.example;
        }
        return COUNTRY_FORMATS.get("default").example;
    }

    /**
     * Get country-specific error message
     */
    public static String getCountryErrorMessage(String countryCode) {
        PhoneNumberFormat format = COUNTRY_FORMATS.get(countryCode);
        if (format != null) {
            return format.errorMessage + " Example: " + format.example;
        }
        return COUNTRY_FORMATS.get("default").errorMessage;
    }

    public static class ValidationResult {
        public boolean isValid;
        public String message;
        public String formattedNumber;

        public ValidationResult(boolean isValid, String message) {
            this.isValid = isValid;
            this.message = message;
        }

        public ValidationResult(boolean isValid, String message, String formattedNumber) {
            this.isValid = isValid;
            this.message = message;
            this.formattedNumber = formattedNumber;
        }
    }

    /**
     * Get all supported country codes
     */
    public static String[] getSupportedCountryCodes() {
        return COUNTRY_FORMATS.keySet().toArray(new String[0]);
    }

    /**
     * Check if country code is supported
     */
    public static boolean isCountrySupported(String countryCode) {
        return COUNTRY_FORMATS.containsKey(countryCode);
    }
}