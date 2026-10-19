package dev.codexlan.remote;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/** Lightweight vector reveal: one animator, no bitmap decode or layout per frame. */
final class LaunchAnimationView extends View {
    private final Paint paint=new Paint(3);
    private final Path mark=new Path();
    private final UiPalette palette;
    private ValueAnimator animator;
    private float progress;
    private int shaderWidth,shaderHeight;
    private RadialGradient glow;
    LaunchAnimationView(Context context){super(context);palette=UiPalette.of(context);setBackgroundColor(palette.background);setClickable(true);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    void play(Runnable finished){
        animator=ValueAnimator.ofFloat(0f,1f);animator.setDuration(1350);animator.setInterpolator(new android.view.animation.LinearInterpolator());
        animator.addUpdateListener(a->{progress=(float)a.getAnimatedValue();invalidate();if(progress>.76f)setAlpha(Math.max(0,1-(progress-.76f)/.24f));});
        animator.addListener(new android.animation.AnimatorListenerAdapter(){@Override public void onAnimationEnd(android.animation.Animator a){finished.run();}});animator.start();
    }
    private float dp(float value){return value*getResources().getDisplayMetrics().density;}
    private static float smooth(float x){x=Math.max(0,Math.min(1,x));return x*x*(3-2*x);}
    private int tone(int dark,int light){return palette.dark?dark:light;}
    private void ink(int color,float opacity){paint.setColor(color);paint.setAlpha(Math.round(Color.alpha(color)*Math.max(0,Math.min(1,opacity))));}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);float cx=getWidth()/2f,cy=getHeight()*.44f,t=progress;
        if(glow==null||shaderWidth!=getWidth()||shaderHeight!=getHeight()){shaderWidth=getWidth();shaderHeight=getHeight();glow=new RadialGradient(cx,cy,dp(175),new int[]{tone(Color.argb(75,99,220,177),Color.argb(42,64,154,112)),tone(Color.argb(20,118,94,230),Color.argb(16,118,94,180)),Color.TRANSPARENT},new float[]{0,.55f,1},Shader.TileMode.CLAMP);}
        float reveal=smooth(t/.3f);paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);paint.setShader(glow);paint.setAlpha((int)(255*reveal));canvas.drawCircle(cx,cy,dp(175),paint);paint.setShader(null);
        float ring=dp(69+14*smooth(t/.8f));paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));ink(tone(Color.argb(75,177,216,202),Color.argb(90,59,119,91)),reveal);canvas.drawCircle(cx,cy,ring,paint);
        paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeWidth(dp(2));ink(tone(Color.argb(210,174,239,210),Color.rgb(48,120,84)),reveal);canvas.drawArc(cx-ring,cy-ring,cx+ring,cy+ring,-100+t*210,55+45*(float)Math.sin(t*Math.PI),false,paint);
        paint.setStyle(Paint.Style.FILL);
        for(int i=0;i<20;i++){double angle=i*2.39996+t*.65;float radius=dp(84+(i%6)*14+6*(float)Math.sin(i+t*6));float opacity=reveal*(.3f+.55f*(float)Math.pow(Math.sin(i+t*2),2));ink(i%3==0?tone(Color.argb(150,173,157,245),Color.argb(170,112,85,158)):tone(Color.argb(150,188,235,219),Color.argb(170,47,118,84)),opacity);canvas.drawCircle(cx+(float)Math.cos(angle)*radius,cy+(float)Math.sin(angle)*radius,dp(i%4==0?1.6f:.9f),paint);}
        canvas.save();float scale=.8f+.2f*smooth(t/.32f)+.035f*(float)Math.sin(Math.min(1,t/.4f)*Math.PI);canvas.translate(cx,cy-dp(3*smooth(t/.8f)));canvas.scale(scale,scale);
        ink(tone(Color.rgb(31,36,34),Color.rgb(229,240,233)),reveal);canvas.drawRoundRect(-dp(45),-dp(45),dp(45),dp(45),dp(25),dp(25),paint);
        paint.setStyle(Paint.Style.STROKE);ink(tone(Color.rgb(232,247,238),Color.rgb(36,88,64)),reveal);paint.setStrokeWidth(dp(4));paint.setStrokeJoin(Paint.Join.ROUND);
        mark.reset();mark.moveTo(-dp(16),-dp(15));mark.lineTo(-dp(30),0);mark.lineTo(-dp(16),dp(15));mark.moveTo(dp(16),-dp(15));mark.lineTo(dp(30),0);mark.lineTo(dp(16),dp(15));mark.moveTo(dp(6),-dp(23));mark.lineTo(-dp(6),dp(23));canvas.drawPath(mark,paint);canvas.restore();
        paint.setStyle(Paint.Style.FILL);paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(dp(23));ink(palette.foreground,smooth((t-.1f)/.22f));canvas.drawText("Codex LAN",cx,cy+dp(119),paint);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif",0));paint.setTextSize(dp(12));ink(palette.muted,smooth((t-.16f)/.22f));canvas.drawText("让工作，随你继续",cx,cy+dp(145),paint);
    }
    @Override protected void onDetachedFromWindow(){if(animator!=null){animator.removeAllListeners();animator.cancel();animator=null;}super.onDetachedFromWindow();}
}
