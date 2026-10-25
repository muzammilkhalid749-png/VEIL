package com.m3.veil.ui.auth;

import android.content.Context;
import android.os.Build;
import android.os.LocaleList;
import android.telephony.TelephonyManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class CountryData {

    public static List<Country> getAllCountries() {
        List<Country> countries = new ArrayList<>();

        // Asia
        countries.add(new Country("Pakistan", "PK", "+92", "🇵🇰"));
        countries.add(new Country("India", "IN", "+91", "🇮🇳"));
        countries.add(new Country("China", "CN", "+86", "🇨🇳"));
        countries.add(new Country("Japan", "JP", "+81", "🇯🇵"));
        countries.add(new Country("South Korea", "KR", "+82", "🇰🇷"));
        countries.add(new Country("Singapore", "SG", "+65", "🇸🇬"));
        countries.add(new Country("Malaysia", "MY", "+60", "🇲🇾"));
        countries.add(new Country("Thailand", "TH", "+66", "🇹🇭"));
        countries.add(new Country("Vietnam", "VN", "+84", "🇻🇳"));
        countries.add(new Country("Philippines", "PH", "+63", "🇵🇭"));
        countries.add(new Country("Indonesia", "ID", "+62", "🇮🇩"));
        countries.add(new Country("Bangladesh", "BD", "+880", "🇧🇩"));
        countries.add(new Country("Sri Lanka", "LK", "+94", "🇱🇰"));
        countries.add(new Country("Nepal", "NP", "+977", "🇳🇵"));
        countries.add(new Country("Afghanistan", "AF", "+93", "🇦🇫"));
        countries.add(new Country("Uzbekistan", "UZ", "+998", "🇺🇿"));
        countries.add(new Country("Kazakhstan", "KZ", "+7", "🇰🇿"));
        countries.add(new Country("Azerbaijan", "AZ", "+994", "🇦🇿"));

        // Middle East
        countries.add(new Country("United Arab Emirates", "AE", "+971", "🇦🇪"));
        countries.add(new Country("Saudi Arabia", "SA", "+966", "🇸🇦"));
        countries.add(new Country("Qatar", "QA", "+974", "🇶🇦"));
        countries.add(new Country("Kuwait", "KW", "+965", "🇰🇼"));
        countries.add(new Country("Oman", "OM", "+968", "🇴🇲"));
        countries.add(new Country("Bahrain", "BH", "+973", "🇧🇭"));
        countries.add(new Country("Turkey", "TR", "+90", "🇹🇷"));
        countries.add(new Country("Iraq", "IQ", "+964", "🇮🇶"));
        countries.add(new Country("Iran", "IR", "+98", "🇮🇷"));
        countries.add(new Country("Jordan", "JO", "+962", "🇯🇴"));
        countries.add(new Country("Lebanon", "LB", "+961", "🇱🇧"));
        countries.add(new Country("Yemen", "YE", "+967", "🇾🇪"));
        countries.add(new Country("Syria", "SY", "+963", "🇸🇾"));
        countries.add(new Country("Palestine", "PS", "+970", "🇵🇸"));
        countries.add(new Country("Israel", "IL", "+972", "🇮🇱"));

        // North America
        countries.add(new Country("United States", "US", "+1", "🇺🇸"));
        countries.add(new Country("Canada", "CA", "+1", "🇨🇦"));
        countries.add(new Country("Mexico", "MX", "+52", "🇲🇽"));

        // Europe
        countries.add(new Country("United Kingdom", "GB", "+44", "🇬🇧"));
        countries.add(new Country("Germany", "DE", "+49", "🇩🇪"));
        countries.add(new Country("France", "FR", "+33", "🇫🇷"));
        countries.add(new Country("Italy", "IT", "+39", "🇮🇹"));
        countries.add(new Country("Spain", "ES", "+34", "🇪🇸"));
        countries.add(new Country("Netherlands", "NL", "+31", "🇳🇱"));
        countries.add(new Country("Switzerland", "CH", "+41", "🇨🇭"));
        countries.add(new Country("Sweden", "SE", "+46", "🇸🇪"));
        countries.add(new Country("Norway", "NO", "+47", "🇳🇴"));
        countries.add(new Country("Denmark", "DK", "+45", "🇩🇰"));
        countries.add(new Country("Finland", "FI", "+358", "🇫🇮"));
        countries.add(new Country("Ireland", "IE", "+353", "🇮🇪"));
        countries.add(new Country("Portugal", "PT", "+351", "🇵🇹"));
        countries.add(new Country("Belgium", "BE", "+32", "🇧🇪"));
        countries.add(new Country("Austria", "AT", "+43", "🇦🇹"));
        countries.add(new Country("Poland", "PL", "+48", "🇵🇱"));
        countries.add(new Country("Czech Republic", "CZ", "+420", "🇨🇿"));
        countries.add(new Country("Romania", "RO", "+40", "🇷🇴"));
        countries.add(new Country("Greece", "GR", "+30", "🇬🇷"));
        countries.add(new Country("Hungary", "HU", "+36", "🇭🇺"));
        countries.add(new Country("Ukraine", "UA", "+380", "🇺🇦"));
        countries.add(new Country("Russia", "RU", "+7", "🇷🇺"));

        // Africa
        countries.add(new Country("Egypt", "EG", "+20", "🇪🇬"));
        countries.add(new Country("Nigeria", "NG", "+234", "🇳🇬"));
        countries.add(new Country("South Africa", "ZA", "+27", "🇿🇦"));
        countries.add(new Country("Kenya", "KE", "+254", "🇰🇪"));
        countries.add(new Country("Ghana", "GH", "+233", "🇬🇭"));
        countries.add(new Country("Morocco", "MA", "+212", "🇲🇦"));
        countries.add(new Country("Algeria", "DZ", "+213", "🇩🇿"));
        countries.add(new Country("Tunisia", "TN", "+216", "🇹🇳"));
        countries.add(new Country("Sudan", "SD", "+249", "🇸🇩"));
        countries.add(new Country("Ethiopia", "ET", "+251", "🇪🇹"));

        // Oceania
        countries.add(new Country("Australia", "AU", "+61", "🇦🇺"));
        countries.add(new Country("New Zealand", "NZ", "+64", "🇳🇿"));

        // South America
        countries.add(new Country("Brazil", "BR", "+55", "🇧🇷"));
        countries.add(new Country("Argentina", "AR", "+54", "🇦🇷"));
        countries.add(new Country("Chile", "CL", "+56", "🇨🇱"));
        countries.add(new Country("Colombia", "CO", "+57", "🇨🇴"));
        countries.add(new Country("Peru", "PE", "+51", "🇵🇪"));
        countries.add(new Country("Venezuela", "VE", "+58", "🇻🇪"));

        return countries;
    }

    public static Country findCountryByCode(String code) {
        if (code == null || code.trim().isEmpty()) return null;
        String cleanCode = code.trim().toUpperCase(Locale.US);
        for (Country country : getAllCountries()) {
            if (country.getCode().equalsIgnoreCase(cleanCode)) {
                return country;
            }
        }
        return null;
    }

    public static String getFlagEmoji(String countryCode) {
        if (countryCode == null || countryCode.length() != 2) return "🌐";
        String upper = countryCode.toUpperCase(Locale.US);
        int firstChar = Character.codePointAt(upper, 0) - 0x41 + 0x1F1E6;
        int secondChar = Character.codePointAt(upper, 1) - 0x41 + 0x1F1E6;
        if (firstChar < 0x1F1E6 || firstChar > 0x1F1FF || secondChar < 0x1F1E6 || secondChar > 0x1F1FF) {
            return "🌐";
        }
        return new String(Character.toChars(firstChar)) + new String(Character.toChars(secondChar));
    }

    private static String getCountryFromTimeZone(String tzId) {
        if (tzId == null) return null;
        String tz = tzId.trim();

        if (tz.contains("Karachi")) return "PK";
        if (tz.contains("Kolkata") || tz.contains("Calcutta")) return "IN";
        if (tz.contains("Dubai")) return "AE";
        if (tz.contains("Riyadh")) return "SA";
        if (tz.contains("Dhaka")) return "BD";
        if (tz.contains("Colombo")) return "LK";
        if (tz.contains("Kathmandu") || tz.contains("Katmandu")) return "NP";
        if (tz.contains("Singapore")) return "SG";
        if (tz.contains("Kuala_Lumpur")) return "MY";
        if (tz.contains("Bangkok")) return "TH";
        if (tz.contains("Jakarta")) return "ID";
        if (tz.contains("Manila")) return "PH";
        if (tz.contains("Tokyo")) return "JP";
        if (tz.contains("Seoul")) return "KR";
        if (tz.contains("Shanghai") || tz.contains("Hong_Kong") || tz.contains("Beijing") || tz.contains("Urumqi")) return "CN";
        if (tz.contains("Muscat")) return "OM";
        if (tz.contains("Bahrain")) return "BH";
        if (tz.contains("Qatar")) return "QA";
        if (tz.contains("Kuwait")) return "KW";
        if (tz.contains("Baghdad")) return "IQ";
        if (tz.contains("Tehran")) return "IR";
        if (tz.contains("Beirut")) return "LB";
        if (tz.contains("Amman")) return "JO";
        if (tz.contains("Jerusalem")) return "IL";
        if (tz.contains("Istanbul")) return "TR";
        if (tz.contains("Kabul")) return "AF";
        if (tz.contains("Tashkent")) return "UZ";
        if (tz.contains("Almaty")) return "KZ";
        if (tz.contains("Baku")) return "AZ";

        if (tz.contains("London")) return "GB";
        if (tz.contains("Berlin") || tz.contains("Frankfurt")) return "DE";
        if (tz.contains("Paris")) return "FR";
        if (tz.contains("Rome")) return "IT";
        if (tz.contains("Madrid")) return "ES";
        if (tz.contains("Amsterdam")) return "NL";
        if (tz.contains("Zurich")) return "CH";
        if (tz.contains("Stockholm")) return "SE";
        if (tz.contains("Oslo")) return "NO";
        if (tz.contains("Copenhagen")) return "DK";
        if (tz.contains("Helsinki")) return "FI";
        if (tz.contains("Dublin")) return "IE";
        if (tz.contains("Lisbon")) return "PT";
        if (tz.contains("Brussels")) return "BE";
        if (tz.contains("Vienna")) return "AT";
        if (tz.contains("Warsaw")) return "PL";
        if (tz.contains("Prague")) return "CZ";
        if (tz.contains("Bucharest")) return "RO";
        if (tz.contains("Athens")) return "GR";
        if (tz.contains("Budapest")) return "HU";
        if (tz.contains("Kyiv") || tz.contains("Kiev")) return "UA";
        if (tz.contains("Moscow")) return "RU";

        if (tz.contains("Cairo")) return "EG";
        if (tz.contains("Lagos")) return "NG";
        if (tz.contains("Johannesburg")) return "ZA";
        if (tz.contains("Nairobi")) return "KE";
        if (tz.contains("Casablanca")) return "MA";
        if (tz.contains("Algiers")) return "DZ";
        if (tz.contains("Tunis")) return "TN";
        if (tz.contains("Khartoum")) return "SD";
        if (tz.contains("Accra")) return "GH";
        if (tz.contains("Addis_Ababa")) return "ET";

        if (tz.contains("Sydney") || tz.contains("Melbourne") || tz.contains("Brisbane") || tz.contains("Perth")) return "AU";
        if (tz.contains("Auckland")) return "NZ";

        if (tz.contains("Toronto") || tz.contains("Vancouver") || tz.contains("Montreal") || tz.contains("Edmonton") || tz.contains("Winnipeg")) return "CA";
        if (tz.contains("Mexico")) return "MX";
        if (tz.contains("Sao_Paulo")) return "BR";
        if (tz.contains("Buenos_Aires")) return "AR";
        if (tz.contains("Bogota")) return "CO";
        if (tz.contains("Lima")) return "PE";
        if (tz.contains("Santiago")) return "CL";

        return null;
    }

    public static Country detectCountry(Context context) {
        String detectedIso = null;

        if (context != null) {
            // 1. Try TelephonyManager SIM country (highest reliability for cellular devices)
            try {
                TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
                if (tm != null) {
                    String simIso = tm.getSimCountryIso();
                    if (simIso != null && !simIso.trim().isEmpty()) {
                        detectedIso = simIso.trim().toUpperCase(Locale.US);
                    }
                    // 2. Try Network Country if SIM didn't yield an ISO code
                    if (detectedIso == null || detectedIso.isEmpty()) {
                        String netIso = tm.getNetworkCountryIso();
                        if (netIso != null && !netIso.trim().isEmpty()) {
                            detectedIso = netIso.trim().toUpperCase(Locale.US);
                        }
                    }
                }
            } catch (Exception ignored) {}

            // 3. Try System Configuration Locale
            if (detectedIso == null || detectedIso.isEmpty()) {
                try {
                    Locale locale;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        LocaleList list = context.getResources().getConfiguration().getLocales();
                        locale = (list != null && !list.isEmpty()) ? list.get(0) : Locale.getDefault();
                    } else {
                        locale = context.getResources().getConfiguration().locale;
                    }
                    if (locale != null && locale.getCountry() != null && !locale.getCountry().trim().isEmpty()) {
                        detectedIso = locale.getCountry().trim().toUpperCase(Locale.US);
                    }
                } catch (Exception ignored) {}
            }
        }

        // 4. Try TimeZone heuristic if still undetermined or if locale was US fallback while timezone is elsewhere
        String tzId = TimeZone.getDefault().getID();
        String tzCountry = getCountryFromTimeZone(tzId);
        if (detectedIso == null || detectedIso.isEmpty() || (detectedIso.equals("US") && tzCountry != null && !tzCountry.equals("US"))) {
            if (tzCountry != null) {
                detectedIso = tzCountry;
            }
        }

        // 5. Try Default Locale country if still null
        if (detectedIso == null || detectedIso.isEmpty()) {
            detectedIso = Locale.getDefault().getCountry();
        }

        if (detectedIso != null && !detectedIso.trim().isEmpty()) {
            Country match = findCountryByCode(detectedIso);
            if (match != null) {
                return match;
            }
        }

        // Default fallback to US
        return new Country("United States", "US", "+1", "🇺🇸");
    }

    public static Country getDefaultCountry() {
        return detectCountry(null);
    }
}
