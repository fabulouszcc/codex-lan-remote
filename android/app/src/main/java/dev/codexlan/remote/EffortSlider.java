package dev.codexlan.remote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.SeekBar;

/** Native SeekBar semantics with the compact segmented desktop appearance. */
final class EffortSlider extends SeekBar {
    interface Listener {void preview(int index);void commit(int index);}
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Listener listener;
    private boolean cancelled;
    private int trackingStart;
    private boolean ultra;
    private boolean fast;
    private android.animation.ValueAnimator particles;
    private float phase;
    private android.graphics.LinearGradient glow;
    void ultra(boolean value){if(ultra==value)return;ultra=value;effects();invalidate();}
    void fast(boolean value){if(fast==value)return;fast=value;effects();invalidate();}
    private void effects(){
        if(!ultra||!isAttachedToWindow()||getWindowVisibility()!=VISIBLE||!android.animation.ValueAnimator.areAnimatorsEnabled()){if(particles!=null){particles.cancel();particles=null;}return;}
        if(particles!=null){particles.setDuration(fast?1400:2600);return;}
        particles=android.animation.ValueAnimator.ofFloat(0,1);particles.setDuration(fast?1400:2600);particles.setRepeatCount(android.animation.ValueAnimator.INFINITE);particles.setInterpolator(new android.view.animation.LinearInterpolator());particles.addUpdateListener(v->{phase=(float)v.getAnimatedValue();invalidate();});particles.start();
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();effects();}
    @Override protected void onDetachedFromWindow(){if(particles!=null){particles.cancel();particles=null;}super.onDetachedFromWindow();}
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);effects();}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);glow=new android.graphics.LinearGradient(0,0,Math.max(1,w),0,new int[]{Color.rgb(45,91,218),Color.rgb(165,115,250),Color.rgb(135,100,238)},null,android.graphics.Shader.TileMode.CLAMP);}
    EffortSlider(Context context,int count,int selected,Listener listener){
        super(context);this.listener=listener;setMax(Math.max(0,count-1));setProgress(selected);
        setPadding((int)dp(16),0,(int)dp(16),0);ColorDrawable thumb=new ColorDrawable(Color.TRANSPARENT);thumb.setBounds(0,0,(int)dp(32),(int)dp(32));setThumb(thumb);setThumbOffset((int)dp(16));setSplitTrack(false);
        setEnabled(count>1);setContentDescription("思考强度，左右滑动调整");
        setOnSeekBarChangeListener(new OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar bar){}
            public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){listener.preview(progress);}
            public void onStopTrackingTouch(SeekBar bar){if(!cancelled)listener.commit(getProgress());}
        });
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){cancelled=false;trackingStart=getProgress();}
        if(event.getActionMasked()==MotionEvent.ACTION_CANCEL)cancelled=true;
        boolean result=super.onTouchEvent(event);
        if(event.getActionMasked()==MotionEvent.ACTION_CANCEL)setProgress(trackingStart);
        return result;
    }
    private float dp(float value){return value*getResources().getDisplayMetrics().density;}
    @Override protected synchronized void onDraw(Canvas canvas){
        float left=getPaddingLeft(),right=getWidth()-getPaddingRight(),y=getHeight()/2f;
        float x=getMax()==0?(left+right)/2:left+(right-left)*getProgress()/getMax();
        paint.setColor(Color.rgb(70,70,70));canvas.drawRoundRect(0,y-dp(14),getWidth(),y+dp(14),dp(14),dp(14),paint);
        canvas.save();canvas.clipRect(0,0,x,getHeight());paint.setColor(Color.rgb(55,126,244));if(ultra)paint.setShader(glow);canvas.drawRoundRect(0,y-dp(14),getWidth(),y+dp(14),dp(14),dp(14),paint);paint.setShader(null);
        if(ultra){for(int i=0;i<20;i++){float u=(i*.173f+phase*(fast?1.4f:.7f))%1f;float px=dp(12)+u*(getWidth()-dp(24)),py=y+(float)Math.sin(i*17.3)*dp(7);float twinkle=.35f+.65f*(float)Math.abs(Math.sin(i*2.7+phase*Math.PI*2));paint.setColor(Color.argb((int)(210*twinkle),237,223,255));canvas.drawCircle(px,py,dp(i%5==0?1.2f:.65f),paint);}}
        canvas.restore();
        for(int i=0;i<=getMax();i++){float dot=getMax()==0?x:left+(right-left)*i/getMax();paint.setColor(i<=getProgress()?Color.rgb(133,176,248):Color.rgb(124,124,124));canvas.drawCircle(dot,y,dp(2.2f),paint);}
        paint.setColor(Color.rgb(202,202,202));canvas.drawCircle(x,y,dp(16),paint);paint.setColor(Color.WHITE);canvas.drawCircle(x,y,dp(15.4f),paint);
    }
    @Override public boolean onKeyUp(int keyCode,KeyEvent event){boolean handled=super.onKeyUp(keyCode,event);if(keyCode==KeyEvent.KEYCODE_DPAD_LEFT||keyCode==KeyEvent.KEYCODE_DPAD_RIGHT)listener.commit(getProgress());return handled;}
    @Override public boolean performAccessibilityAction(int action,Bundle arguments){int before=getProgress();boolean result=super.performAccessibilityAction(action,arguments);if(result&&getProgress()!=before)listener.commit(getProgress());return result;}
}
