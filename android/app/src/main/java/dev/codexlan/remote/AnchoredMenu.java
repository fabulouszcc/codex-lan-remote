package dev.codexlan.remote;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.Gravity;
import android.widget.PopupWindow;

/** Shared floating menus grow from the control which opened them. */
final class AnchoredMenu {
    static PopupWindow below(Activity activity,View anchor,View content,int desiredWidth){
        float density=activity.getResources().getDisplayMetrics().density;Rect frame=new Rect();anchor.getWindowVisibleDisplayFrame(frame);
        int width=Math.min(desiredWidth,frame.width()-Math.round(24*density));
        PopupWindow popup=new PopupWindow(content,width,-2,true);popup.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));popup.setElevation(12*density);popup.setOutsideTouchable(true);popup.setOverlapAnchor(false);popup.setAnimationStyle(0);popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        // Let PopupWindow convert the dialog's window coordinates itself.
        popup.showAsDropDown(anchor,0,Math.round(8*density),Gravity.START);
        if(android.animation.ValueAnimator.areAnimatorsEnabled()){content.setPivotX(0);content.setPivotY(0);content.setAlpha(0);content.setScaleX(.94f);content.setScaleY(.94f);content.animate().alpha(1).scaleX(1).scaleY(1).setDuration(160).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();}
        return popup;
    }
    static void resize(Activity activity,View anchor,View content,PopupWindow popup){
        if(popup==null||!popup.isShowing())return;
        int margin=Math.round(12*activity.getResources().getDisplayMetrics().density);Rect frame=new Rect();activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(frame);
        content.measure(View.MeasureSpec.makeMeasureSpec(popup.getWidth(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(Math.max(1,frame.height()-2*margin),View.MeasureSpec.AT_MOST));
        int height=content.getMeasuredHeight();int[] point=new int[2];anchor.getLocationOnScreen(point);int left=Math.max(frame.left+margin,Math.min(point[0],frame.right-popup.getWidth()-margin));int below=point[1]+anchor.getHeight()+margin;
        int top=Math.max(frame.top+margin,Math.min(below+height>frame.bottom-margin?point[1]-height-margin:below,frame.bottom-height-margin));popup.update(left,top,popup.getWidth(),height);
    }
    static PopupWindow show(Activity activity,View anchor,View content,int desiredWidth,boolean input){
        float density=activity.getResources().getDisplayMetrics().density;int margin=Math.round(12*density),gap=Math.round(8*density);
        View decor=activity.getWindow().getDecorView();Rect frame=new Rect();decor.getWindowVisibleDisplayFrame(frame);
        int width=Math.min(desiredWidth,frame.width()-margin*2),maxHeight=Math.max(1,frame.height()-margin*2);
        content.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(maxHeight,View.MeasureSpec.AT_MOST));
        int height=Math.min(content.getMeasuredHeight(),maxHeight);int[] point=new int[2];anchor.getLocationOnScreen(point);
        int left=Math.max(frame.left+margin,Math.min(point[0],frame.right-width-margin));
        int below=point[1]+anchor.getHeight()+gap;boolean above=below+height>frame.bottom-margin;
        int top=Math.max(frame.top+margin,Math.min(above?point[1]-height-gap:below,frame.bottom-height-margin));
        PopupWindow popup=new PopupWindow(content,width,height,true);popup.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));popup.setElevation(12*density);popup.setOutsideTouchable(true);popup.setAnimationStyle(0);
        popup.setInputMethodMode(input?PopupWindow.INPUT_METHOD_NEEDED:PopupWindow.INPUT_METHOD_NOT_NEEDED);popup.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        popup.showAtLocation(anchor,Gravity.TOP|Gravity.LEFT,left,top);content.setPivotX(Math.max(0,Math.min(width,point[0]+anchor.getWidth()/2f-left)));content.setPivotY(Math.max(0,Math.min(height,point[1]+anchor.getHeight()/2f-top)));
        if(android.animation.ValueAnimator.areAnimatorsEnabled()){content.setAlpha(0);content.setScaleX(.82f);content.setScaleY(.82f);content.animate().alpha(1).scaleX(1).scaleY(1).setDuration(200).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();}
        return popup;
    }
}
