package com.loner.encryptedchat;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.*;

/** Password-wrapped random data key, plus an outer Android Keystore envelope. */
public final class Vault {
    private static final String ALIAS = "encrypted-chat-vault-v1";
    private final AtomicFile file;
    private final String alias;
    private final boolean notebook;
    private JSONObject data, header;
    private byte[] master;
    public Vault(Context context) { this(context,false); }
    public Vault(Context context,boolean notebook) {
        this.notebook=notebook;alias=notebook?"encrypted-notebook-v1":ALIAS;
        file=new AtomicFile(new File(context.getNoBackupFilesDir(),notebook?"notebook.enc":"vault.enc"));
    }
    public boolean exists() { return file.getBaseFile().exists() || new File(file.getBaseFile().getPath()+".bak").exists(); }
    public synchronized boolean open() { return master != null; }
    private SecretKey deviceKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (!ks.containsAlias(alias)) {
            if (exists()) throw new IOException("Keystore key missing");
            KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
            generator.generateKey();
        }
        return (SecretKey) ks.getKey(alias, null);
    }
    public synchronized void create(String password) throws Exception {
        if (exists()) throw new IllegalStateException("Vault already exists");
        master = Crypto.random(32);
        data = notebook ? NotebookData.empty() : AccountData.empty();
        try { setPassword(password); } catch(Exception e) {lock();throw e;}
    }
    public synchronized void unlock(String password) throws Exception {
        lock();
        JSONObject outer = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
        Cipher deviceCipher = Cipher.getInstance("AES/GCM/NoPadding");
        deviceCipher.init(Cipher.DECRYPT_MODE, deviceKey(), new GCMParameterSpec(128, Crypto.unb64(outer.getString("iv"))));
        JSONObject inner = new JSONObject(new String(deviceCipher.doFinal(Crypto.unb64(outer.getString("ciphertext"))), StandardCharsets.UTF_8));
        JSONObject h = inner.getJSONObject("header");
        if (h.getInt("iterations") != Crypto.ITERATIONS) throw new IOException("Unsupported KDF");
        byte[] derived = Crypto.pbkdf(password, Crypto.unb64(h.getString("salt")), Crypto.ITERATIONS);
        byte[] unlocked = null;
        try {
            unlocked = Crypto.decrypt(derived, h.getJSONObject("wrapped"));
            JSONObject decoded = new JSONObject(new String(Crypto.decrypt(unlocked, inner.getJSONObject("data")), StandardCharsets.UTF_8));
            JSONObject normalized=normalize(decoded);
            master = unlocked; data = normalized; header = h;
            if(!normalized.toString().equals(decoded.toString()))save();
        } catch (Exception e) { if (unlocked != null) Arrays.fill(unlocked, (byte) 0); lock();throw e; }
        finally { Arrays.fill(derived, (byte) 0); }
    }
    public synchronized void setPassword(String password) throws Exception {
        if (password.length() < 12) throw new IllegalArgumentException("Password must contain at least 12 characters");
        byte[] salt = Crypto.random(32), derived = Crypto.pbkdf(password, salt, Crypto.ITERATIONS);
        JSONObject oldHeader = header;
        try {
            header = new JSONObject().put("salt", Crypto.b64(salt)).put("iterations", Crypto.ITERATIONS)
                .put("wrapped", Crypto.encrypt(derived, master));
            save();
        } catch (Exception e) { header = oldHeader; throw e; }
        finally { Arrays.fill(derived, (byte) 0); }
    }
    public synchronized JSONObject snapshot() throws Exception {
        if (!open()) throw new IllegalStateException("Locked");
        return new JSONObject(data.toString());
    }
    public synchronized void replace(JSONObject next) throws Exception {
        JSONObject normalized=normalize(next);
        JSONObject old = data; data = normalized;
        try { save(); } catch (Exception e) { data = old; throw e; }
    }
    private void save() throws Exception {
        if (!open()) throw new IllegalStateException("Locked");
        JSONObject inner = new JSONObject().put("header", header).put("data", Crypto.encrypt(master, data.toString().getBytes(StandardCharsets.UTF_8)));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, deviceKey());
        JSONObject outer = new JSONObject().put("version", 1).put("iv", Crypto.b64(cipher.getIV()))
            .put("ciphertext", Crypto.b64(cipher.doFinal(inner.toString().getBytes(StandardCharsets.UTF_8))));
        FileOutputStream stream = null;
        try {
            stream = file.startWrite(); stream.write(outer.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(stream);
        } catch (Exception e) { if (stream != null) file.failWrite(stream); throw e; }
    }
    public synchronized void lock() {
        if (master != null) Arrays.fill(master, (byte) 0);
        master = null; data = null; header = null;
    }
    private JSONObject normalize(JSONObject d) throws Exception {
        if(notebook){NotebookData.validate(d);return new JSONObject(d.toString());}
        JSONObject migrated=AccountData.migrate(d);AccountData.validate(migrated);return migrated;
    }
    public interface Transaction {void apply(JSONObject data)throws Exception;}
    public synchronized void update(Transaction transaction)throws Exception {
        JSONObject next=snapshot();transaction.apply(next);replace(next);
    }
    /** Authenticates the wrapped master key without opening or returning domain data. */
    public synchronized boolean verifyPassword(String password)throws Exception {
        if(password==null || password.isEmpty() || !exists())return false;
        JSONObject outer=new JSONObject(new String(file.readFully(),StandardCharsets.UTF_8));
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,deviceKey(),new GCMParameterSpec(128,Crypto.unb64(outer.getString("iv"))));
        JSONObject h=new JSONObject(new String(cipher.doFinal(Crypto.unb64(outer.getString("ciphertext"))),StandardCharsets.UTF_8)).getJSONObject("header");
        if(h.getInt("iterations")!=Crypto.ITERATIONS)throw new IOException("Unsupported KDF");
        byte[] derived=Crypto.pbkdf(password,Crypto.unb64(h.getString("salt")),Crypto.ITERATIONS),probe=null;
        try{probe=Crypto.decrypt(derived,h.getJSONObject("wrapped"));return probe.length==32;}
        catch(javax.crypto.BadPaddingException e){return false;}
        finally{Arrays.fill(derived,(byte)0);if(probe!=null)Arrays.fill(probe,(byte)0);}
    }
    public static void validate(JSONObject d)throws Exception {AccountData.validate(AccountData.migrate(d));}
}
