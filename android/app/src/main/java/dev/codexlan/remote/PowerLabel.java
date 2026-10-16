package dev.codexlan.remote;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Color;
import android.widget.TextView;

/** A restrained moving highlight for Ultra / Fast; no animation when detached. */
final class PowerLabel extends TextView {
    private boolean ultra,fast;
    private ValueAnimator shimmer;
    private float phase;
    PowerLabel(Context context){super(context);}
    void power(boolean ultra,boolean fast){if(this.ultra==ultra&&this.fast==fast)return;this.ultra=ultra;this.fast=fast;refresh();invalidate();}
    private void refresh(){
        boolean animate=(ultra||fast)&&isAttachedToWindow()&&getWindowVisibility()==VISIBLE&&ValueAnimator.areAnimatorsEnabled();
        if(!animate){if(shimmer!=null){shimmer.cancel();shimmer=null;}getPaint().setShader(null);return;}
        if(shimmer!=null)return;
        shimmer=ValueAnimator.ofFloat(0,1);shimmer.setDuration(fast?1100:1900);shimmer.setRepeatCount(ValueAnimator.INFINITE);shimmer.setInterpolator(new android.view.animation.LinearInterpolator());shimmer.addUpdateListener(v->{phase=(float)v.getAnimatedValue();invalidate();});shimmer.start();
    }
    @Override protected void onDraw(Canvas canvas){
        if(shimmer!=null){float w=Math.max(1,getWidth()),x=(phase*3-1)*w;getPaint().setShader(new LinearGradient(x,0,x+w,0,new int[]{ultra?Color.rgb(170,137,255):Color.rgb(87,158,255),Color.WHITE,ultra?Color.rgb(170,137,255):Color.rgb(87,158,255)},null,Shader.TileMode.CLAMP));}
        super.onDraw(canvas);
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();refresh();}
    @Override protected void onDetachedFromWindow(){if(shimmer!=null){shimmer.cancel();shimmer=null;}getPaint().setShader(null);super.onDetachedFromWindow();}
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);refresh();}
}
