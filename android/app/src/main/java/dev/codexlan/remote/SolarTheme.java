package dev.codexlan.remote;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/** Local solar position, using NOAA's fractional-year equations.
 * https://gml.noaa.gov/grad/solcalc/solareqns.PDF */
final class SolarTheme {
    static boolean night(long time, double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || Math.abs(latitude)>90 || Math.abs(longitude)>180) throw new IllegalArgumentException("Invalid coordinates");
        ZonedDateTime utc=Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC);
        double hour=utc.getHour()+utc.getMinute()/60.0+utc.getSecond()/3600.0;
        double year=2*Math.PI/utc.toLocalDate().lengthOfYear()*(utc.getDayOfYear()-1+(hour-12)/24);
        double equation=229.18*(.000075+.001868*Math.cos(year)-.032077*Math.sin(year)-.014615*Math.cos(2*year)-.040849*Math.sin(2*year));
        double declination=.006918-.399912*Math.cos(year)+.070257*Math.sin(year)-.006758*Math.cos(2*year)+.000907*Math.sin(2*year)-.002697*Math.cos(3*year)+.00148*Math.sin(3*year);
        double solar=((hour*60+equation+4*longitude)%1440+1440)%1440;
        double angle=Math.toRadians(solar/4-180), lat=Math.toRadians(latitude);
        double zenith=Math.sin(lat)*Math.sin(declination)+Math.cos(lat)*Math.cos(declination)*Math.cos(angle);
        return zenith<Math.cos(Math.toRadians(90.833));
    }
    static boolean dark(String mode, boolean automatic, boolean hasCity, double latitude, double longitude, long time) {
        return automatic&&hasCity ? night(time,latitude,longitude) : !"light".equals(mode);
    }
}
