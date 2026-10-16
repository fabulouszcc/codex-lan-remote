package dev.codexlan.remote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import java.util.Collections;
import java.util.List;

/** One lightweight touch surface; moving the finger never scrolls the transcript. */
final class ConversationRail extends View {
    static final class Entry {
        final String key,prompt,response;
        final int position;
        Entry(String key,String prompt,String response,int position){this.key=key;this.prompt=prompt;this.response=response;this.position=position;}
    }
    interface Listener {
        void preview(Entry entry,int index,int count,float y);
        void commit(Entry entry);
        void end();
    }
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<Entry> entries=Collections.emptyList();
    private final Listener listener;
    private int active=-1,hover=-1,edge;
    private boolean tracking;
    private final RailWindow window=new RailWindow();
    private float drawnStart,drawnFocus,highlight,fingerY,readingFocus;
    private float startX;
    private long frameTime;
    private final Runnable page=()->{
        if(!tracking||edge==0)return;
        if(window.step(edge)){select(fingerY);postInvalidateOnAnimation();postDelayed(this.page,110);}
    };
    ConversationRail(Context context,Listener listener){
        super(context);this.listener=listener;setClickable(true);
        setContentDescription("对话记录时间线，上下滑动预览，停留在上下边缘自动翻页，松手定位，左滑取消快速定位。双击打开全部记录。");
    }
    void entries(List<Entry> next){
        String key=tracking&&hover>=0&&hover<entries.size()?entries.get(hover).key:null;
        entries=next;hover=-1;
        if(key!=null){for(int i=0;i<next.size();i++)if(key.equals(next.get(i).key)){hover=i;break;}if(hover<0)cancel();}
        configure(true);invalidate();
    }
    void activePosition(float first){
        int next=entries.isEmpty()?-1:0;for(int i=0;i<entries.size();i++){if(entries.get(i).position>first)break;next=i;}
        float focus=Math.max(0,next);
        if(next>=0&&next+1<entries.size())focus+=RailWindow.progress(first,entries.get(next).position,entries.get(next+1).position);
        if(active!=next||readingFocus!=focus){active=next;readingFocus=focus;if(!tracking)configure(false);postInvalidateOnAnimation();}
    }
    private float dp(float value){return value*getResources().getDisplayMetrics().density;}
    private float spacing(){return dp(12);}
    private float top(){return (getHeight()-Math.max(0,window.capacity-1)*spacing())/2f;}
    private float bottom(){return top()+Math.max(0,window.capacity-1)*spacing();}
    private void configure(boolean preserve){
        int capacity=Math.max(1,Math.min(25,1+(int)((getHeight()*.72f-dp(32))/spacing())));
        boolean changed=window.capacity!=Math.min(entries.size(),capacity);
        window.configure(entries.size(),capacity,Math.max(0,active),preserve&&!changed);
        if(changed||frameTime==0){drawnStart=window.start;drawnFocus=Math.max(0,active);}
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);if(tracking)cancel();configure(false);drawnStart=window.start;}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);if(entries.isEmpty())return;
        long now=android.os.SystemClock.uptimeMillis();float elapsed=frameTime==0?16:Math.min(32,now-frameTime);frameTime=now;
        float mix=android.animation.ValueAnimator.areAnimatorsEnabled()?(float)(1-Math.exp(-elapsed/55f)):1;
        float targetFocus=tracking?hover:readingFocus,targetHighlight=tracking?1:0;
        float targetStart=tracking?window.start:window.centeredStart(readingFocus);
        drawnStart+=(targetStart-drawnStart)*mix;drawnFocus+=(targetFocus-drawnFocus)*mix;highlight+=(targetHighlight-highlight)*mix;
        if(Math.abs(targetStart-drawnStart)<.01f)drawnStart=targetStart;
        if(Math.abs(targetFocus-drawnFocus)<.01f)drawnFocus=targetFocus;
        if(Math.abs(targetHighlight-highlight)<.01f)highlight=targetHighlight;
        canvas.save();canvas.clipRect(0,top()-dp(6),getWidth(),bottom()+dp(6));paint.setStrokeCap(Paint.Cap.BUTT);
        int first=Math.max(0,(int)Math.floor(drawnStart)-1),last=Math.min(entries.size()-1,(int)Math.ceil(drawnStart)+window.capacity);
        for(int i=first;i<=last;i++){
            float focus=RailWindow.emphasis(i-drawnFocus)*highlight;
            float half=dp(3.4f+7.6f*focus);int shade=(int)(76+179*focus);
            float reading=Math.max(0,1-Math.abs(i-drawnFocus))*(1-highlight);
            half=Math.max(half,dp(3.4f+5.6f*reading));shade=Math.max(shade,(int)(76+117*reading));
            if(tracking&&i==active){half=Math.max(half,dp(5));shade=Math.max(shade,130);}
            paint.setColor(Color.rgb(shade,shade,shade));paint.setStrokeWidth(dp(1.2f+.6f*focus));
            float y=top()+(i-drawnStart)*spacing();canvas.drawLine(getWidth()/2f-half,y,getWidth()/2f+half,y,paint);
        }
        canvas.restore();
        if(drawnStart!=targetStart||drawnFocus!=targetFocus||highlight!=targetHighlight)postInvalidateOnAnimation();
    }
    private void select(float y){
        fingerY=y;hover=window.at(y,top(),bottom());
        if(hover>=0&&hover<entries.size())listener.preview(entries.get(hover),hover,entries.size(),Math.max(top(),Math.min(bottom(),y)));
        postInvalidateOnAnimation();
    }
    private void preview(float y){
        select(y);int next=y<top()+dp(15)?-1:y>bottom()-dp(15)?1:0;
        if(next!=edge){removeCallbacks(page);edge=next;if(edge!=0)postDelayed(page,320);}
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        if(!isEnabled()||entries.isEmpty())return false;
        // TalkBack retains a standard click action for the full record list.
        android.view.accessibility.AccessibilityManager accessibility=(android.view.accessibility.AccessibilityManager)getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        if(accessibility!=null&&accessibility.isTouchExplorationEnabled())return super.onTouchEvent(event);
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                startX=event.getX();tracking=true;drawnFocus=window.at(event.getY(),top(),bottom());getParent().requestDisallowInterceptTouchEvent(true);preview(event.getY());return true;
            case MotionEvent.ACTION_MOVE:
                if(tracking&&NavigationPosition.cancelLeft(startX,event.getX(),dp(56))){cancel();configure(false);postInvalidateOnAnimation();return true;}
                if(tracking)preview(event.getY());return true;
            case MotionEvent.ACTION_UP:
                if(tracking&&NavigationPosition.cancelLeft(startX,event.getX(),dp(56))){cancel();configure(false);return true;}
                if(tracking){select(event.getY());Entry selected=hover<0?null:entries.get(hover);cancel();if(selected!=null)listener.commit(selected);}return true;
            case MotionEvent.ACTION_CANCEL:cancel();return true;
            case MotionEvent.ACTION_POINTER_DOWN:cancel();return true;
            default:return true;
        }
    }
    void cancel(){removeCallbacks(page);edge=0;tracking=false;hover=-1;listener.end();postInvalidateOnAnimation();if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);}
    @Override protected void onDetachedFromWindow(){cancel();super.onDetachedFromWindow();}
}
