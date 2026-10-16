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
            case "scan": line(canvas,4,9,4,4);line(canvas,4,4,9,4);line(canvas,15,4,20,4);line(canvas,20,4,20,9);line(canvas,20,15,20,20);line(canvas,20,20,15,20);line(canvas,9,20,4,20);line(canvas,4,20,4,15);line(canvas,7,12,17,12);break;
            case "bolt-filled": Path bolt=new Path();bolt.moveTo(13,3);bolt.lineTo(5,13);bolt.lineTo(11,13);bolt.lineTo(9,21);bolt.lineTo(19,10);bolt.lineTo(13,10);bolt.close();paint.setStyle(Paint.Style.FILL);canvas.drawPath(bolt,paint);paint.setStyle(Paint.Style.STROKE);break;
            case "search": canvas.drawCircle(10.5f,10.5f,6.5f,paint);line(canvas,15.5f,15.5f,20,20);break;
            case "bolt": line(canvas,13,3,5,13); line(canvas,5,13,11,13); line(canvas,11,13,9,21); line(canvas,9,21,19,10); line(canvas,19,10,13,10); line(canvas,13,10,13,3); break;
            case "reset": canvas.drawArc(4,4,20,20,-145,310,false,paint); line(canvas,4,4,4,10); line(canvas,4,10,10,10); break;
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
