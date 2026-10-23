package dev.codexlan.remote;

import android.app.Activity;
import android.media.*;
import android.os.*;
import org.vosk.Model;
import org.vosk.Recognizer;
import java.util.concurrent.*;

/** App-owned foreground microphone and offline inference. No audio files or network requests. */
final class SpeechInput {
    interface Listener {void text(String value);void status(String value);void ended();void error(String value);}
    private final Activity activity;private final Handler ui=new Handler(Looper.getMainLooper());private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile boolean running,stopRequested,destroyed;private volatile int generation;private volatile AudioRecord recorder;private Model model;
    SpeechInput(Activity activity){this.activity=activity;}
    boolean running(){return running;}
    private void post(int epoch,Runnable value){ui.post(()->{if(!destroyed&&epoch==generation)value.run();});}
    void start(Listener listener){
        cancel();if(destroyed)return;final int epoch=++generation;stopRequested=false;running=true;listener.status("正在加载离线语音模型…");
        worker.execute(()->{AudioRecord audio=null;Recognizer recognizer=null;String finalText="";boolean failed=false;
            try{
                if(!VoiceModels.ready(activity))throw new IllegalStateException("MODEL_NOT_READY");
                if(model==null)model=new Model(VoiceModels.directory(activity).getAbsolutePath());
                if(epoch!=generation||destroyed)return;
                if(stopRequested){failed=true;return;}
                recognizer=new Recognizer(model,16000f);
                int minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);if(minimum<=0)throw new IllegalStateException("AUDIO_UNAVAILABLE");
                audio=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(minimum*2,12800));
                if(audio.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("AUDIO_UNAVAILABLE");
                recorder=audio;if(epoch!=generation||stopRequested||destroyed)return;audio.startRecording();if(audio.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException("AUDIO_UNAVAILABLE");
                post(epoch,()->listener.status("正在听 · 离线识别，文字直接填入输入框"));short[] buffer=new short[3200];long began=android.os.SystemClock.elapsedRealtime();String previous="";
                while(epoch==generation&&!stopRequested&&!destroyed){
                    int count=audio.read(buffer,0,buffer.length);if(count<0){if(stopRequested||epoch!=generation)break;throw new IllegalStateException("AUDIO_UNAVAILABLE");}if(count==0)continue;
                    boolean complete=recognizer.acceptWaveForm(buffer,count);String value=VoiceTranscript.text(complete?recognizer.getResult():recognizer.getPartialResult(),complete?"text":"partial");
                    if(!value.isEmpty()&&!value.equals(previous)){previous=value;String text=value;post(epoch,()->listener.text(text));}
                    if(complete&&!value.isEmpty()){finalText=value;break;}
                    long elapsed=android.os.SystemClock.elapsedRealtime()-began;if(elapsed>=60000||elapsed>=15000&&previous.isEmpty())break;
                }
                if(epoch==generation&&finalText.isEmpty())finalText=VoiceTranscript.text(recognizer.getFinalResult(),"text");
            }catch(Exception error){failed=true;String reason=error instanceof SecurityException?"麦克风权限未开启，请重新授权":model==null?"离线模型加载失败，请在设置中检查模型":"麦克风不可用，请检查系统麦克风开关或其他应用占用";post(epoch,()->listener.error(reason));}
            finally{
                if(audio!=null){try{audio.stop();}catch(Exception ignored){}audio.release();if(recorder==audio)recorder=null;}
                if(recognizer!=null)recognizer.close();
                if(epoch==generation&&!destroyed){String text=finalText;boolean error=failed;post(epoch,()->{running=false;if(!text.isEmpty())listener.text(text);else if(!error)listener.error("没有听清，请长按重试");listener.ended();});}
            }
        });
    }
    void stop(){if(!running)return;stopRequested=true;AudioRecord audio=recorder;if(audio!=null)try{audio.stop();}catch(Exception ignored){}}
    void cancel(){generation++;running=false;stopRequested=true;AudioRecord audio=recorder;if(audio!=null)try{audio.stop();}catch(Exception ignored){}}
    void destroy(){cancel();destroyed=true;worker.execute(()->{if(model!=null){model.close();model=null;}});worker.shutdown();}
}
