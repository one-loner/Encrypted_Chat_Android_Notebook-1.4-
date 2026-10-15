package com.loner.encryptedchat;

import android.content.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.*;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class NotebookDeviceTest {
    private File dir;private Context context;
    @Before public void setup(){Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();dir=new File(target.getNoBackupFilesDir(),"test-notes-"+UUID.randomUUID());assertTrue(dir.mkdirs());context=new ContextWrapper(target){@Override public File getNoBackupFilesDir(){return dir;}};}
    @After public void cleanup(){for(File file:dir.listFiles())file.delete();dir.delete();}
    @Test public void independentPasswordsAndKeysProtectNotesAcrossRestart()throws Exception{
        Vault main=new Vault(context),notes=new Vault(context,true);main.create("main app password");notes.create("notebook password");
        notes.update(d->d.getJSONArray("notes").put(new JSONObject().put("id","n1").put("title","Private title").put("text","Private note text 123").put("modifiedAt",1)));
        main.lock();notes.lock();assertTrue(main.verifyPassword("main app password"));assertFalse(notes.verifyPassword("main app password"));assertFalse(main.open());assertFalse(notes.open());
        assertEquals(PasswordRouter.Mode.NOTEBOOK,PasswordRouter.resolve("notebook password",main::verifyPassword,notes::verifyPassword));notes.unlock("notebook password");assertFalse(main.open());assertEquals("Private note text 123",notes.snapshot().getJSONArray("notes").getJSONObject(0).getString("text"));notes.lock();
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=new FileInputStream(new File(dir,"notebook.enc"))){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}String disk=out.toString("UTF-8");assertFalse(disk.contains("Private note"));assertFalse(disk.contains("notebook password"));
    }
    @Test public void changingNotebookPasswordDoesNotChangeMainAndKeepsNotes()throws Exception{
        Vault main=new Vault(context),notes=new Vault(context,true);main.create("main app password");notes.create("old notebook password");notes.update(d->d.getJSONArray("notes").put(new JSONObject().put("id","n").put("title","T").put("text","Keep me").put("modifiedAt",1)));
        notes.setPassword("new notebook password");notes.lock();main.lock();assertFalse(notes.verifyPassword("old notebook password"));assertTrue(main.verifyPassword("main app password"));notes.unlock("new notebook password");assertEquals("Keep me",notes.snapshot().getJSONArray("notes").getJSONObject(0).getString("text"));notes.lock();
    }
    @Test public void backupKeepsEncryptedProxyAndAllServerSettings()throws Exception{
        Vault main=new Vault(context);main.create("main app password");
        main.update(d->{d.getJSONArray("servers").put(new JSONObject().put("id","s1").put("name","First").put("url","https://example.com/index.php").put("username","alice").put("password","server-secret").put("enabled",false).put("proxy",new JSONObject().put("host","127.0.0.1").put("port",1080).put("username","proxy-user").put("password","proxy-secret")));});
        JSONObject exported=Crypto.exportAccount(main.snapshot(),"backup password 123");main.lock();main.unlock("main app password");main.replace(Crypto.importAccount(exported,"backup password 123"));JSONObject server=main.snapshot().getJSONArray("servers").getJSONObject(0);assertFalse(server.getBoolean("enabled"));assertEquals("proxy-secret",server.getJSONObject("proxy").getString("password"));main.lock();
    }
}
