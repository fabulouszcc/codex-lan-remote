package dev.codexlan.remote;

import android.content.Context;
import android.view.View;
import android.widget.ListView;

/** Preserve the reading anchor while adjustResize animates the keyboard/draft. */
final class TranscriptListView extends ListView {
    private boolean resizing,followBottom;
    private int anchor,anchorTop;
    Runnable onViewportSettled;
    TranscriptListView(Context context){super(context);}
    boolean followingResize(){return resizing&&followBottom;}
    private final Runnable settle=()->{resizing=false;if(onViewportSettled!=null)onViewportSettled.run();};
    private final Runnable restore=()->{
        if(!resizing||getAdapter()==null)return;
        int count=getAdapter().getCount();
        if(count>0){if(followBottom)setSelection(count-1);else setSelectionFromTop(Math.min(anchor,count-1),anchorTop-getPaddingTop());}
        removeCallbacks(settle);postDelayed(settle,100);
    };
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){
        if(oldh>0&&h!=oldh&&getAdapter()!=null){
            if(!resizing){
                int count=getAdapter().getCount();View last=getChildAt(getChildCount()-1),first=getChildAt(0);
                followBottom=MobileState.viewportAtBottom(count,getLastVisiblePosition(),last==null?0:last.getBottom(),oldh,getPaddingBottom(),Math.round(48*getResources().getDisplayMetrics().density));
                anchor=getFirstVisiblePosition();anchorTop=first==null?getPaddingTop():first.getTop();
            }
            resizing=true;removeCallbacks(restore);removeCallbacks(settle);post(restore);
        }
        super.onSizeChanged(w,h,oldw,oldh);
    }
    @Override protected void onDetachedFromWindow(){removeCallbacks(restore);removeCallbacks(settle);resizing=false;super.onDetachedFromWindow();}
}
