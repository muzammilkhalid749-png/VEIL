package com.m3.veil.ui.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CountryData {

    public static List<Country> getAllCountries() {
        List<Country> countries = new ArrayList<>();

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
        countries.add(new Country("Russia", "RU", "+7", "🇷🇺"));

        // Asia
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
        countries.add(new Country("Pakistan", "PK", "+92", "🇵🇰"));
        countries.add(new Country("Bangladesh", "BD", "+880", "🇧🇩"));
        countries.add(new Country("Sri Lanka", "LK", "+94", "🇱🇰"));
        countries.add(new Country("Nepal", "NP", "+977", "🇳🇵"));

        // Middle East
        countries.add(new Country("United Arab Emirates", "AE", "+971", "🇦🇪"));
        countries.add(new Country("Saudi Arabia", "SA", "+966", "🇸🇦"));
        countries.add(new Country("Qatar", "QA", "+974", "🇶🇦"));
        countries.add(new Country("Kuwait", "KW", "+965", "🇰🇼"));
        countries.add(new Country("Israel", "IL", "+972", "🇮🇱"));
        countries.add(new Country("Turkey", "TR", "+90", "🇹🇷"));
        countries.add(new Country("Lebanon", "LB", "+961", "🇱🇧"));
        countries.add(new Country("Jordan", "JO", "+962", "🇯🇴"));

        // Africa
        countries.add(new Country("South Africa", "ZA", "+27", "🇿🇦"));
        countries.add(new Country("Nigeria", "NG", "+234", "🇳🇬"));
        countries.add(new Country("Egypt", "EG", "+20", "🇪🇬"));
        countries.add(new Country("Kenya", "KE", "+254", "🇰🇪"));
        countries.add(new Country("Ghana", "GH", "+233", "🇬🇭"));
        countries.add(new Country("Morocco", "MA", "+212", "🇲🇦"));
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

    public static Country getDefaultCountry() {
        // Get device locale and try to match country
        String deviceCountry = Locale.getDefault().getCountry();
        List<Country> countries = getAllCountries();

        for (Country country : countries) {
            if (country.getCode().equals(deviceCountry)) {
                return country;
            }
        }

        // Fallback to US
        return new Country("United States", "US", "+1", "🇺🇸");
    }
}