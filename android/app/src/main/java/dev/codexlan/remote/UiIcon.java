package dev.codexlan.remote;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Small vector controls, independent of emoji fonts and screen density. */
final class UiIcon extends Drawable {
    private final String name;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    UiIcon(String name, int color) { this.name = name; paint.setColor(color); paint.setStrokeWidth(1.8f); paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND); }
    @Override public void draw(Canvas canvas) {
        canvas.save(); canvas.translate(getBounds().left, getBounds().top); canvas.scale(getBounds().width()/24f, getBounds().height()/24f);
        switch (name) {
            case "menu": line(canvas,4,7,20,7); line(canvas,4,12,16,12); line(canvas,4,17,20,17); break;
            case "send": line(canvas,12,19,12,5); line(canvas,6,11,12,5); line(canvas,12,5,18,11); break;
            case "stop": canvas.drawRoundRect(6,6,18,18,2,2,paint); break;
            case "back": line(canvas,19,12,5,12); line(canvas,5,12,11,6); line(canvas,5,12,11,18); break;
            case "settings": canvas.drawCircle(12,12,4,paint); canvas.drawCircle(12,12,8,paint); for(int i=0;i<8;i++){double a=i*Math.PI/4; line(canvas,12+(float)Math.cos(a)*8,12+(float)Math.sin(a)*8,12+(float)Math.cos(a)*10,12+(float)Math.sin(a)*10);} break;
            default: line(canvas,9,6,15,12); line(canvas,15,12,9,18);
        }
        canvas.restore();
    }
    private void line(Canvas c,float x,float y,float a,float b){c.drawLine(x,y,a,b,paint);}
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
