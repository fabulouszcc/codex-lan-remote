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
    private ValueAnimator animator;
    private float progress;
    private int shaderWidth,shaderHeight;
    private RadialGradient glow;
    LaunchAnimationView(Context context){super(context);setBackgroundColor(Color.rgb(18,18,18));setClickable(true);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    void play(Runnable finished){
        animator=ValueAnimator.ofFloat(0f,1f);animator.setDuration(1350);animator.setInterpolator(new android.view.animation.LinearInterpolator());
        animator.addUpdateListener(a->{progress=(float)a.getAnimatedValue();invalidate();if(progress>.76f)setAlpha(Math.max(0,1-(progress-.76f)/.24f));});
        animator.addListener(new android.animation.AnimatorListenerAdapter(){@Override public void onAnimationEnd(android.animation.Animator a){finished.run();}});animator.start();
    }
    private float dp(float value){return value*getResources().getDisplayMetrics().density;}
    private static float smooth(float x){x=Math.max(0,Math.min(1,x));return x*x*(3-2*x);}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);float cx=getWidth()/2f,cy=getHeight()*.44f,t=progress;
        if(glow==null||shaderWidth!=getWidth()||shaderHeight!=getHeight()){shaderWidth=getWidth();shaderHeight=getHeight();glow=new RadialGradient(cx,cy,dp(175),new int[]{Color.argb(75,99,220,177),Color.argb(20,118,94,230),Color.TRANSPARENT},new float[]{0,.55f,1},Shader.TileMode.CLAMP);}
        float reveal=smooth(t/.3f);paint.setShader(glow);paint.setAlpha((int)(255*reveal));canvas.drawCircle(cx,cy,dp(175),paint);paint.setShader(null);
        float ring=dp(69+14*smooth(t/.8f));paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));paint.setColor(Color.argb((int)(75*reveal),177,216,202));canvas.drawCircle(cx,cy,ring,paint);
        paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeWidth(dp(2));paint.setColor(Color.argb((int)(210*reveal),174,239,210));canvas.drawArc(cx-ring,cy-ring,cx+ring,cy+ring,-100+t*210,55+45*(float)Math.sin(t*Math.PI),false,paint);
        paint.setStyle(Paint.Style.FILL);
        for(int i=0;i<20;i++){double angle=i*2.39996+t*.65;float radius=dp(84+(i%6)*14+6*(float)Math.sin(i+t*6));float opacity=reveal*(.3f+.55f*(float)Math.pow(Math.sin(i+t*2),2));paint.setColor(Color.argb((int)(150*opacity),i%3==0?173:188,i%3==0?157:235,i%3==0?245:219));canvas.drawCircle(cx+(float)Math.cos(angle)*radius,cy+(float)Math.sin(angle)*radius,dp(i%4==0?1.6f:.9f),paint);}
        canvas.save();float scale=.8f+.2f*smooth(t/.32f)+.035f*(float)Math.sin(Math.min(1,t/.4f)*Math.PI);canvas.translate(cx,cy-dp(3*smooth(t/.8f)));canvas.scale(scale,scale);
        paint.setColor(Color.rgb(31,36,34));canvas.drawRoundRect(-dp(45),-dp(45),dp(45),dp(45),dp(25),dp(25),paint);
        paint.setStyle(Paint.Style.STROKE);paint.setColor(Color.argb((int)(255*reveal),232,247,238));paint.setStrokeWidth(dp(4));paint.setStrokeJoin(Paint.Join.ROUND);
        mark.reset();mark.moveTo(-dp(16),-dp(15));mark.lineTo(-dp(30),0);mark.lineTo(-dp(16),dp(15));mark.moveTo(dp(16),-dp(15));mark.lineTo(dp(30),0);mark.lineTo(dp(16),dp(15));mark.moveTo(dp(6),-dp(23));mark.lineTo(-dp(6),dp(23));canvas.drawPath(mark,paint);canvas.restore();
        paint.setStyle(Paint.Style.FILL);paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(dp(23));paint.setColor(Color.argb((int)(240*smooth((t-.15f)/.25f)),241,247,243));canvas.drawText("Codex LAN",cx,cy+dp(119),paint);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif",0));paint.setTextSize(dp(12));paint.setColor(Color.argb((int)(160*smooth((t-.25f)/.25f)),185,204,195));canvas.drawText("让工作，随你继续",cx,cy+dp(145),paint);
    }
    @Override protected void onDetachedFromWindow(){if(animator!=null){animator.removeAllListeners();animator.cancel();animator=null;}super.onDetachedFromWindow();}
}
