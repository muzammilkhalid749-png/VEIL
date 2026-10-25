package com.m3.veil.ui.auth;

public class Country {
    private String name;
    private String code;
    private String dialCode;
    private String flag;

    public Country(String name, String code, String dialCode, String flag) {
        this.name = name;
        this.code = code;
        this.dialCode = dialCode;
        this.flag = flag;
    }

    public String getName() { return name; }
    public String getCode() { return code; }
    public String getDialCode() { return dialCode; }
    public String getFlag() { return flag; }
}