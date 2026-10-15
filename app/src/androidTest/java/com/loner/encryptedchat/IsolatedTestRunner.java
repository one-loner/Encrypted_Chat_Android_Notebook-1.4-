package com.loner.encryptedchat;
import android.app.Activity;
import android.content.*;
import android.content.res.Configuration;
import android.os.Bundle;
import androidx.test.runner.AndroidJUnitRunner;
import java.io.File;

/** Only in the test APK: production MainActivity gets no test hooks/backdoors. */
public class IsolatedTestRunner extends AndroidJUnitRunner {
    static File directory;static String prefix;
    static class IsolatedContext extends ContextWrapper {
        IsolatedContext(Context base){super(base);}
        @Override public File getNoBackupFilesDir(){return directory;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences(prefix+name,mode);}
        @Override public Context createConfigurationContext(Configuration config){return new IsolatedContext(super.createConfigurationContext(config));}
    }
    public static class IsolatedActivity extends MainActivity {
        @Override protected void attachBaseContext(Context base){super.attachBaseContext(new IsolatedContext(base));}
    }
    @Override public Activity newActivity(ClassLoader loader,String name,Intent intent)throws InstantiationException,IllegalAccessException,ClassNotFoundException {
        if(name.equals(MainActivity.class.getName()) && directory!=null)return new IsolatedActivity();return super.newActivity(loader,name,intent);
    }
}
