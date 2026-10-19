package dev.codexlan.remote;

import android.content.Context;
import android.view.View;
import android.widget.ScrollView;

/** Stable form surface: editable controls never enter the transcript's recycle bin. */
final class InlineQuestionsView extends ScrollView {
    private final java.util.function.IntSupplier viewport;
    InlineQuestionsView(Context context,java.util.function.IntSupplier viewport){super(context);this.viewport=viewport;setFillViewport(false);setClipToPadding(false);}
    @Override protected void onMeasure(int width,int height){
        int available=viewport.getAsInt();if(available<=0)available=getResources().getDisplayMetrics().heightPixels;
        int limit=Math.max(1,Math.round(available*.45f));int mode=View.MeasureSpec.getMode(height);
        if(mode!=View.MeasureSpec.UNSPECIFIED)limit=Math.min(limit,View.MeasureSpec.getSize(height));
        super.onMeasure(width,View.MeasureSpec.makeMeasureSpec(limit,View.MeasureSpec.AT_MOST));
    }
}
