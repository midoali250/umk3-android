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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class ControlsInstrumentedTest {
    private final int[] location=new int[2];
    private long dragDownTime;
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
    private MotionEvent dragEvent(MainActivity a,int action,float x,float y){
        a.controlsForTest().getLocationOnScreen(location);x+=location[0];y+=location[1];
        long now=SystemClock.uptimeMillis();
        if(action==MotionEvent.ACTION_DOWN)dragDownTime=now;
        MotionEvent.PointerProperties[] props={new MotionEvent.PointerProperties()};props[0].id=0;props[0].toolType=MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords[] coords={new MotionEvent.PointerCoords()};coords[0].x=x;coords[0].y=y;coords[0].pressure=1;coords[0].size=1;
        MotionEvent e=MotionEvent.obtain(dragDownTime,now,action,1,props,coords,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);return e;
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
    private void shell(String command) {
        try (android.os.ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
             java.io.InputStream in = new java.io.FileInputStream(pfd.getFileDescriptor())) {
            byte[] buf = new byte[8192]; while (in.read(buf) != -1) { }
        } catch (Exception ignored) { }
    }
    private void exportScreenshot(MainActivity a, File file, String name) {
        shell("run-as " + a.getPackageName() + " cat '" + file.getAbsolutePath() + "' > /data/local/tmp/umk3-" + name + ".png");
    }
    private void screenshot(MainActivity a,String name)throws Exception{
        new File(a.getFilesDir(),"test-captures").mkdirs();
        if(!"true".equals(InstrumentationRegistry.getArguments().getString("screenshots")))return;
        Bitmap bitmap=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(bitmap);
        File file=new File(a.getFilesDir(),"test-captures/"+name+".png");
        try(FileOutputStream out=new FileOutputStream(file)){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
        exportScreenshot(a,file,name);
    }
    /** Free analog stick diagonals plus the one-tap finisher button, end to end. */
    @Test public void analogStickDiagonalsAndOneTapFinisherReachTheCore()throws Exception{
        String path=InstrumentationRegistry.getArguments().getString("rom");assertNotNull("Local ROM required",path);
        ApplicationProvider.getApplicationContext().getSharedPreferences("sega-settings",0).edit().clear().commit();
        Intent intent=new Intent(ApplicationProvider.getApplicationContext(),MainActivity.class).putExtra(MainActivity.EXTRA_TEST_ROM,path).putExtra(MainActivity.EXTRA_TEST_NO_WATCHDOG,true);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(intent)){
            await(scenario,a->a.engineForTest().frame()!=null&&a.videoForTest().presentedFrames()>120);
            await(scenario,a->!a.engineForTest().isPaused());
            MainActivity a=activity(scenario);
            TouchControlsView v=a.controlsForTest();
            float px=v.centerX(0),py=v.centerY(0),r=v.padRadius();
            float diagonal=r*0.62f;
            // Up + forward at once: the old cross could only report one axis in a tie.
            inject(event(a,MotionEvent.ACTION_DOWN,px+diagonal,py-diagonal));
            int upForward=(1<<SegaButtons.UP)|(1<<SegaButtons.RIGHT);
            assertEquals("stick did not report the up-forward diagonal",upForward,a.engineForTest().inputMask());
            assertTrue(a.controlsForTest().stickEngaged());
            // The knob must be drawn where the finger pulled it, not left at the centre.
            assertTrue("stick knob did not follow the finger",a.controlsForTest().stickOffsetX()>r*0.3f);
            assertTrue(a.controlsForTest().stickOffsetY()<-r*0.3f);
            screenshot(a,"stick-diagonal");
            // Slide the thumb across the centre to the opposite diagonal without lifting.
            inject(event(a,MotionEvent.ACTION_MOVE,px-diagonal,py+diagonal));
            int downBack=(1<<SegaButtons.DOWN)|(1<<SegaButtons.LEFT);
            assertEquals("stick did not slide to the down-back diagonal",downBack,a.engineForTest().inputMask());
            inject(event(a,MotionEvent.ACTION_MOVE,px+r*0.03f,py+r*0.03f));
            assertEquals("stick dead zone did not release the direction",0,a.engineForTest().inputMask());
            inject(event(a,MotionEvent.ACTION_UP,px,py));
            assertEquals(0,a.engineForTest().inputMask());
            // The FINISH star opens the one-tap move panel through the real touch path.
            float fx=v.centerX(3),fy=v.centerY(3);
            inject(event(a,MotionEvent.ACTION_DOWN,fx,fy));SystemClock.sleep(140);
            inject(event(a,MotionEvent.ACTION_UP,fx,fy));
            await(scenario,x->x.movePanelForTest()!=null&&x.movePanelForTest().isPanelOpen());
            // Every fighter offers at least its two fatalities plus a few special moves.
            scenario.onActivity(x->{
                MovePanel panel=x.movePanelForTest();
                int width=x.controlsForTest().getWidth(),height=x.controlsForTest().getHeight();
                int minimum=Integer.MAX_VALUE;String worst="";
                for(String fighterName:com.midoali.atari.core.MoveData.CHARACTER_NAMES){
                    panel.selectFighterForTest(fighterName);
                    panel.layoutForTest(width,height);
                    int rows=0;
                    for(int i=0;i<panel.listForTest().getChildCount();++i)
                        if(panel.listForTest().getChildAt(i) instanceof android.widget.Button)rows++;
                    if(rows<minimum){minimum=rows;worst=fighterName;}
                }
                // Secret fighters (Noob Saibot, Human Smoke) have shortened arcade lists.
                assertTrue("move panel is thin for "+worst+": "+minimum+" rows",minimum>=3);
                panel.selectFighterForTest("Cyrax");
            });
            screenshot(a,"move-panel");
            // Pick Cyrax -> Fatality 1 with a real click on the row.
            scenario.onActivity(x->{
                x.movePanelForTest().selectFighterForTest("Cyrax");
                android.widget.Button row=x.movePanelForTest().findRowForTest("Fatality 1");
                assertNotNull("Fatality 1 row is missing from the move panel",row);
                row.performClick();
            });
            scenario.onActivity(x->assertFalse("move panel stayed open after choosing a move",x.movePanelForTest().isPanelOpen()));
            // Sample what the native core actually polls while the macro plays. Cyrax's
            // Fatality 1 is DOWN, DOWN, UP, DOWN, High Punch as five separate presses.
            List<Integer> observed=new ArrayList<>();
            long deadline=SystemClock.uptimeMillis()+4200;
            while(SystemClock.uptimeMillis()<deadline){
                int mask=SegaCore.nativeTestInput(-1);
                if(observed.isEmpty()||observed.get(observed.size()-1)!=mask)observed.add(mask);
                SystemClock.sleep(4);
            }
            while(!observed.isEmpty()&&observed.get(observed.size()-1)==0)observed.remove(observed.size()-1);
            while(!observed.isEmpty()&&observed.get(0)==0)observed.remove(0);
            int down=1<<SegaButtons.DOWN,up=1<<SegaButtons.UP,highPunch=1<<SegaButtons.X;
            assertEquals("core did not see the five distinct presses: "+observed,
                    Arrays.asList(down,0,down,0,up,0,down,0,highPunch),observed);
            int[] history=new int[1];
            scenario.onActivity(x->history[0]=x.macroForTest().history().length);
            List<Integer> played=new ArrayList<>();
            scenario.onActivity(x->{for(int mask:x.macroForTest().history())played.add(mask);});
            assertEquals(Arrays.asList(down,0,down,0,up,0,down,0,highPunch,0),played);
            assertEquals("macro did not release the pad",Integer.valueOf(0),played.get(played.size()-1));
            assertEquals(0,a.engineForTest().inputMask());
            assertFalse(a.macroForTest().isRunning());
            // Holding the star repeats the last move instantly instead of opening the panel.
            int before=history[0];
            inject(event(a,MotionEvent.ACTION_DOWN,fx,fy));SystemClock.sleep(900);
            inject(event(a,MotionEvent.ACTION_UP,fx,fy));
            final boolean[] replayed=new boolean[1];
            deadline=SystemClock.uptimeMillis()+3000;
            while(SystemClock.uptimeMillis()<deadline&&!replayed[0]){
                scenario.onActivity(x->replayed[0]=!x.macroForTest().isRunning()
                        &&x.macroForTest().history().length>=9&&x.macroForTest().lastStartMillis()>0);
                SystemClock.sleep(50);
            }
            assertTrue("long press did not replay the last finisher",replayed[0]);
            scenario.onActivity(x->assertFalse("long press opened the panel",x.movePanelForTest().isPanelOpen()));
            screenshot(a,"finisher-one-tap");
            assertTrue(before>=9);
        }
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
            await(scenario,a->!a.isSidePanelOpen()&&!a.engineForTest().isPaused());SystemClock.sleep(500);
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
            MainActivity a=activity(scenario);
            float oldX=a.controlsForTest().centerX(0),oldY=a.controlsForTest().centerY(0);
            scenario.onActivity(x->x.controlsForTest().setEditing(true));
            inject(dragEvent(a,MotionEvent.ACTION_DOWN,oldX,oldY));SystemClock.sleep(100);
            inject(dragEvent(a,MotionEvent.ACTION_MOVE,oldX+80,oldY-40));SystemClock.sleep(150);
            inject(dragEvent(a,MotionEvent.ACTION_MOVE,oldX+160,oldY-80));SystemClock.sleep(150);
            inject(dragEvent(a,MotionEvent.ACTION_UP,oldX+160,oldY-80));
            float[] moved=new float[2];int[] mask=new int[1];
            scenario.onActivity(x->{moved[0]=x.controlsForTest().centerX(0);moved[1]=x.controlsForTest().centerY(0);mask[0]=x.engineForTest().inputMask();});
            assertTrue("D-pad did not move in layout editor: "+oldX+" -> "+moved[0]+" dragGroup="+a.controlsForTest().dragGroupForTest(),moved[0]>oldX);assertEquals(0,mask[0]);
            scenario.onActivity(x->{
                com.midoali.atari.core.ControlLayout edited=x.controlsForTest().layout();
                edited.setScale(1.3f);edited.setOpacity(.5f);edited.bilinear=true;
                new SettingsStore(x).save(edited);x.controlsForTest().setEditing(false);
            });
            screenshot(a,"layout-edit");
            scenario.recreate();await(scenario,restored->restored.engineForTest().frame()!=null&&restored.videoForTest().appliedFilter()==GameSurfaceView.FILTER_LINEAR);
            scenario.onActivity(restored->{assertEquals(1.3f,restored.layoutForTest().scale,.001);assertEquals(.5f,restored.layoutForTest().opacity,.001);assertTrue(restored.layoutForTest().padX>.16f);assertTrue(restored.layoutForTest().bilinear);assertTrue(restored.layoutForTest().sixButton);assertEquals(6,restored.controlsForTest().faceButtonCount());});
        }
    }
}
