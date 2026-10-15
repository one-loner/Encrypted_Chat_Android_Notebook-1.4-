package com.loner.encryptedchat;
import android.content.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import javax.crypto.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** A v1 encrypted file fixture: tests the on-disk upgrade, not just JSON conversion. */
@RunWith(AndroidJUnit4.class)
public class LegacyMigrationDeviceTest {
    @Test public void versionOneFileUpgradesInPlaceWithoutPasswordOrKeyChanges()throws Exception{
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();File dir=new File(target.getNoBackupFilesDir(),"test-v1-"+UUID.randomUUID());assertTrue(dir.mkdirs());
        Context context=new ContextWrapper(target){@Override public File getNoBackupFilesDir(){return dir;}};
        try{
            String password="legacy app password",phrase="legacy encryption phrase";Vault seed=new Vault(context);seed.create(password);seed.lock();
            JSONObject envelope=Crypto.encrypt(Crypto.roomKey(phrase,"Old room"),"{\"kind\":\"text\",\"text\":\"Kept history\"}".getBytes(StandardCharsets.UTF_8));
            JSONObject legacy=new JSONObject().put("schema",1).put("account",new JSONObject().put("url","https://example.com/index.php").put("username","alice").put("password","old server password"))
                .put("keys",new JSONArray().put(new JSONObject().put("id","original-key-id").put("name","Original name").put("phrase",phrase)))
                .put("chats",new JSONArray().put(new JSONObject().put("name","Old room").put("keyId","original-key-id").put("messages",new JSONArray().put(new JSONObject().put("id","original-message-id").put("sender","alice").put("createdAt","old date").put("fileId","old-file-id").put("envelope",envelope)))));
            byte[] master=Crypto.random(32),salt=Crypto.random(32),derived=Crypto.pbkdf(password,salt,Crypto.ITERATIONS);
            JSONObject header=new JSONObject().put("salt",Crypto.b64(salt)).put("iterations",Crypto.ITERATIONS).put("wrapped",Crypto.encrypt(derived,master));
            JSONObject inner=new JSONObject().put("header",header).put("data",Crypto.encrypt(master,legacy.toString().getBytes(StandardCharsets.UTF_8)));
            KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,ks.getKey("encrypted-chat-vault-v1",null));
            JSONObject outer=new JSONObject().put("version",1).put("iv",Crypto.b64(cipher.getIV())).put("ciphertext",Crypto.b64(cipher.doFinal(inner.toString().getBytes(StandardCharsets.UTF_8))));
            try(FileOutputStream out=new FileOutputStream(new File(dir,"vault.enc"))){out.write(outer.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}Arrays.fill(master,(byte)0);Arrays.fill(derived,(byte)0);
            Vault upgraded=new Vault(context);upgraded.unlock(password);JSONObject data=upgraded.snapshot();assertEquals(2,data.getInt("schema"));assertEquals(phrase,data.getJSONArray("keys").getJSONObject(0).getString("phrase"));
            assertEquals("old server password",data.getJSONArray("servers").getJSONObject(0).getString("password"));JSONObject chat=data.getJSONArray("chats").getJSONObject(0),message=chat.getJSONArray("messages").getJSONObject(0);
            assertEquals("original-message-id",message.getJSONArray("sources").getJSONObject(0).getString("id"));assertEquals(envelope.toString(),message.getJSONObject("envelope").toString());upgraded.lock();
            upgraded.unlock(password);assertEquals(data.toString(),upgraded.snapshot().toString());upgraded.lock();
        }finally{for(File f:dir.listFiles())f.delete();dir.delete();}
    }
}
