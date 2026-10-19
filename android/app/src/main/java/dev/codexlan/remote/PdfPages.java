package dev.codexlan.remote;

import android.app.Activity;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.util.concurrent.*;

/** Virtualized continuous pages. Only visible pages enter the render queue. */
final class PdfPages extends ListView implements AutoCloseable {
    private final Activity activity;
    private final int pageWidth;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final java.util.Set<Integer> pending=new java.util.HashSet<>();
    private final android.util.LruCache<Integer,Bitmap> pages=new android.util.LruCache<>(12*1024*1024){protected int sizeOf(Integer key,Bitmap bitmap){return bitmap.getByteCount();}};
    private PdfRenderer renderer;
    private ParcelFileDescriptor descriptor;
    private volatile boolean closed;
    private int count,pageHeight;
    private int quality=1,generation;
    private int[] heights;
    PdfPages(Activity activity,File file,int width,TextView status){
        super(activity);this.activity=activity;pageWidth=width;
        setDivider(new android.graphics.drawable.ColorDrawable(UiPalette.of(activity).background));setDividerHeight(Math.round(8*getResources().getDisplayMetrics().density));setSelector(android.R.color.transparent);setCacheColorHint(Color.TRANSPARENT);setVerticalScrollBarEnabled(false);
        worker.execute(()->{
            try{
                descriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);renderer=new PdfRenderer(descriptor);count=renderer.getPageCount();
                try(PdfRenderer.Page first=renderer.openPage(0)){pageHeight=Math.max(1,Math.round(pageWidth*first.getHeight()/(float)first.getWidth()));}
                heights=new int[count];java.util.Arrays.fill(heights,pageHeight);
                ui(()->{status.setVisibility(View.GONE);setAdapter(new Pages());});
            }catch(Exception e){ui(()->{status.setText("PDF 无法打开");status.setVisibility(View.VISIBLE);});release();}
        });
    }
    private void ui(Runnable runnable){activity.runOnUiThread(()->{if(!closed&&!activity.isDestroyed())runnable.run();});}
    private final class Holder extends FrameLayout {
        final ImageView image;final ProgressBar spinner;int page;
        Holder(){super(activity);setBackgroundColor(Color.WHITE);image=new ImageView(activity);image.setScaleType(ImageView.ScaleType.FIT_CENTER);addView(image,new FrameLayout.LayoutParams(-1,-1));spinner=new ProgressBar(activity);int size=Math.round(28*getResources().getDisplayMetrics().density);addView(spinner,new FrameLayout.LayoutParams(size,size,Gravity.CENTER));}
        void bind(int index){page=index;setContentDescription("第 "+(index+1)+" 页，共 "+count+" 页");Bitmap bitmap=pages.get(index);image.setImageBitmap(bitmap);spinner.setVisibility(bitmap==null?View.VISIBLE:View.GONE);if(bitmap==null)request(index);}
    }
    private final class Pages extends BaseAdapter {
        public int getCount(){return count;}
        public Object getItem(int position){return position;}
        public long getItemId(int position){return position;}
        public boolean hasStableIds(){return true;}
        public boolean isEnabled(int position){return false;}
        public boolean areAllItemsEnabled(){return false;}
        public View getView(int position,View recycled,ViewGroup parent){Holder row=recycled instanceof Holder?(Holder)recycled:new Holder();row.setLayoutParams(new AbsListView.LayoutParams(-1,heights[position]));row.bind(position);return row;}
    }
    private void request(int page){
        if(closed||!pending.add(page))return;
        int stamp=generation,resolution=quality;
        worker.execute(()->{
            if(closed||stamp!=generation)return;
            try(PdfRenderer.Page source=renderer.openPage(page)){
                float scale=Math.min(pageWidth*resolution/(float)source.getWidth(),2000f/Math.max(source.getWidth(),source.getHeight()));Bitmap bitmap=Bitmap.createBitmap(Math.max(1,Math.round(source.getWidth()*scale)),Math.max(1,Math.round(source.getHeight()*scale)),Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);source.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                int height=Math.max(1,Math.round(pageWidth*source.getHeight()/(float)source.getWidth()));
                ui(()->{if(stamp!=generation)return;pending.remove(page);heights[page]=height;pages.put(page,bitmap);for(int i=0;i<getChildCount();i++){Holder row=(Holder)getChildAt(i);if(row.page==page){if(row.getLayoutParams().height!=height){row.getLayoutParams().height=height;row.requestLayout();}row.image.setImageBitmap(bitmap);row.spinner.setVisibility(View.GONE);}}});
            }catch(Exception e){ui(()->{if(stamp!=generation)return;pending.remove(page);Toast.makeText(activity,"PDF 页面读取失败",Toast.LENGTH_SHORT).show();});}
        });
    }
    void detail(float zoom){int next=zoom>1.3f?2:1;if(next==quality||closed)return;quality=next;generation++;pages.evictAll();pending.clear();for(int i=0;i<getChildCount();i++)request(((Holder)getChildAt(i)).page);}
    private void release(){if(renderer!=null){renderer.close();renderer=null;}if(descriptor!=null){try{descriptor.close();}catch(Exception ignored){}descriptor=null;}}
    public void close(){if(closed)return;closed=true;worker.execute(this::release);worker.shutdown();pages.evictAll();}
    @Override protected void onDetachedFromWindow(){close();super.onDetachedFromWindow();}
}
