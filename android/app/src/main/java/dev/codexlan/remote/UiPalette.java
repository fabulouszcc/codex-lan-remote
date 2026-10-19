package dev.codexlan.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

final class UiPalette {
    private static final java.util.WeakHashMap<Context,UiPalette> palettes=new java.util.WeakHashMap<>();
    static synchronized UiPalette of(Context context){UiPalette value=palettes.get(context);if(value==null){value=new UiPalette(context);palettes.put(context,value);}return value;}
    final boolean dark;
    final int background, foreground, muted, accent;
    UiPalette(Context context) {
        SharedPreferences p=context.getSharedPreferences("display",Context.MODE_PRIVATE);
        dark=SolarTheme.dark(p.getString("mode","dark"),p.getBoolean("solar",false),p.contains("latitude"),
            Double.longBitsToDouble(p.getLong("latitude",0)),Double.longBitsToDouble(p.getLong("longitude",0)),System.currentTimeMillis());
        background=dark?Color.rgb(18,18,18):Color.rgb(250,250,250);
        foreground=dark?Color.rgb(242,242,242):Color.rgb(29,31,33);
        muted=dark?Color.rgb(157,157,157):Color.rgb(97,101,107);
        accent=dark?Color.rgb(220,233,225):Color.rgb(36,88,64);
    }
    static int color(Context context,int original) {
        if(of(context).dark)return original;
        int r=Color.red(original),g=Color.green(original),b=Color.blue(original);
        if(Math.max(r,Math.max(g,b))<=90) {
            int spread=Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b));
            if(spread<5){int light=Math.min(250,268-(r+g+b)/3);return Color.rgb(light,light,light);}
            return Color.rgb(238,244,240);
        }
        return original;
    }
}
