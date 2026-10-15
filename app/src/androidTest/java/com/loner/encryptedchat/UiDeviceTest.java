package com.loner.encryptedchat;

import android.app.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class UiDeviceTest {
    @Rule public ActivityTestRule<MainActivity> activityRule=new ActivityTestRule<>(MainActivity.class,false,false);
    private Context context;private File dir;
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    @Before public void setup()throws Exception {
        Context target=instrumentation.getTargetContext();dir=new File(target.getNoBackupFilesDir(),"test-ui-"+UUID.randomUUID());assertTrue(dir.mkdirs());
        IsolatedTestRunner.directory=dir;IsolatedTestRunner.prefix="test-"+UUID.randomUUID()+"-";context=new IsolatedTestRunner.IsolatedContext(target);
    }
    @After public void cleanup(){IsolatedTestRunner.directory=null;for(File f:dir.listFiles())f.delete();dir.delete();}
    private interface Check{boolean get();}
    private void waitFor(Check check)throws Exception{
        long until=android.os.SystemClock.elapsedRealtime()+45000;
        while(android.os.SystemClock.elapsedRealtime()<until){AtomicReference<Boolean> result=new AtomicReference<>(false);instrumentation.runOnMainSync(()->result.set(check.get()));if(result.get())return;Thread.sleep(60);}fail("UI state did not arrive");
    }
    private List<View> views(){List<View> all=new ArrayList<>();walk(activityRule.getActivity().getWindow().getDecorView(),all);return all;}
    private void walk(View view,List<View> all){all.add(view);if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++)walk(g.getChildAt(i),all);}}
    private TextView label(int resource){String text=activityRule.getActivity().getString(resource);for(View v:views())if(v instanceof TextView && ((TextView)v).getText().toString().equals(text))return (TextView)v;return null;}
    private void click(int resource){instrumentation.runOnMainSync(()->{TextView v=label(resource);assertNotNull(v);v.performClick();});}
    private EditText hint(int resource){String hint=activityRule.getActivity().getString(resource);for(View v:views())if(v instanceof EditText && ((EditText)v).getHint()!=null && ((EditText)v).getHint().toString().equals(hint))return (EditText)v;return null;}
    private void login(String password){instrumentation.runOnMainSync(()->{EditText gate=activityRule.getActivity().findViewById(R.id.gate_password);assertNotNull(gate);gate.setText(password);label(R.string.unlock).performClick();});}
    @Test public void oneFieldRoutesNotesAndBackgroundSavesThenLocks()throws Exception{
        Vault main=new Vault(context),notes=new Vault(context,true);main.create("main app password");main.lock();notes.create("notebook password");notes.lock();
        activityRule.launchActivity(new Intent());instrumentation.runOnMainSync(()->{int count=0;for(View v:views())if(v instanceof EditText)count++;assertEquals(1,count);});
        login("notebook password");waitFor(()->label(R.string.new_note)!=null);instrumentation.runOnMainSync(()->assertNull(label(R.string.chats)));
        click(R.string.new_note);instrumentation.runOnMainSync(()->{hint(R.string.note_title).setText("UI title");hint(R.string.note_text).setText("Saved on background");activityRule.getActivity().moveTaskToBack(true);});
        waitFor(()->activityRule.getActivity().findViewById(R.id.gate_password)!=null);
        notes.unlock("notebook password");assertEquals("Saved on background",notes.snapshot().getJSONArray("notes").getJSONObject(0).getString("text"));notes.lock();
    }
    @Test public void layoutSwitchPersistsAndUsesRightAndLeftCards()throws Exception{
        Vault main=new Vault(context);main.create("main app password");
        main.update(d->{JSONObject server=new JSONObject().put("id","s").put("name","Offline").put("url","https://127.0.0.1:9/index.php").put("username","alice").put("password","test").put("enabled",false).put("proxy",new JSONObject());d.getJSONArray("servers").put(server);
            d.getJSONArray("keys").put(new JSONObject().put("id","k").put("name","Key").put("phrase","encryption phrase"));JSONObject c=AccountData.addChat(d,"UI room","k",new JSONArray().put("s"));byte[] key=Crypto.roomKey("encryption phrase","UI room");
            for(String who:new String[]{"alice","bob"}){JSONObject envelope=Crypto.encrypt(key,new JSONObject().put("kind","text").put("text",who+"-message").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));MessageIndex.merge(c.getJSONArray("messages"),new JSONObject().put("id",who).put("sender",who).put("createdAt","now").put("envelope",envelope),server);}
            Arrays.fill(key,(byte)0);
        });main.lock();
        activityRule.launchActivity(new Intent());login("main app password");waitFor(()->label(R.string.settings)!=null);click(R.string.settings);
        instrumentation.runOnMainSync(()->{for(View v:views())if(v instanceof Switch && ((Switch)v).getText().toString().equals(activityRule.getActivity().getString(R.string.chat_bubbles)))((Switch)v).setChecked(true);});
        assertTrue(context.getSharedPreferences("preferences",Context.MODE_PRIVATE).getBoolean("chat_bubbles",false));click(R.string.chats);
        waitFor(()->{for(View v:views())if(v instanceof TextView && "bob-message".equals(((TextView)v).getText().toString()))return true;return false;});
        instrumentation.runOnMainSync(()->{for(View v:views())if(v instanceof TextView){String t=((TextView)v).getText().toString();if(t.equals("alice-message")||t.equals("bob-message")){LinearLayout.LayoutParams params=(LinearLayout.LayoutParams)((View)v.getParent()).getLayoutParams();assertEquals(t.equals("alice-message")?Gravity.RIGHT:Gravity.LEFT,params.gravity);}}});
    }
    @Test public void wrongAndBlockedPasswordNeverOpensEitherDomain()throws Exception{
        Vault main=new Vault(context);main.create("main app password");main.lock();
        context.getSharedPreferences("preferences",Context.MODE_PRIVATE).edit().putLong("authLockedUntil",System.currentTimeMillis()+60000).commit();
        activityRule.launchActivity(new Intent());login("main app password");instrumentation.runOnMainSync(()->{assertNotNull(activityRule.getActivity().findViewById(R.id.gate_password));assertNull(label(R.string.settings));});
    }
}
