package dev.codexlan.remote;

import android.content.Context;
import android.view.*;
import android.widget.*;

/** Scale the continuous page viewport; vertical gestures still belong to ListView. */
final class PdfZoom extends FrameLayout {
    private final PdfPages pages;
    private final ScaleGestureDetector pinch;
    private final int slop;
    private float zoom=1,translation,startX,startY,lastX;
    private boolean pinching,panning;
    PdfZoom(Context context,PdfPages pages){
        super(context);this.pages=pages;setClipChildren(true);addView(pages,new FrameLayout.LayoutParams(-1,-1));pages.setPivotX(0);pages.setPivotY(0);slop=ViewConfiguration.get(context).getScaledTouchSlop();
        pinch=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            public boolean onScaleBegin(ScaleGestureDetector detector){return true;}
            public void onScaleEnd(ScaleGestureDetector detector){pages.detail(zoom);}
            public boolean onScale(ScaleGestureDetector detector){
                float previous=zoom;zoom=Math.max(1,Math.min(4,zoom*detector.getScaleFactor()));
                translation=detector.getFocusX()-(detector.getFocusX()-translation)*zoom/previous;
                pages.getLayoutParams().height=Math.max(1,Math.round(getHeight()/zoom));pages.requestLayout();pages.setScaleX(zoom);pages.setScaleY(zoom);
                pages.scrollListBy(Math.round(detector.getFocusY()*(1/previous-1/zoom)));applyPan();return true;
            }
        });
    }
    private void applyPan(){translation=Math.max(getWidth()*(1-zoom),Math.min(0,translation));pages.setTranslationX(translation);}
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        pinch.onTouchEvent(event);
        if(event.getPointerCount()>1&&!pinching){pinching=true;MotionEvent cancel=MotionEvent.obtain(event);cancel.setAction(MotionEvent.ACTION_CANCEL);super.dispatchTouchEvent(cancel);cancel.recycle();}
        if(pinching){if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){pinching=false;panning=false;}return true;}
        return super.dispatchTouchEvent(event);
    }
    @Override public boolean onInterceptTouchEvent(MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){startX=lastX=event.getX();startY=event.getY();panning=false;}
        if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&zoom>1){float dx=event.getX()-startX,dy=event.getY()-startY;if(Math.abs(dx)>slop&&Math.abs(dx)>Math.abs(dy))panning=true;}
        return panning;
    }
    @Override public boolean onTouchEvent(MotionEvent event){if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&panning){translation+=event.getX()-lastX;applyPan();}lastX=event.getX();if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL)panning=false;return true;}
}
