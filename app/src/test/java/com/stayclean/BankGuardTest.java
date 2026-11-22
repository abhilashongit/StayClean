package com.stayclean;

import static org.junit.Assert.*;

import org.junit.Test;

public class BankGuardTest {
    static final String OWN = "com.stayclean.guard";

    @Test public void reported_apps_are_recognised() {
        assertTrue(BankGuard.nameLooksLikeBank("money.super.payments"));     // super.money
        assertTrue(BankGuard.nameLooksLikeBank("com.axisbank.mobile.oneapp")); // Axis ONEApp
        assertTrue(BankGuard.nameLooksLikeBank("com.axis.mobile"));
    }

    @Test public void common_banks_and_upi_apps_match_by_name() {
        for (String p : new String[]{
                "com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm",
                "in.org.npci.upiapp", "com.snapwork.hdfc", "com.csam.icici.bank.imobile",
                "com.sbi.lotusintouch", "com.kotak.mobilebanking", "com.dreamplug.androidapp"}) {
            assertTrue(p, BankGuard.nameLooksLikeBank(p));
        }
    }

    @Test public void unknown_bank_caught_by_token() {
        assertTrue(BankGuard.nameLooksLikeBank("com.somebank.android"));
        assertTrue(BankGuard.nameLooksLikeBank("com.newfintech.upi"));
        assertTrue(BankGuard.nameLooksLikeBank("in.federalbank.app"));
    }

    @Test public void everyday_apps_are_not_banks_by_name() {
        for (String p : new String[]{"com.whatsapp", "com.google.android.youtube",
                "com.google.android.gm", "com.spotify.music", "com.android.settings"}) {
            assertFalse(p, BankGuard.nameLooksLikeBank(p));
        }
    }

    @Test public void watched_apps_are_never_treated_as_banks() {
        // browsers, social, blocked food apps, Reddit, and our own app are excluded even if a token matched
        assertTrue(BankGuard.excludedByName("com.android.chrome", OWN));
        assertTrue(BankGuard.excludedByName("com.instagram.android", OWN));
        assertTrue(BankGuard.excludedByName("com.twitter.android", OWN));
        assertTrue(BankGuard.excludedByName("in.swiggy.android", OWN));
        assertTrue(BankGuard.excludedByName("com.reddit.frontpage", OWN));
        assertTrue(BankGuard.excludedByName(OWN, OWN));
        assertTrue(BankGuard.excludedByName(null, OWN));
        assertTrue(BankGuard.excludedByName("", OWN));
    }

    @Test public void a_real_bank_is_not_excluded() {
        assertFalse(BankGuard.excludedByName("money.super.payments", OWN));
        assertFalse(BankGuard.excludedByName("com.axisbank.mobile.oneapp", OWN));
    }
}
