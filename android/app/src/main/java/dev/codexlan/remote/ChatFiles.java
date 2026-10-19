package dev.codexlan.remote;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Lazy native previews; file traffic has its own pool and cancellation channel. */
final class ChatFiles {
    private final Activity activity;
    private final ExecutorService worker=Executors.newFixedThreadPool(2);
    private final java.util.Set<RemoteApi> channels=ConcurrentHashMap.newKeySet();
    private final java.util.Map<String,CompletableFuture<File>> loading=new ConcurrentHashMap<>();
    private boolean closed;
    ChatFiles(Activity activity){this.activity=activity;}
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private TextView label(String value,int size){TextView text=new TextView(activity);text.setText(value);text.setTextSize(size);text.setTextColor(UiPalette.of(activity).foreground);return text;}
    private void ui(Runnable runnable){activity.runOnUiThread(()->{if(!closed&&!activity.isDestroyed())runnable.run();});}
    private void error(Throwable error){ui(()->Toast.makeText(activity,error.getCause()!=null?error.getCause().getMessage():error.getMessage(),Toast.LENGTH_LONG).show());}
    void add(LinearLayout parent,JSONArray files,String chat,RemoteApi api){
        if(files==null||api==null)return;
        for(int i=0;i<files.length();i++){
            JSONObject file=files.optJSONObject(i);if(file==null)continue;
            UiPalette palette=UiPalette.of(activity);
            LinearLayout card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(14),dp(12),dp(14),dp(12));
            android.graphics.drawable.GradientDrawable shape=new android.graphics.drawable.GradientDrawable();shape.setColor(palette.dark?Color.rgb(36,36,36):Color.rgb(244,246,245));shape.setCornerRadius(dp(18));shape.setStroke(dp(1),palette.dark?Color.rgb(55,55,55):Color.rgb(222,227,224));
            card.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(palette.dark?0x22ffffff:0x16000000),shape,null));
            String name=file.optString("name","文件"),kind=file.optString("kind");boolean picture="image".equals(kind);
            LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);card.addView(row);
            ImageView icon=new ImageView(activity);icon.setImageDrawable(new UiIcon(picture?"image":"file",palette.accent));icon.setPadding(dp(10),dp(10),dp(10),dp(10));android.graphics.drawable.GradientDrawable tile=new android.graphics.drawable.GradientDrawable();tile.setColor(palette.dark?Color.rgb(47,53,49):Color.rgb(229,238,232));tile.setCornerRadius(dp(12));icon.setBackground(tile);row.addView(icon,new LinearLayout.LayoutParams(dp(44),dp(44)));
            LinearLayout labels=new LinearLayout(activity);labels.setOrientation(LinearLayout.VERTICAL);LinearLayout.LayoutParams labelSpace=new LinearLayout.LayoutParams(0,-2,1);labelSpace.leftMargin=dp(12);labelSpace.rightMargin=dp(10);row.addView(labels,labelSpace);
            String displayName=picture&&name.matches("(?i)codex-clipboard-[a-f0-9-]+\\.(?:png|jpe?g|webp)")?"图片 "+(i+1):name;
            TextView title=label(displayName,14);title.setTypeface(null,Typeface.BOLD);title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);labels.addView(title);
            String extension=name.contains(".")?name.substring(name.lastIndexOf('.')+1).toUpperCase(java.util.Locale.ROOT):"文件";
            String prompt=extension+" · "+(picture?"点击加载图片":"点击查看文件");
            TextView hint=label(prompt,12);hint.setTextColor(palette.muted);hint.setPadding(0,dp(4),0,0);labels.addView(hint);
            ImageView arrow=new ImageView(activity);arrow.setImageDrawable(new UiIcon("next",palette.muted));row.addView(arrow,new LinearLayout.LayoutParams(dp(16),dp(16)));
            card.setContentDescription((picture?"加载图片：":"查看文件：")+name);
            card.setOnClickListener(v->{card.setEnabled(false);hint.setText("正在读取…");load(api,chat,file).thenAcceptAsync(local->ui(()->{card.setEnabled(true);hint.setText(prompt);preview(local,file);}),worker).exceptionally(e->{ui(()->{card.setEnabled(true);hint.setText("读取失败 · 点击重试");});error(e);return null;});});
            if(!file.optString("diff").isEmpty()){TextView diff=label("查看变更",13);diff.setTextColor(palette.accent);diff.setGravity(Gravity.CENTER_VERTICAL|Gravity.END);diff.setMinHeight(dp(44));diff.setOnClickListener(v->text(name+" · 变更",file.optString("diff"),null));card.addView(diff);}
            LinearLayout.LayoutParams space=new LinearLayout.LayoutParams(-1,-2);space.topMargin=dp(8);space.bottomMargin=dp(4);parent.addView(card,space);
        }
    }
    private CompletableFuture<File> load(RemoteApi api,String chat,JSONObject ref){
        String name=ref.optString("name","file").replaceAll("[^\\p{L}\\p{N}._ -]","_");if(name.length()>120)name=name.substring(name.length()-120);
        String key=api.fingerprint+":"+chat+":"+ref.optString("id");final String filename=hash(key)+"-"+name;
        return loading.computeIfAbsent(key,k->{CompletableFuture<File> future=CompletableFuture.supplyAsync(()->{
            RemoteApi channel=api.readChannel();channels.add(channel);
            try{
                File root=new File(activity.getCacheDir(),"previews");if(!root.isDirectory()&&!root.mkdirs())throw new IOException("无法建立文件缓存");
                File local=new File(root,filename);if(local.isFile()&&System.currentTimeMillis()-local.lastModified()<60000)return local;
                byte[] bytes=channel.file(chat,ref.optString("id"));File temporary=File.createTempFile("preview-",".tmp",root);
                try{try(FileOutputStream out=new FileOutputStream(temporary)){out.write(bytes);}if(!temporary.renameTo(local))throw new IOException("保存预览失败");}finally{temporary.delete();}
                File[] old=root.listFiles();if(old!=null){java.util.Arrays.sort(old,java.util.Comparator.comparingLong(File::lastModified).reversed());long total=0;for(int i=0;i<old.length;i++){total+=old[i].length();if(!old[i].equals(local)&&(i>=48||total>96L*1024*1024))old[i].delete();}}
                return local;
            }catch(Exception e){throw new CompletionException(e);}finally{channels.remove(channel);channel.cancel();}
        },worker);future.whenComplete((value,error)->ui(()->loading.remove(k,future)));return future;});
    }
    private static String hash(String key){try{byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    private Bitmap decode(File file,int edge){BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),options);if(options.outWidth<1||options.outHeight<1)return null;options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>edge)options.inSampleSize*=2;options.inJustDecodeBounds=false;return BitmapFactory.decodeFile(file.getPath(),options);}
    private void preview(File local,JSONObject ref){
        String kind=ref.optString("kind"),name=ref.optString("name");
        if("image".equals(kind)){worker.execute(()->{try{Bitmap bitmap=decode(local,2400);if(bitmap==null)throw new IOException("图片格式暂不支持");ui(()->imagePreview(local,name,bitmap));}catch(Exception e){error(e);}});}
        else if("text".equals(kind))worker.execute(()->{try{String value;try(InputStream in=new FileInputStream(local)){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] block=new byte[8192];int n;while(bytes.size()<1024*1024&&(n=in.read(block,0,Math.min(block.length,1024*1024-bytes.size())))!=-1)bytes.write(block,0,n);value=bytes.toString("UTF-8")+(local.length()>1024*1024?"\n\n…预览前 1 MB，可用其他应用查看完整文件":"");}final String content=value;ui(()->text(name,content,local));}catch(Exception e){error(e);}});
        else if(name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf"))pdf(local,name);
        else new AlertDialog.Builder(activity).setTitle(name).setMessage("文件已读取到手机，可使用支持此格式的应用查看。").setPositiveButton("其他应用打开",(d,w)->external(local)).setNegativeButton("关闭",null).show();
    }
    private void imagePreview(File local,String name,Bitmap bitmap){
        int width=previewWidth();int height=Math.min((int)(activity.getResources().getDisplayMetrics().heightPixels*.72f),Math.max(dp(100),Math.round(width*bitmap.getHeight()/(float)bitmap.getWidth())));
        ZoomImage image=new ZoomImage(activity,bitmap);image.setMinimumHeight(0);image.setContentDescription("图片，双指缩放，双击放大");previewCard(local,image,width,height);
    }
    private int previewWidth(){return Math.min(dp(420),activity.getResources().getDisplayMetrics().widthPixels-dp(32));}
    private void previewCard(File local,View content,int width,int height){
        UiPalette palette=UiPalette.of(activity);Dialog dialog=new Dialog(activity);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        FrameLayout card=new FrameLayout(activity);android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();background.setColor(palette.dark?Color.rgb(35,35,35):Color.rgb(250,251,250));background.setCornerRadius(dp(24));card.setBackground(background);card.setClipToOutline(true);card.addView(content,new FrameLayout.LayoutParams(-1,-1));
        ImageButton close=overlay("close","关闭预览");FrameLayout.LayoutParams closeSpace=new FrameLayout.LayoutParams(dp(36),dp(36),Gravity.TOP|Gravity.END);closeSpace.setMargins(dp(8),dp(8),dp(8),dp(8));card.addView(close,closeSpace);close.setOnClickListener(v->dialog.dismiss());
        ImageButton more=overlay("more","预览选项");FrameLayout.LayoutParams moreSpace=new FrameLayout.LayoutParams(dp(36),dp(36),Gravity.TOP|Gravity.START);moreSpace.setMargins(dp(8),dp(8),dp(8),dp(8));card.addView(more,moreSpace);more.setOnClickListener(v->previewMenu(local,more));
        dialog.setContentView(card);android.view.Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));window.setGravity(Gravity.CENTER);window.setDimAmount(.45f);}dialog.show();if(window!=null)window.setLayout(width,height);
    }
    private ImageButton overlay(String icon,String description){ImageButton button=new ImageButton(activity);button.setImageDrawable(new UiIcon(icon,Color.WHITE));button.setPadding(dp(9),dp(9),dp(9),dp(9));android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();background.setColor(0x99000000);background.setShape(android.graphics.drawable.GradientDrawable.OVAL);button.setBackground(background);button.setContentDescription(description);return button;}
    private void text(String name,String value,File local){TextView content=label(value,13);content.setTypeface(Typeface.MONOSPACE);content.setTextIsSelectable(true);content.setPadding(dp(16),dp(12),dp(16),dp(12));HorizontalScrollView horizontal=new HorizontalScrollView(activity);horizontal.addView(content);ScrollView scroll=new ScrollView(activity);scroll.addView(horizontal);AlertDialog.Builder dialog=new AlertDialog.Builder(activity).setTitle(name).setView(scroll).setPositiveButton("关闭",null);if(local!=null)dialog.setNeutralButton("其他应用打开",(d,w)->external(local));dialog.show();}
    private void pdf(File local,String name){
        int width=previewWidth(),height=(int)(activity.getResources().getDisplayMetrics().heightPixels*.72f);
        FrameLayout body=new FrameLayout(activity);TextView status=label("正在加载…",13);status.setGravity(Gravity.CENTER);
        PdfPages pages=new PdfPages(activity,local,width,status);body.addView(new PdfZoom(activity,pages),new FrameLayout.LayoutParams(-1,-1));body.addView(status,new FrameLayout.LayoutParams(-1,-1));previewCard(local,body,width,height);
    }
    private void previewMenu(File local,View anchor){
        UiPalette palette=UiPalette.of(activity);LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(6),dp(6),dp(6),dp(6));android.graphics.drawable.GradientDrawable shape=new android.graphics.drawable.GradientDrawable();shape.setColor(palette.dark?Color.rgb(43,43,43):Color.rgb(250,251,250));shape.setCornerRadius(dp(18));panel.setBackground(shape);panel.setClipToOutline(true);final PopupWindow[] popup={null};
        String[] labels={"保存到本地","其他应用打开"};for(int i=0;i<labels.length;i++){final boolean save=i==0;LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(12),0,dp(12),0);ImageView icon=new ImageView(activity);icon.setImageDrawable(new UiIcon(save?"download":"file",palette.accent));row.addView(icon,new LinearLayout.LayoutParams(dp(20),dp(20)));TextView title=label(labels[i],14);title.setGravity(Gravity.CENTER_VERTICAL);title.setPadding(dp(12),0,0,0);row.addView(title,new LinearLayout.LayoutParams(0,dp(46),1));row.setContentDescription(labels[i]);row.setOnClickListener(v->{popup[0].dismiss();if(save)save(local);else external(local);});panel.addView(row);}
        popup[0]=AnchoredMenu.below(activity,anchor,panel,dp(220));
    }
    private void save(File local){
        activity.getSharedPreferences("previewExport",Context.MODE_PRIVATE).edit().putString("file",local.getName()).apply();
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime(local)).putExtra(Intent.EXTRA_TITLE,local.getName().replaceFirst("^[a-f0-9]{64}-",""));
        try{activity.startActivityForResult(intent,208);}catch(ActivityNotFoundException e){Toast.makeText(activity,"手机未提供文件保存界面",Toast.LENGTH_LONG).show();}
    }
    boolean saveResult(int request,int result,Intent data){
        if(request!=208)return false;String name=activity.getSharedPreferences("previewExport",Context.MODE_PRIVATE).getString("file","");activity.getSharedPreferences("previewExport",Context.MODE_PRIVATE).edit().remove("file").apply();
        if(result!=Activity.RESULT_OK||data==null||data.getData()==null)return true;Uri target=data.getData();
        worker.execute(()->{try{File root=new File(activity.getCacheDir(),"previews"),source=new File(root,name);if(!source.getCanonicalFile().getParentFile().equals(root.getCanonicalFile())||!source.isFile())throw new IOException("缓存已移除，请重新打开文件后保存");try(InputStream input=new FileInputStream(source);OutputStream output=activity.getContentResolver().openOutputStream(target,"w")){if(output==null)throw new IOException("无法写入所选位置");byte[] bytes=new byte[16384];int n;while((n=input.read(bytes))!=-1)output.write(bytes,0,n);}ui(()->Toast.makeText(activity,"已保存到所选位置",Toast.LENGTH_SHORT).show());}catch(Exception e){error(e);}});return true;
    }
    private String mime(File local){Uri uri=new Uri.Builder().scheme("content").authority(activity.getPackageName()+".previews").appendPath(local.getName()).build();return activity.getContentResolver().getType(uri);}
    private void external(File local){Uri uri=new Uri.Builder().scheme("content").authority(activity.getPackageName()+".previews").appendPath(local.getName()).build();String mime=activity.getContentResolver().getType(uri);Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newRawUri("文件",uri));try{activity.startActivity(Intent.createChooser(intent,"打开文件"));}catch(ActivityNotFoundException e){Toast.makeText(activity,"手机尚未安装支持此格式的查看器",Toast.LENGTH_LONG).show();}}
    void close(){closed=true;for(RemoteApi channel:channels)channel.cancel();worker.shutdownNow();}
    private static final class ZoomImage extends View {
        final Bitmap bitmap;final Paint paint=new Paint(3);final ScaleGestureDetector pinch;final GestureDetector gestures;float zoom=1,x,y,lastX,lastY;
        ZoomImage(Activity context,Bitmap bitmap){super(context);this.bitmap=bitmap;setMinimumHeight(Math.round(380*getResources().getDisplayMetrics().density));pinch=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){public boolean onScale(ScaleGestureDetector detector){zoom=Math.max(1,Math.min(6,zoom*detector.getScaleFactor()));if(zoom==1)x=y=0;invalidate();return true;}});gestures=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener(){public boolean onDown(MotionEvent e){return true;}public boolean onDoubleTap(MotionEvent e){zoom=zoom>1?1:2.5f;x=y=0;invalidate();return true;}});}
        protected void onDraw(Canvas canvas){float fit=Math.min(getWidth()/(float)bitmap.getWidth(),getHeight()/(float)bitmap.getHeight());canvas.save();canvas.translate(getWidth()/2f+x,getHeight()/2f+y);canvas.scale(fit*zoom,fit*zoom);canvas.drawBitmap(bitmap,-bitmap.getWidth()/2f,-bitmap.getHeight()/2f,paint);canvas.restore();}
        public boolean onTouchEvent(MotionEvent e){pinch.onTouchEvent(e);gestures.onTouchEvent(e);if(e.getActionMasked()==MotionEvent.ACTION_DOWN){lastX=e.getX();lastY=e.getY();}else if(e.getActionMasked()==MotionEvent.ACTION_MOVE){if(!pinch.isInProgress()&&zoom>1){x+=e.getX()-lastX;y+=e.getY()-lastY;invalidate();}lastX=e.getX();lastY=e.getY();}return true;}
    }
}
