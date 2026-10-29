package io.localplayer.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;

/** Platform-only black-box smoke; no Kotlin or Jetpack test runtime required. */
public final class ReleaseLaunchSmoke extends Instrumentation {
    private Bundle args;
    private final JSONArray passed=new JSONArray();
    private final JSONArray taps=new JSONArray();
    @Override public void onCreate(Bundle args) { super.onCreate(args);this.args=args;start(); }
    private AccessibilityNodeInfo find(String label,boolean scrollable) {
        AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
        if(root==null)return null;
        AccessibilityNodeInfo partial=null;
        ArrayDeque<AccessibilityNodeInfo> nodes=new ArrayDeque<>();nodes.add(root);
        while(!nodes.isEmpty()){
            AccessibilityNodeInfo n=nodes.remove();
            Rect bounds=new Rect();n.getBoundsInScreen(bounds);
            boolean visible=n.isVisibleToUser()&&bounds.width()>=24&&bounds.height()>=24;
            for(AccessibilityNodeInfo parent=n.getParent();visible&&parent!=null;parent=parent.getParent()){
                if(parent.isScrollable()){
                    Rect viewport=new Rect();parent.getBoundsInScreen(viewport);
                    visible=viewport.contains(bounds);
                }
            }
            if(visible&&(scrollable?n.isScrollable():((n.getText()!=null&&n.getText().toString().equals(label))||
                (n.getContentDescription()!=null&&n.getContentDescription().toString().equals(label)))))return n;
            if(visible&&!scrollable&&partial==null&&!"避难所".equals(label)&&n.getText()!=null&&n.getText().toString().contains(label))partial=n;
            for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null)nodes.add(c);}
        }
        return partial;
    }
    private AccessibilityNodeInfo await(String label){
        long end=SystemClock.elapsedRealtime()+10000;
        while(SystemClock.elapsedRealtime()<end){AccessibilityNodeInfo n=find(label,false);if(n!=null)return n;SystemClock.sleep(100);}
        throw new AssertionError("UI entry missing: "+label);
    }
    private void click(String label){
        clickNode(await(label),label);
    }
    private void clickNode(AccessibilityNodeInfo node,String label){
        Rect bounds=new Rect();node.getBoundsInScreen(bounds);
        taps.put(label+" "+bounds.toShortString());
        while(node!=null&&!node.isClickable())node=node.getParent();
        if(node==null||!node.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new AssertionError("Cannot click: "+label);
        SystemClock.sleep(350);
    }
    private AccessibilityNodeInfo scrollTo(String label){
        long end=SystemClock.elapsedRealtime()+20000;
        while(SystemClock.elapsedRealtime()<end){
            AccessibilityNodeInfo target=find(label,false);if(target!=null)return target;
            AccessibilityNodeInfo scroll=find("",true);
            if(scroll!=null)scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            try{getUiAutomation().waitForIdle(100,1500);}catch(java.util.concurrent.TimeoutException ignored){}
            // Connecting replaces the source list with the directory grid.
            // Re-read the active tree even when the old list cannot scroll.
            SystemClock.sleep(250);
        }
        return await(label);
    }
    private void launch(){
        Intent intent=getTargetContext().getPackageManager().getLaunchIntentForPackage(getTargetContext().getPackageName());
        if(intent==null)throw new AssertionError("No launcher");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivitySync(intent);waitForIdleSync();
    }
    private SurfaceView surface(View view){
        if(view instanceof SurfaceView)return (SurfaceView)view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){SurfaceView found=surface(group.getChildAt(i));if(found!=null)return found;}}
        return null;
    }
    private long frame(Activity activity) throws Exception {
        for(int attempt=0;attempt<50;attempt++){
            AtomicReference<SurfaceView> holder=new AtomicReference<>();
            runOnMainSync(()->holder.set(surface(activity.getWindow().getDecorView())));
            SurfaceView view=holder.get();
            if(view!=null&&view.getWidth()>0&&view.getHeight()>0){
                Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);
                CountDownLatch done=new CountDownLatch(1);int[] status={-1};
                PixelCopy.request(view,bitmap,result->{status[0]=result;done.countDown();},new Handler(Looper.getMainLooper()));
                if(done.await(3,TimeUnit.SECONDS)&&status[0]==PixelCopy.SUCCESS){
                    long hash=17;java.util.HashSet<Integer> colors=new java.util.HashSet<>();
                    for(int y=0;y<bitmap.getHeight();y+=31)for(int x=0;x<bitmap.getWidth();x+=29){int pixel=bitmap.getPixel(x,y);colors.add(pixel);hash=hash*31+pixel;}
                    bitmap.recycle();if(colors.size()>5)return hash;
                }else bitmap.recycle();
            }
            SystemClock.sleep(200);
        }
        throw new AssertionError("No decoded picture on real surface");
    }
    private MediaController controller() throws Exception {
        MediaSessionManager manager=(MediaSessionManager)getTargetContext().getSystemService(android.content.Context.MEDIA_SESSION_SERVICE);
        for(int i=0;i<30;i++){
            // Shell identity is needed only for enumeration. Keeping it while
            // playback runs changes the app's audio-focus permission checks.
            getUiAutomation().adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL");
            try{
                for(MediaController c:manager.getActiveSessions(null))if(c.getPackageName().equals(getTargetContext().getPackageName()))return c;
            }finally{getUiAutomation().dropShellPermissionIdentity();}
            SystemClock.sleep(100);
        }
        throw new AssertionError("No platform media session");
    }
    private void awaitPlaying(MediaController controller,long minimum,long after) {
        long deadline=SystemClock.elapsedRealtime()+20000;
        while(SystemClock.elapsedRealtime()<deadline){
            android.media.session.PlaybackState state=controller.getPlaybackState();
            if(state!=null&&state.getState()==android.media.session.PlaybackState.STATE_PLAYING){
                long position=state.getPosition()+(long)((SystemClock.elapsedRealtime()-state.getLastPositionUpdateTime())*state.getPlaybackSpeed());
                if(position>=minimum&&state.getLastPositionUpdateTime()>=after)return;
            }
            SystemClock.sleep(100);
        }
        throw new AssertionError("Media clock did not start advancing: state="+controller.getPlaybackState()+" title="+(controller.getMetadata()==null?null:controller.getMetadata().getDescription().getTitle()));
    }
    private void playback(String source,String clip) throws Exception {
        launch();click("信源");clickNode(scrollTo(source),source);
        AccessibilityNodeInfo target=scrollTo(clip);
        ActivityMonitor monitor=addMonitor("io.localplayer.app.PlaybackActivity",null,false);
        clickNode(target,clip);
        Activity activity=waitForMonitorWithTimeout(monitor,10000);removeMonitor(monitor);
        if(activity==null)throw new AssertionError("Playback activity did not open");
        MediaController controller=null;
        try{
            controller=controller();taps.put("session for "+clip+": "+(controller.getMetadata()==null?null:controller.getMetadata().getDescription().getTitle())+" "+controller.getPlaybackState());awaitPlaying(controller,250,0);
            if(args!=null&&"true".equals(args.getString("require_audio"))){
                android.media.AudioManager audio=(android.media.AudioManager)getTargetContext().getSystemService(android.content.Context.AUDIO_SERVICE);
                long end=SystemClock.elapsedRealtime()+10000;
                while(!audio.isMusicActive()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);
                if(!audio.isMusicActive())throw new AssertionError("No active audio output for "+clip);
                passed.put(clip+": active audio output");
            }
            long first=frame(activity);SystemClock.sleep(600);long next=frame(activity);
            if(first==next)throw new AssertionError("Picture did not advance");
            long issued=SystemClock.elapsedRealtime();controller.getTransportControls().seekTo(2500);awaitPlaying(controller,2700,issued);
            long deadline=SystemClock.elapsedRealtime()+10000;boolean changed=false;
            while(SystemClock.elapsedRealtime()<deadline){if(frame(activity)!=next){changed=true;break;}SystemClock.sleep(100);}
            if(!changed)throw new AssertionError("Picture did not change after seek");
            if(controller.getPlaybackState()==null||controller.getPlaybackState().getPosition()<1500)throw new AssertionError("Seek did not advance position");
            passed.put(source+" / "+clip+": real decoded frames, progress and seek");
        }finally{
            if(controller!=null)controller.getTransportControls().stop();
            runOnMainSync(activity::finish);getUiAutomation().dropShellPermissionIdentity();
        }
    }
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            launch();click("站务");click("映汐放映站 · 信笺");
            Rect button=new Rect();await("检查更新").getBoundsInScreen(button);
            Bitmap image=getUiAutomation().takeScreenshot();
            if(image==null||button.bottom<image.getHeight()*3/4)throw new AssertionError("Update button is not at bottom");
            if(image.getPixel(4,button.centerY())!=0xFFF3F1ED)throw new AssertionError("Footer background mismatch");
            image.recycle();passed.put("Production R8 rules: launch, settings and fixed footer");
            if(args!=null&&"media".equals(args.getString("mode"))){
                for(String source:args.getString("source")==null?new String[]{"R8 SMB","R8 SFTP"}:new String[]{args.getString("source")})for(String clip:args.getString("clip")==null?new String[]{"r8-avc","r8-hevc"}:new String[]{args.getString("clip")})playback(source,clip);
            }
            result.putString("passed",passed.toString());finish(Activity.RESULT_OK,result);
        }catch(Throwable error){
            JSONArray visible=new JSONArray();
            AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
            if(root!=null){
                ArrayDeque<AccessibilityNodeInfo> nodes=new ArrayDeque<>();nodes.add(root);
                while(!nodes.isEmpty()){
                    AccessibilityNodeInfo node=nodes.remove();
                    if(node.getText()!=null)visible.put(node.getText().toString());
                    for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child!=null)nodes.add(child);}
                }
            }
            result.putString("visible",visible.toString());
            result.putString("taps",taps.toString());
            result.putString("passed",passed.toString());result.putString("error",android.util.Log.getStackTraceString(error));finish(Activity.RESULT_CANCELED,result);
        }
    }
}
