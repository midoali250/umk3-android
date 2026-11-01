package com.midoali.atari;

import static org.junit.Assert.*;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.midoali.atari.core.SegaButtons;
import com.midoali.atari.core.SegaCore;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class ControlsInstrumentedTest {
    private final int[] location=new int[2];
    private void await(ActivityScenario<MainActivity> scenario,Check check)throws Exception {
        long deadline=SystemClock.uptimeMillis()+90000;
        while(SystemClock.uptimeMillis()<deadline){
            boolean[] ready={false};scenario.onActivity(a->{try{ready[0]=check.ok(a);}catch(Exception e){throw new RuntimeException(e);}});
            if(ready[0])return;SystemClock.sleep(100);
        }
        MainActivity a=activity(scenario);EmulatorEngine e=a.engineForTest();EmulatorEngine.Frame f=e.frame();
        fail("Timed out waiting for emulator / renderer; loaded="+e.isLoaded()+" error="+e.error()
            +" fps="+e.stats().fps+" frame="+(f==null?"null":f.serial+":"+f.width+"x"+f.height)
            +" videoSerial="+a.videoForTest().frameSerial()+" presented="+a.videoForTest().presentedFrames()
            +" nonBlack="+a.videoForTest().nonBlackFrameSeen()+" surface="+a.videoForTest().getWidth()+"x"+a.videoForTest().getHeight());
    }
    private MainActivity activity(ActivityScenario<MainActivity> scenario){AtomicReference<MainActivity> ref=new AtomicReference<>();scenario.onActivity(ref::set);return ref.get();}
    private void multitouch(MainActivity a){
        TouchControlsView v=a.controlsForTest();float px=v.centerX(0)+v.padRadius()*.7f,py=v.centerY(0),bx=v.faceX(1),by=v.faceY(1);
        inject(event(a,MotionEvent.ACTION_DOWN,px,py));v.getLocationOnScreen(location);bx+=location[0];by+=location[1];
        MotionEvent.PointerProperties[] props={new MotionEvent.PointerProperties(),new MotionEvent.PointerProperties()};props[0].id=0;props[0].toolType=MotionEvent.TOOL_TYPE_FINGER;props[1].id=1;props[1].toolType=MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords[] coords={new MotionEvent.PointerCoords(),new MotionEvent.PointerCoords()};coords[0].x=px+location[0];coords[0].y=py+location[1];coords[0].pressure=1;coords[0].size=1;coords[1].x=bx;coords[1].y=by;coords[1].pressure=1;coords[1].size=1;
        long now=SystemClock.uptimeMillis();MotionEvent e=MotionEvent.obtain(now,now,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,props,coords,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);inject(e);
        int expected=(1<<SegaButtons.RIGHT)|(1<<SegaButtons.B);assertEquals(expected,a.engineForTest().inputMask());SystemClock.sleep(500);assertEquals(expected,SegaCore.nativeTestInput(-1));
        now=SystemClock.uptimeMillis();e=MotionEvent.obtain(now,now,MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,props,coords,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);inject(e);
        assertEquals(1<<SegaButtons.RIGHT,a.engineForTest().inputMask());SystemClock.sleep(250);assertEquals(1<<SegaButtons.RIGHT,SegaCore.nativeTestInput(-1));inject(event(a,MotionEvent.ACTION_UP,px,py));assertEquals(0,a.engineForTest().inputMask());
    }
    interface Check{boolean ok(MainActivity a)throws Exception;}
    private MotionEvent event(MainActivity a,int action,float x,float y){
        a.controlsForTest().getLocationOnScreen(location);x+=location[0];y+=location[1];
        long now=SystemClock.uptimeMillis();
        MotionEvent e=MotionEvent.obtain(now,now,action,x,y,0);e.setSource(InputDevice.SOURCE_TOUCHSCREEN);return e;
    }
    private void inject(MotionEvent e){assertTrue(InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(e,true));e.recycle();}
    private void tap(MainActivity a,int button,int holdMillis){
        TouchControlsView v=a.controlsForTest();float x,y;
        if(button==SegaButtons.START){x=v.centerX(2);y=v.centerY(2);}
        else if(button==SegaButtons.A||button==SegaButtons.B||button==SegaButtons.C||button==SegaButtons.X||button==SegaButtons.Y||button==SegaButtons.Z){
            int i=button==SegaButtons.A?0:button==SegaButtons.B?1:button==SegaButtons.C?2:button==SegaButtons.X?3:button==SegaButtons.Y?4:5;x=v.faceX(i);y=v.faceY(i);}
        else{x=v.centerX(0)+(button==SegaButtons.LEFT?-v.padRadius()*.7f:button==SegaButtons.RIGHT?v.padRadius()*.7f:0);y=v.centerY(0)+(button==SegaButtons.UP?-v.padRadius()*.7f:button==SegaButtons.DOWN?v.padRadius()*.7f:0);}
        v.getLocationOnScreen(location);
        android.util.Log.e("SEGA_TOUCH","button="+button+" x="+x+" y="+y+" loc="+location[0]+","+location[1]+" size="+v.getWidth()+"x"+v.getHeight()+" hit="+v.hitMask(x,y));
        int before=SegaCore.nativeTestInput(button);byte[] beforeRam=SegaCore.nativeTestRam(0,65536);assertNotNull(beforeRam);
        inject(event(a,MotionEvent.ACTION_DOWN,x,y));assertEquals(1<<button,a.engineForTest().inputMask());
        SystemClock.sleep(holdMillis);
        assertTrue("Core did not poll "+button,SegaCore.nativeTestInput(button)>before);
        assertEquals(1<<button,SegaCore.nativeTestInput(-1));
        assertFalse("No RAM response for "+button,Arrays.equals(beforeRam,SegaCore.nativeTestRam(0,65536)));
        inject(event(a,MotionEvent.ACTION_UP,x,y));assertEquals(0,a.engineForTest().inputMask());SystemClock.sleep(250);
    }
    private void screenshot(MainActivity a,String name)throws Exception{
        new File(a.getFilesDir(),"test-captures").mkdirs();
        if(!"true".equals(InstrumentationRegistry.getArguments().getString("screenshots")))return;
        Bitmap bitmap=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(bitmap);
        File file=new File(a.getFilesDir(),"test-captures/"+name+".png");
        try(FileOutputStream out=new FileOutputStream(file)){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
    }
    @Test public void dispatchedMultitouchReachesRealGameAndSettingsPersist()throws Exception{
        String path=InstrumentationRegistry.getArguments().getString("rom");assertNotNull("Local ROM required",path);
        ApplicationProvider.getApplicationContext().getSharedPreferences("sega-settings",0).edit().clear().commit();
        Intent intent=new Intent(ApplicationProvider.getApplicationContext(),MainActivity.class).putExtra(MainActivity.EXTRA_TEST_ROM,path).putExtra(MainActivity.EXTRA_TEST_NO_WATCHDOG,true);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(intent)){
            await(scenario,a->a.engineForTest().frame()!=null&&a.videoForTest().presentedFrames()>120);
            assertEquals(6,activity(scenario).controlsForTest().faceButtonCount());
            // Regression: video must actually present non-black pixels (v0.4 black-screen bug).
            await(scenario,a->a.videoForTest().nonBlackFrameSeen());
            AtomicReference<android.graphics.Bitmap> shotRef=new AtomicReference<>();
            scenario.onActivity(a->shotRef.set(a.videoForTest().captureForTest()));
            android.graphics.Bitmap shot=shotRef.get();
            boolean anyLit=false;
            for(int y=0;y<shot.getHeight()&&!anyLit;y+=4)for(int x=0;x<shot.getWidth();x+=4){int c=shot.getPixel(x,y);if((c&0x00ffffff)!=0){anyLit=true;break;}}
            assertTrue("Video view rendered black",anyLit);
            scenario.onActivity(a->shot.recycle());
            // Gear opens the side menu without using Back.
            scenario.onActivity(a->a.gearForTest().performClick());
            assertTrue(activity(scenario).isSidePanelOpen());
            scenario.onActivity(a->a.gearForTest().performClick());
            assertFalse(activity(scenario).isSidePanelOpen());
            // All waits/injections run on the test thread, never block the UI dispatcher.
            tap(activity(scenario),SegaButtons.START,300);SystemClock.sleep(12000);
            tap(activity(scenario),SegaButtons.START,300);SystemClock.sleep(12000);
            tap(activity(scenario),SegaButtons.C,300);SystemClock.sleep(1500);
            tap(activity(scenario),SegaButtons.C,300);SystemClock.sleep(15000);
            screenshot(activity(scenario),"match-before");
            for(int button:new int[]{SegaButtons.RIGHT,SegaButtons.LEFT,SegaButtons.UP,SegaButtons.DOWN,SegaButtons.A,SegaButtons.B,SegaButtons.C,SegaButtons.X,SegaButtons.Y,SegaButtons.Z}){
                tap(activity(scenario),button,button>=SegaButtons.UP?650:220);screenshot(activity(scenario),"input-"+button);
            }
            multitouch(activity(scenario));
            scenario.onActivity(a->a.videoForTest().setVideoMode(com.midoali.atari.core.ControlLayout.VIDEO_ENHANCED));
            await(scenario,a->a.videoForTest().appliedVideoMode()==com.midoali.atari.core.ControlLayout.VIDEO_ENHANCED);screenshot(activity(scenario),"enhanced");
            scenario.onActivity(a->a.videoForTest().setVideoMode(com.midoali.atari.core.ControlLayout.VIDEO_ORIGINAL));
            await(scenario,a->a.videoForTest().appliedFilter()==GameSurfaceView.FILTER_NEAREST);screenshot(activity(scenario),"original");
            scenario.onActivity(a->a.videoForTest().setVideoMode(com.midoali.atari.core.ControlLayout.VIDEO_SMOOTH));
            await(scenario,a->a.videoForTest().appliedFilter()==GameSurfaceView.FILTER_LINEAR);screenshot(activity(scenario),"smooth");
            scenario.onActivity(a->a.videoForTest().setDisplayMode(com.midoali.atari.core.ControlLayout.DISPLAY_STRETCH));
            await(scenario,a->{int[] vp=a.videoForTest().appliedViewport();return vp[2]==a.controlsForTest().getWidth()&&vp[3]==a.controlsForTest().getHeight();});
            scenario.onActivity(a->a.videoForTest().setDisplayMode(com.midoali.atari.core.ControlLayout.DISPLAY_FIT));
            await(scenario,a->{int[] vp=a.videoForTest().appliedViewport();return vp[0]>=0&&vp[1]>=0&&vp[2]<=a.controlsForTest().getWidth()&&vp[3]<=a.controlsForTest().getHeight();});
            screenshot(activity(scenario),"fit-full");
            MainActivity a=activity(scenario);float old=a.controlsForTest().centerX(0);
            scenario.onActivity(x->x.controlsForTest().setEditing(true));
            inject(event(a,MotionEvent.ACTION_DOWN,a.controlsForTest().centerX(0),a.controlsForTest().centerY(0)));
            inject(event(a,MotionEvent.ACTION_MOVE,a.controlsForTest().centerX(0)+40,a.controlsForTest().centerY(0)-20));
            inject(event(a,MotionEvent.ACTION_UP,a.controlsForTest().centerX(0),a.controlsForTest().centerY(0)));
            assertTrue(a.controlsForTest().centerX(0)>old);assertEquals(0,a.engineForTest().inputMask());
            scenario.onActivity(x->{x.layoutForTest().setScale(1.3f);x.layoutForTest().setOpacity(.5f);x.layoutForTest().bilinear=true;new SettingsStore(x).save(x.layoutForTest());x.controlsForTest().setEditing(false);});
            screenshot(a,"layout-edit");
            scenario.recreate();await(scenario,restored->restored.engineForTest().frame()!=null&&restored.videoForTest().appliedFilter()==GameSurfaceView.FILTER_LINEAR);
            scenario.onActivity(restored->{assertEquals(1.3f,restored.layoutForTest().scale,.001);assertEquals(.5f,restored.layoutForTest().opacity,.001);assertTrue(restored.layoutForTest().padX>.16f);assertTrue(restored.layoutForTest().bilinear);assertTrue(restored.layoutForTest().sixButton);assertEquals(6,restored.controlsForTest().faceButtonCount());});
        }
    }
}
