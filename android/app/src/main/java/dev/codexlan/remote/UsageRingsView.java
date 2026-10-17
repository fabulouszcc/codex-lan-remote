package dev.codexlan.remote;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.view.View;
import org.json.JSONObject;

/** Two compact activity-style rings; only animate when a quota value changes. */
final class UsageRingsView extends View {
    static final int OUTER=Color.rgb(48,209,88),INNER=Color.rgb(10,132,255);
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds=new RectF();
    private float outer=-1,inner=-1,targetOuter=-1,targetInner=-1;
    private boolean subdued;
    private ValueAnimator animator;
    UsageRingsView(Context context){super(context);setFocusable(true);setClickable(true);paint.setStrokeCap(Paint.Cap.ROUND);setContentDescription("查看剩余额度");}
    void quota(JSONObject value,boolean online){
        float a=UsagePresentation.remaining(value==null?null:value.optJSONObject("primary"));
        float b=UsagePresentation.remaining(value==null?null:value.optJSONObject("secondary"));
        boolean dim=!online||value==null||value.optBoolean("stale");
        String label="额度剩余，外环 "+UsagePresentation.period(value==null?null:value.optJSONObject("primary"),"短周期")+" "+UsagePresentation.percent(value==null?null:value.optJSONObject("primary"))+"，内环 "+UsagePresentation.period(value==null?null:value.optJSONObject("secondary"),"长周期")+" "+UsagePresentation.percent(value==null?null:value.optJSONObject("secondary"));
        setContentDescription(label+(dim?"，非实时数据":"")+"，点按查看详情");
        if(a==targetOuter&&b==targetInner){if(subdued!=dim){subdued=dim;invalidate();}return;}
        subdued=dim;targetOuter=a;targetInner=b;if(animator!=null)animator.cancel();
        if(!isAttachedToWindow()||!ValueAnimator.areAnimatorsEnabled()){outer=a;inner=b;invalidate();return;}
        final float fromA=outer<0?0:outer,fromB=inner<0?0:inner;
        animator=ValueAnimator.ofFloat(0,1);animator.setDuration(420);animator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        animator.addUpdateListener(v->{float t=(float)v.getAnimatedValue();outer=a<0?-1:fromA+(a-fromA)*t;inner=b<0?-1:fromB+(b-fromB)*t;invalidate();});animator.start();
    }
    private void ring(Canvas canvas,float radius,float stroke,float value,int color){
        float cx=getWidth()/2f,cy=getHeight()/2f;
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(stroke);paint.setShader(null);
        paint.setColor(value<0?Color.rgb(110,110,115):color);paint.setAlpha(value<0?85:subdued?25:42);canvas.drawCircle(cx,cy,radius,paint);
        if(value<0)return;
        bounds.set(cx-radius,cy-radius,cx+radius,cy+radius);paint.setAlpha(subdued?110:255);
        if(value>0)canvas.drawArc(bounds,-90,Math.min(360,value*3.6f),false,paint);
    }
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float d=getResources().getDisplayMetrics().density;float radius=Math.min(getWidth(),getHeight())/2f-7*d;
        ring(canvas,radius,3.5f*d,outer,OUTER);ring(canvas,radius-6*d,3.5f*d,inner,INNER);
        if(outer<0&&inner<0){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.5f*d);paint.setColor(Color.GRAY);paint.setAlpha(180);canvas.drawLine(getWidth()/2f-2*d,getHeight()/2f,getWidth()/2f+2*d,getHeight()/2f,paint);}
    }
    @Override protected void onDetachedFromWindow(){if(animator!=null)animator.cancel();super.onDetachedFromWindow();}
}
