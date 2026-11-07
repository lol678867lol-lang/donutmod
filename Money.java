package de.donutmod.core;

import java.util.Locale;

/** Zahlenformat: kurz (k/m/b/t, abgeschnitten auf 1 Nachkommastelle: 3587 -> 3.5k) oder voll (3.587). */
public final class Money {
    private Money() {}

    public static String format(long v, boolean shortFmt) {
        if (!shortFmt) return String.format(Locale.GERMANY, "%,d", v);
        long a = Math.abs(v);
        String sign = v < 0 ? "-" : "";
        long unit; String suffix;
        if (a >= 1_000_000_000_000L)      { unit = 1_000_000_000_000L; suffix = "t"; }
        else if (a >= 1_000_000_000L)     { unit = 1_000_000_000L;     suffix = "b"; }
        else if (a >= 1_000_000L)         { unit = 1_000_000L;         suffix = "m"; }
        else if (a >= 1_000L)             { unit = 1_000L;             suffix = "k"; }
        else return sign + a;
        long tenths = a / (unit / 10);                 // abschneiden, nicht runden
        return sign + (tenths % 10 == 0 ? Long.toString(tenths / 10) : (tenths / 10) + "." + (tenths % 10)) + suffix;
    }
}
