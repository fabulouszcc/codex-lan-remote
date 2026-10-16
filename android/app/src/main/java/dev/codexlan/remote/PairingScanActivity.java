package dev.codexlan.remote;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/** Offline QR scan. Camera and frames are released when this page leaves foreground. */
@SuppressWarnings("deprecation")
public final class PairingScanActivity extends Activity implements SurfaceHolder.Callback {
    private SurfaceView preview;
    private TextView hint;
    private Camera camera;
    private boolean resumed,surfaceReady,finished,permissionAsked;
    private long lastFrame;
    private final AtomicBoolean decoding=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(18,18,18));root.setPadding(dp(20),dp(16),dp(20),dp(16));root.setOnApplyWindowInsetsListener((v,insets)->{if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());v.setPadding(dp(20)+bars.left,dp(12)+bars.top,dp(20)+bars.right,dp(12)+bars.bottom);}return insets;});
        TextView title=new TextView(this);title.setText("扫描电脑配对码");title.setTextSize(23);title.setTextColor(Color.WHITE);title.setPadding(0,dp(8),0,dp(18));root.addView(title);
        FrameLayout viewport=new FrameLayout(this);viewport.setClipChildren(true);preview=new SurfaceView(this);viewport.addView(preview,new FrameLayout.LayoutParams(-1,-1));root.addView(viewport,new LinearLayout.LayoutParams(-1,0,1));preview.getHolder().addCallback(this);
        hint=new TextView(this);hint.setText("将电脑插件的配对二维码放入镜头中\n二维码仅在手机本地识别");hint.setTextColor(Color.LTGRAY);hint.setTextSize(14);hint.setGravity(Gravity.CENTER);hint.setPadding(0,dp(18),0,dp(12));root.addView(hint);
        Button image=action("从图片识别");root.addView(image);image.setOnClickListener(v->{Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);try{startActivityForResult(intent,2);}catch(Exception e){hint.setText("系统图片选择器不可用，请使用相机或粘贴配对信息");}});
        Button back=action("返回");back.setOnClickListener(v->finish());root.addView(back);setContentView(root);
    }
    private Button action(String title){Button button=new Button(this);button.setText(title);button.setTextSize(14);button.setAllCaps(false);button.setTextColor(Color.rgb(242,242,242));android.graphics.drawable.GradientDrawable shape=new android.graphics.drawable.GradientDrawable();shape.setColor(Color.rgb(43,43,43));shape.setCornerRadius(dp(16));button.setBackground(shape);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(48));params.topMargin=dp(8);button.setLayoutParams(params);return button;}
    @Override protected void onResume(){super.onResume();resumed=true;if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)openCamera();else if(!permissionAsked){permissionAsked=true;requestPermissions(new String[]{Manifest.permission.CAMERA},1);}}
    @Override protected void onPause(){resumed=false;closeCamera();super.onPause();}
    @Override protected void onDestroy(){closeCamera();worker.shutdownNow();super.onDestroy();}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==1){if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)openCamera();else hint.setText("未开启相机权限，可从图片识别，或返回粘贴配对信息");}}
    @Override public void surfaceCreated(SurfaceHolder holder){surfaceReady=true;openCamera();}
    @Override public void surfaceChanged(SurfaceHolder holder,int format,int w,int h){}
    @Override public void surfaceDestroyed(SurfaceHolder holder){surfaceReady=false;closeCamera();}
    private void openCamera(){
        if(!resumed||!surfaceReady||camera!=null||finished||checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;
        try{
            int id=-1;Camera.CameraInfo info=new Camera.CameraInfo();for(int i=0;i<Camera.getNumberOfCameras();i++){Camera.getCameraInfo(i,info);if(info.facing==Camera.CameraInfo.CAMERA_FACING_BACK){id=i;break;}}if(id<0)throw new Exception();
            camera=Camera.open(id);Camera.Parameters parameters=camera.getParameters();Camera.Size size=parameters.getSupportedPreviewSizes().get(0);long score=Long.MAX_VALUE;
            for(Camera.Size candidate:parameters.getSupportedPreviewSizes()){long next=Math.abs((long)candidate.width*candidate.height-640*480);if(next<score){size=candidate;score=next;}}
            parameters.setPreviewSize(size.width,size.height);parameters.setPreviewFormat(android.graphics.ImageFormat.NV21);java.util.List<String> focus=parameters.getSupportedFocusModes();if(focus!=null&&focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);camera.setParameters(parameters);
            int rotation=getWindowManager().getDefaultDisplay().getRotation()*90;int orientation=(info.orientation-rotation+360)%360;camera.setDisplayOrientation(orientation);camera.setPreviewDisplay(preview.getHolder());
            int width=size.width,height=size.height;int displayWidth=orientation%180==0?width:height,displayHeight=orientation%180==0?height:width;View parent=(View)preview.getParent();float scale=Math.min(parent.getWidth()/(float)displayWidth,parent.getHeight()/(float)displayHeight);if(scale>0)preview.setLayoutParams(new FrameLayout.LayoutParams(Math.round(displayWidth*scale),Math.round(displayHeight*scale),Gravity.CENTER));
            camera.setPreviewCallbackWithBuffer((frame,source)->{
                long now=android.os.SystemClock.uptimeMillis();if(frame!=null&&now-lastFrame>=250&&decoding.compareAndSet(false,true)){lastFrame=now;byte[] gray=java.util.Arrays.copyOf(frame,width*height);worker.execute(()->{try{String text=PairingQr.grayscale(gray,width,height);accept(text);}catch(Exception ignored){}finally{decoding.set(false);}});}if(camera==source)source.addCallbackBuffer(frame);
            });camera.addCallbackBuffer(new byte[width*height*3/2]);camera.addCallbackBuffer(new byte[width*height*3/2]);camera.startPreview();
        }catch(Exception e){closeCamera();hint.setText("相机暂不可用，可从图片识别，或返回粘贴配对信息");}
    }
    private void closeCamera(){if(camera!=null){try{camera.setPreviewCallbackWithBuffer(null);camera.stopPreview();}catch(Exception ignored){}camera.release();camera=null;}}
    private void accept(String text){
        try{PairingInfo.parse(text,System.currentTimeMillis());runOnUiThread(()->{if(finished||isFinishing()||isDestroyed())return;finished=true;setResult(RESULT_OK,new Intent().putExtra("pairing",text));finish();});}
        catch(Exception e){runOnUiThread(()->{if(!isDestroyed())hint.setText(e.getMessage());});}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=2||result!=RESULT_OK||data==null||data.getData()==null)return;
        android.net.Uri uri=data.getData();worker.execute(()->{android.graphics.Bitmap bitmap=null;try{
            android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();bounds.inJustDecodeBounds=true;try(java.io.InputStream in=getContentResolver().openInputStream(uri)){android.graphics.BitmapFactory.decodeStream(in,null,bounds);}if(bounds.outWidth<1||bounds.outHeight<1)throw new Exception();
            android.graphics.BitmapFactory.Options options=new android.graphics.BitmapFactory.Options();options.inSampleSize=1;while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>2048)options.inSampleSize*=2;
            try(java.io.InputStream in=getContentResolver().openInputStream(uri)){bitmap=android.graphics.BitmapFactory.decodeStream(in,null,options);}if(bitmap==null)throw new Exception();int w=bitmap.getWidth(),h=bitmap.getHeight();int[] pixels=new int[w*h];bitmap.getPixels(pixels,0,w,0,0,w,h);accept(PairingQr.pixels(pixels,w,h));
        }catch(Exception e){runOnUiThread(()->{if(!isDestroyed())hint.setText("图片中未识别到配对码，请使用清晰的电脑配对二维码");});}finally{if(bitmap!=null)bitmap.recycle();}});
    }
}
