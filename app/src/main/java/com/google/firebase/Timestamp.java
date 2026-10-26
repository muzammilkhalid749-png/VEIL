package com.google.firebase;

import java.io.Serializable;
import java.util.Date;

public class Timestamp implements Comparable<Timestamp>, Serializable {
    private final long seconds;
    private final int nanoseconds;

    public Timestamp(long seconds, int nanoseconds) {
        this.seconds = seconds;
        this.nanoseconds = nanoseconds;
    }

    public Timestamp(Date date) {
        long millis = date != null ? date.getTime() : System.currentTimeMillis();
        this.seconds = millis / 1000;
        this.nanoseconds = (int) ((millis % 1000) * 1000000);
    }

    public static Timestamp now() {
        return new Timestamp(new Date());
    }

    public long getSeconds() {
        return seconds;
    }

    public int getNanoseconds() {
        return nanoseconds;
    }

    public Date toDate() {
        return new Date(seconds * 1000 + nanoseconds / 1000000);
    }

    @Override
    public int compareTo(Timestamp other) {
        if (other == null) return 1;
        if (seconds != other.seconds) return Long.compare(seconds, other.seconds);
        return Integer.compare(nanoseconds, other.nanoseconds);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Timestamp)) return false;
        Timestamp that = (Timestamp) o;
        return seconds == that.seconds && nanoseconds == that.nanoseconds;
    }

    @Override
    public int hashCode() {
        return (int) (seconds ^ (seconds >>> 32)) * 31 + nanoseconds;
    }

    @Override
    public String toString() {
        return "Timestamp(seconds=" + seconds + ", nanoseconds=" + nanoseconds + ")";
    }
}
