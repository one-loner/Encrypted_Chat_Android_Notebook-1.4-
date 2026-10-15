package com.loner.encryptedchat;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

public class MainActivity extends Activity {
    private static final int EXPORT=11, IMPORT=12, ATTACH=13, DOWNLOAD=14, QR_IMAGE=15;
    private static final int MAX_BACKUP=100*1024*1024, MAX_FILE=5*1024*1024;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final AtomicInteger epoch=new AtomicInteger();
    private volatile SyncEngine engine;
    private final Set<String> inFlight=ConcurrentHashMap.newKeySet();
    private Vault notebook;
    private boolean notebookMode;
    private final Map<String,byte[]> derived=new ConcurrentHashMap<>();
    private final Map<String,JSONArray> decoded=new ConcurrentHashMap<>();
    private final Map<String,String> states=new ConcurrentHashMap<>();
    private final Set<String> sending=ConcurrentHashMap.newKeySet();
    private final Set<String> revoked=ConcurrentHashMap.newKeySet();
    private final Map<String,RoomView> roomViews=new HashMap<>();
    private final Map<String,String> drafts=new HashMap<>();
    private final java.util.List<Dialog> dialogs=new java.util.ArrayList<>();
    private Vault vault;
    private LinearLayout root, body, nav;
    private TextView status;
    private ViewPager pager;
    private JSONArray rooms=new JSONArray();
    private String page="chats", selectedRoom="";
    private boolean active, polling, external;
    private int pendingJobs;
    
    private byte[] pendingBytes;
    private String pendingImportPassword, attachmentRoom, attachmentText;
    private final Runnable externalTimeout=() -> { if (external) lock(); };
    private final Runnable tick=new Runnable() {
        public void run() {
            if (!active || !vault.open() || external) return;
            if (!notebookMode && !polling && pendingJobs==0) poll();
            handler.postDelayed(this,2000);
        }
    };
    @Override protected void attachBaseContext(Context base) {
        String lang=base.getSharedPreferences("preferences",MODE_PRIVATE).getString("language","");
        if (!lang.isEmpty()) {
            Configuration config=new Configuration(base.getResources().getConfiguration());
            config.setLocale(new Locale(lang)); base=base.createConfigurationContext(config);
        }
        super.attachBaseContext(base);
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        vault=new Vault(this); notebook=new Vault(this,true); showGate();
    }
    @Override protected void onResume() {
        super.onResume(); active=true;
        handler.removeCallbacks(tick); if (vault.open()) handler.post(tick);
    }
    @Override protected void onStop() {
        saveNoteDraftNow();super.onStop(); active=false; handler.removeCallbacks(tick);
        if (!external) lock();
    }
    @Override protected void onDestroy() {
        epoch.incrementAndGet(); closeSessions(); vault.lock();notebook.lock();
        handler.removeCallbacksAndMessages(null); io.shutdownNow(); super.onDestroy();
    }
    private int dp(int n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private TextView text(String value,int size) {
        TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(Color.rgb(27,45,41));
        t.setPadding(dp(8),dp(8),dp(8),dp(8)); t.setSaveEnabled(false); return t;
    }
    private Button button(int label,Runnable action) {
        Button b=new Button(this); b.setText(label); b.setAllCaps(false); b.setOnClickListener(v -> action.run()); return b;
    }
    private EditText field(int hint,boolean secret,String value) {
        EditText e=new EditText(this); e.setHint(hint); e.setText(value); e.setSingleLine(true); e.setSaveEnabled(false);
        e.setInputType(secret?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT);
        if (Build.VERSION.SDK_INT >= 26) e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        return e;
    }
    private void scrollInto(LinearLayout target,View content) {
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.addView(content);
        target.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    }
    private void frame(String title,boolean navigation) {
        root=column(); root.setBackgroundColor(Color.rgb(243,246,245));
        root.setPadding(dp(12),dp(8),dp(12),dp(8));
        root.setOnApplyWindowInsetsListener((v,insets)->{
            v.setPadding(dp(12)+insets.getSystemWindowInsetLeft(),dp(8)+insets.getSystemWindowInsetTop(),
                dp(12)+insets.getSystemWindowInsetRight(),dp(8)+insets.getSystemWindowInsetBottom()); return insets;
        });
        TextView heading=text(title,26); heading.setTypeface(null,Typeface.BOLD); root.addView(heading);
        status=text("",12); root.addView(status);
        body=column(); root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        if (navigation) {
            nav=row(); root.addView(nav);
            nav.addView(button(R.string.chats,()->{page="chats";showHome();}),new LinearLayout.LayoutParams(0,-2,1));
            nav.addView(button(R.string.keys,()->{page="keys";showHome();}),new LinearLayout.LayoutParams(0,-2,1));
            nav.addView(button(R.string.settings,()->{page="settings";showHome();}),new LinearLayout.LayoutParams(0,-2,1));
        }
        setContentView(root); root.requestApplyInsets();
    }
    private interface Job { void run() throws Exception; }
    private void work(Job job,Runnable success) {
        final int ticket=epoch.get(); pendingJobs++;
        if (status!=null) status.setText(R.string.wait);
        io.execute(()->{
            if (ticket!=epoch.get()) return;
            Exception failure=null;
            try { job.run(); } catch (Exception e) { failure=e; }
            final Exception result=failure;
            handler.post(()->{
                if (ticket!=epoch.get() || isDestroyed()) return;
                pendingJobs=Math.max(0,pendingJobs-1);
                if (status!=null) status.setText("");
                if (result!=null) error(result.getMessage()); else if (success!=null) success.run();
            });
        });
    }
    private void error(String message) {
        if (!active) return;
        remember(new AlertDialog.Builder(this).setTitle(R.string.error).setMessage(message==null?getString(R.string.error):message)
            .setPositiveButton(R.string.ok,null).show());
    }
    private void toast(int message) { Toast.makeText(this,message,Toast.LENGTH_SHORT).show(); }
    private JSONObject snapshot() {
        try { return vault.snapshot(); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    @android.annotation.SuppressLint("ApplySharedPref") // Persist failures before completing authentication, on the I/O worker.
    private void showGate() {
        notebookMode=false;roomViews.clear();pager=null;
        frame(getString(R.string.app_name),false);
        LinearLayout form=column();scrollInto(body,form);
        if(!vault.exists())form.addView(text(getString(R.string.vault_hint),16));
        EditText password=field(R.string.password,true,"");password.setId(R.id.gate_password);form.addView(password);
        long until=getSharedPreferences("preferences",MODE_PRIVATE).getLong("authLockedUntil",0);
        if(until>System.currentTimeMillis())status.setText(getString(R.string.password_blocked,(until-System.currentTimeMillis()+999)/1000));
        Button submit=button(R.string.unlock,()->{});form.addView(submit);
        submit.setOnClickListener(v->{
            long blocked=getSharedPreferences("preferences",MODE_PRIVATE).getLong("authLockedUntil",0)-System.currentTimeMillis();
            if(blocked>0){error(getString(R.string.password_blocked,(blocked+999)/1000));return;}
            if(pendingJobs>0){toast(R.string.wait);return;}
            String value=password.getText().toString();password.setText("");
            if(value.isEmpty()){error(getString(R.string.empty_password));return;}
            if(!vault.exists()){
                if(value.length()<12){error(getString(R.string.password_rule));return;}
                LinearLayout confirm=column();EditText repeated=field(R.string.repeat_password,true,"");confirm.addView(repeated);
                dialog(R.string.create_vault,confirm,R.string.create,()->{
                    if(!value.equals(repeated.getText().toString())){error(getString(R.string.password_rule));return;}
                    work(()->{
                        if(notebook.verifyPassword(value))throw new IOException(getString(R.string.passwords_differ));
                        vault.create(value);
                    },()->{resetFailures();openMain();});
                });return;
            }
            final PasswordRouter.Mode[] mode={PasswordRouter.Mode.INVALID};
            work(()->{
                mode[0]=PasswordRouter.resolve(value,vault::verifyPassword,notebook::verifyPassword);
                if(mode[0]==PasswordRouter.Mode.MAIN){notebook.lock();vault.unlock(value);}
                else if(mode[0]==PasswordRouter.Mode.NOTEBOOK){vault.lock();notebook.unlock(value);}
                else {
                    int attempts=getSharedPreferences("preferences",MODE_PRIVATE).getInt("authAttempts",0)+1;
                    getSharedPreferences("preferences",MODE_PRIVATE).edit().putInt("authAttempts",attempts)
                        .putLong("authLockedUntil",System.currentTimeMillis()+PasswordRouter.delayAfterFailure(attempts)).commit();
                    throw new IOException(getString(R.string.unlock_error));
                }
            },()->{resetFailures();if(mode[0]==PasswordRouter.Mode.NOTEBOOK){notebookMode=true;showNotebook();}else openMain();});
        });
    }
    private void resetFailures(){getSharedPreferences("preferences",MODE_PRIVATE).edit().remove("authAttempts").remove("authLockedUntil").apply();}
    private void openMain(){notebookMode=false;notebook.lock();engine=new SyncEngine();showHome();handler.removeCallbacks(tick);handler.post(tick);}

    private void closeSessions() {
        SyncEngine old=engine;engine=null;if(old!=null)old.close();inFlight.clear();
        for(byte[] raw:derived.values())Arrays.fill(raw,(byte)0);derived.clear();
        revoked.clear();states.clear();decoded.clear();sending.clear();
    }
    private void resetNetwork()throws Exception{
        closeSessions();if(vault.open() && !notebookMode){
            vault.update(d->{JSONArray chats=d.getJSONArray("chats");for(int i=0;i<chats.length();i++){JSONArray out=chats.getJSONObject(i).getJSONArray("outbox");for(int m=0;m<out.length();m++){JSONObject targets=out.getJSONObject(m).getJSONObject("targets");Iterator<String> ids=targets.keys();while(ids.hasNext()){JSONObject t=targets.getJSONObject(ids.next());if(!"sent".equals(t.optString("state")))t.put("state","pending").put("nextAttempt",0);}}}});
            engine=new SyncEngine();
        }
    }

    private void remember(Dialog dialog) {
        dialogs.add(dialog);
        dialog.setOnDismissListener(d -> { dialogs.remove(dialog); if(dialog.getWindow()!=null) clearInputs(dialog.getWindow().getDecorView()); });
    }
    private void clearInputs(View view) {
        if(view instanceof EditText) ((EditText)view).setText("");
        if(view instanceof ViewGroup) { ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)clearInputs(group.getChildAt(i)); }
    }
    private void lock() {
        saveNoteDraftNow();noteDraft=null;
        for(Dialog d:new ArrayList<>(dialogs)) d.dismiss(); dialogs.clear();
        epoch.incrementAndGet(); pendingJobs=0; polling=false;
        closeSessions(); vault.lock();notebook.lock();notebookMode=false;
        if(!io.isShutdown()) io.execute(()->{closeSessions();vault.lock();notebook.lock();});
        drafts.clear(); roomViews.clear();
        if (pendingBytes!=null) Arrays.fill(pendingBytes,(byte)0); pendingBytes=null;
        pendingImportPassword=null; attachmentText=null; attachmentRoom=null;
        handler.removeCallbacks(tick); handler.removeCallbacks(externalTimeout);
        external=false; showGate();
    }
    private void showHome() {
        if (!vault.open()) { showGate(); return; }
        roomViews.clear(); pager=null;
        frame(getString(R.string.app_name),true);
        if ("keys".equals(page)) showKeys(); else if ("settings".equals(page)) showSettings(); else showChats();
    }
    private void showChats() {
        JSONObject d=snapshot(); rooms=d.optJSONArray("chats");
        LinearLayout bar=row(); bar.addView(button(R.string.add_chat,this::addChat)); body.addView(bar);
        if (rooms.length()==0) { body.addView(text(getString(R.string.empty_chats),18)); return; }
        HorizontalScrollView strip=new HorizontalScrollView(this); LinearLayout tabs=row(); strip.addView(tabs); body.addView(strip);
        pager=new ViewPager(this); pager.setId(View.generateViewId());
        body.addView(pager,new LinearLayout.LayoutParams(-1,0,1));
        pager.setAdapter(new PagerAdapter() {
            public int getCount() { return rooms.length(); }
            public boolean isViewFromObject(View view,Object object) { return view==object; }
            public Object instantiateItem(ViewGroup container,int position) {
                JSONObject c=rooms.optJSONObject(position); RoomView rv=new RoomView(c.optString("id"));
                roomViews.put(rv.name,rv); container.addView(rv.layout); rv.refresh(); return rv.layout;
            }
            public void destroyItem(ViewGroup container,int pos,Object object) {
                roomViews.remove(rooms.optJSONObject(pos).optString("id")); container.removeView((View)object);
            }
        });
        for (int i=0;i<rooms.length();i++) {
            final int index=i; String name=rooms.optJSONObject(i).optString("name");
            int duplicates=0;for(int j=0;j<rooms.length();j++)if(name.equals(rooms.optJSONObject(j).optString("name")))duplicates++;
            if(duplicates>1)try{name+=" · "+key(d,rooms.optJSONObject(i).optString("keyId")).getString("name");}catch(Exception ignored){}
            Button tab=new Button(this); tab.setText(name); tab.setAllCaps(false); tab.setOnClickListener(v->pager.setCurrentItem(index,true)); tabs.addView(tab);
        }
        pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            public void onPageSelected(int pos) { selectedRoom=rooms.optJSONObject(pos).optString("id"); }
        });
        for (int i=0;i<rooms.length();i++) if (rooms.optJSONObject(i).optString("id").equals(selectedRoom)) pager.setCurrentItem(i,false);
        selectedRoom=rooms.optJSONObject(pager.getCurrentItem()).optString("id");
    }
    private JSONObject chat(JSONObject d,String id)throws Exception{return AccountData.find(d.getJSONArray("chats"),id);}
    private JSONObject key(JSONObject d,String id)throws Exception{return AccountData.key(d,id);}
    private byte[] roomKey(JSONObject d,JSONObject chat)throws Exception {
        String id=chat.getString("id");byte[] raw=derived.get(id);
        if(raw==null){raw=Crypto.roomKey(key(d,chat.getString("keyId")).getString("phrase"),chat.getString("name"));derived.put(id,raw);}return raw;
    }
    private JSONArray decryptMessages(byte[] raw,JSONArray records)throws Exception {
        JSONArray out=new JSONArray();
        for(int i=0;i<records.length();i++){
            JSONObject record=records.getJSONObject(i),item=new JSONObject(record.toString());
            try{
                String plain=new String(Crypto.decrypt(raw,record.getJSONObject("envelope")),StandardCharsets.UTF_8);JSONObject payload;
                try{payload=new JSONObject(plain);}catch(JSONException e){payload=new JSONObject().put("kind","text").put("text",plain);}
                item.put("payload",payload);
            }catch(Exception e){item.put("error",true);}out.put(item);
        }return out;
    }
    private String networkError(Exception error){
        Throwable cause=error;
        while(cause!=null){if(cause instanceof Socks5SocketFactory.ProxyException){String reason=((Socks5SocketFactory.ProxyException)cause).reason;return getString("authentication".equals(reason)||"auth_method".equals(reason)?R.string.proxy_auth_error:R.string.proxy_connect_error);}cause=cause.getCause();}
        return getString(error instanceof ChatApi.AuthException?R.string.server_auth_error:R.string.server_unreachable);
    }
    private void refreshRooms(){for(RoomView view:roomViews.values())view.refresh();}
    private void poll(){
        if(!vault.open() || notebookMode || engine==null || polling)return;polling=true;int ticket=epoch.get();SyncEngine current=engine;
        io.execute(()->{
            try{
                JSONObject d=vault.snapshot();JSONArray chats=d.getJSONArray("chats"),servers=d.getJSONArray("servers");
                for(int j=0;j<servers.length();j++){
                    JSONObject server=servers.getJSONObject(j);String sid=server.getString("id"),probe="probe:"+sid;
                    if(server.getBoolean("enabled") && !states.containsKey(probe)){
                        states.put(probe,getString(R.string.wait));current.probe(server,result->{if(ticket==epoch.get() && engine==current)states.put(probe,result.error==null?getString(R.string.connected):networkError(result.error));});
                    }
                }
                for(int i=0;i<chats.length();i++){
                    if(ticket!=epoch.get())return;JSONObject c=chats.getJSONObject(i);String cid=c.getString("id");
                    if(!decoded.containsKey(cid))decoded.put(cid,decryptMessages(roomKey(d,c),c.getJSONArray("messages")));
                    for(int j=0;j<servers.length();j++){
                        JSONObject server=servers.getJSONObject(j);if(server.getBoolean("enabled") && AccountData.contains(c.getJSONArray("serverIds"),server.getString("id")))
                            current.poll(server,c,result->onSync(ticket,server,result,null,current));
                    }
                }
                dispatchOutbox(ticket,current);
            }catch(Exception e){if(ticket==epoch.get())handler.post(()->{if(ticket==epoch.get())status.setText(R.string.offline);});}
            finally{handler.post(()->{if(ticket==epoch.get()){polling=false;refreshRooms();}});}
        });
    }
    private void dispatchOutbox(int ticket,SyncEngine current)throws Exception {
        JSONObject d=vault.snapshot();JSONArray chats=d.getJSONArray("chats"),servers=d.getJSONArray("servers");long now=System.currentTimeMillis();
        for(int i=0;i<chats.length();i++){
            JSONObject c=chats.getJSONObject(i);String cid=c.getString("id");JSONArray out=c.getJSONArray("outbox");
            for(int m=0;m<out.length();m++){
                JSONObject item=out.getJSONObject(m);String mid=item.getString("id");JSONObject targets=item.getJSONObject("targets");
                for(int j=0;j<servers.length();j++){
                    if(ticket!=epoch.get())return;JSONObject server=servers.getJSONObject(j);String sid=server.getString("id");JSONObject target=targets.optJSONObject(sid);
                    if(!server.getBoolean("enabled") || !AccountData.contains(c.getJSONArray("serverIds"),sid) || target==null || !DeliveryQueue.due(target,now))continue;
                    String token=cid+":"+mid+":"+sid;if(!inFlight.add(token))continue;boolean reconcile=target.optInt("attempts")>0;
                    vault.update(latest->{JSONObject t=AccountData.find(chat(latest,cid).getJSONArray("outbox"),mid).getJSONObject("targets").getJSONObject(sid);t.put("attempts",t.optInt("attempts")+1).put("state","inflight").put("nextAttempt",now+60000);});
                    try{current.send(server,c,item.getJSONObject("envelope"),reconcile,result->onSync(ticket,server,result,mid,current));}
                    catch(Exception e){inFlight.remove(token);throw e;}
                }
            }
        }
    }
    private void onSync(int ticket,JSONObject server,SyncEngine.Result result,String outgoing,SyncEngine current){
        if(ticket!=epoch.get() || current!=engine || io.isShutdown())return;
        try{io.execute(()->{
            if(ticket!=epoch.get() || current!=engine || !vault.open() || notebookMode)return;
            try{
                String token=result.chatId+":"+(outgoing==null?"":outgoing)+":"+result.serverId;
                if(outgoing!=null)inFlight.remove(token);
                if(result.error instanceof ChatApi.AuthException)revoked.add(result.chatId+":"+result.serverId);else if(result.error==null)revoked.remove(result.chatId+":"+result.serverId);
                states.put(result.chatId+":"+result.serverId,result.error==null?getString(R.string.connected):networkError(result.error));
                JSONObject d=vault.snapshot(),c;try{c=chat(d,result.chatId);}catch(IllegalArgumentException removed){return;}boolean changed=false;
                if(result.records!=null)for(int i=0;i<result.records.length();i++)changed|=MessageIndex.merge(c.getJSONArray("messages"),result.records.getJSONObject(i),server);
                if(outgoing!=null){
                    JSONObject target=AccountData.find(c.getJSONArray("outbox"),outgoing).getJSONObject("targets").getJSONObject(result.serverId);
                    DeliveryQueue.result(target,result.sent,result.error instanceof ChatApi.AuthException,System.currentTimeMillis());changed=true;
                }
                // A received echo also resolves an earlier ambiguous send result.
                if(result.records!=null){JSONArray out=c.getJSONArray("outbox");
                    for(int i=0;i<out.length();i++){JSONObject item=out.getJSONObject(i),t=item.getJSONObject("targets").optJSONObject(result.serverId);
                        if(t!=null && !"sent".equals(t.optString("state")) && MessageIndex.containsEnvelope(result.records,item.getJSONObject("envelope"))){DeliveryQueue.result(t,true,false,0);changed=true;}}
                }
                if(changed){vault.replace(d);decoded.put(result.chatId,decryptMessages(roomKey(d,c),c.getJSONArray("messages")));}
                handler.post(()->{if(ticket==epoch.get())refreshRooms();});
            }catch(Exception e){handler.post(()->{if(ticket==epoch.get())status.setText(R.string.sync_storage_error);});}
        });}catch(RejectedExecutionException ignored){}
    }

    private final class RoomView {
        final String name; final LinearLayout layout=column(), messages=column();
        final TextView connection=text("",12); final EditText input=field(R.string.message,false,"");
        final ScrollView scroll=new ScrollView(MainActivity.this); JSONArray lastContent; boolean lastRevoked;
        RoomView(String roomName) {
            name=roomName; input.setSingleLine(false); input.setMaxLines(4); input.setText(drafts.get(name));
            input.addTextChangedListener(new android.text.TextWatcher(){
                public void beforeTextChanged(CharSequence s,int start,int count,int after){}
                public void onTextChanged(CharSequence s,int start,int before,int count){drafts.put(name,s.toString());}
                public void afterTextChanged(android.text.Editable e){}
            });
            LinearLayout top=row(); top.addView(connection,new LinearLayout.LayoutParams(0,-2,1));
            top.addView(button(R.string.reconnect,()->work(()->{
                SyncEngine current=engine;if(current!=null)current.reconnect(name);
                vault.update(d->{JSONArray out=chat(d,name).getJSONArray("outbox");for(int i=0;i<out.length();i++){
                    JSONObject targets=out.getJSONObject(i).getJSONObject("targets");Iterator<String> it=targets.keys();while(it.hasNext()){JSONObject t=targets.getJSONObject(it.next());if(!"sent".equals(t.optString("state")))t.put("state","pending").put("nextAttempt",0);}
                }});
            },()->{if(!polling)poll();})));layout.addView(top);
            scroll.addView(messages); layout.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
            layout.addView(input); LinearLayout controls=row();
            controls.addView(button(R.string.attach,()->{
                attachmentRoom=name;attachmentText=input.getText().toString(); launchDocument(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),ATTACH);
            }),new LinearLayout.LayoutParams(0,-2,1));
            controls.addView(button(R.string.send,()->{
                String value=input.getText().toString(); if (!value.trim().isEmpty()) {
                    try { sendPayload(name,new JSONObject().put("kind","text").put("text",value),value); } catch(Exception e) {error(e.getMessage());}
                }
            }),new LinearLayout.LayoutParams(0,-2,1)); layout.addView(controls);
            layout.addView(button(R.string.chat_servers,()->editChatServers(name)));
            layout.addView(button(R.string.close_chat,()->removeChat(name)));
        }
        void refresh() {
            JSONObject account=snapshot();StringBuilder connectionText=new StringBuilder();
            try{JSONObject c=chat(account,name);JSONArray members=c.getJSONArray("serverIds"),servers=account.getJSONArray("servers");
                for(int i=0;i<servers.length();i++){JSONObject server=servers.getJSONObject(i);String sid=server.getString("id");if(AccountData.contains(members,sid))connectionText.append(server.getString("name")).append(": ").append(!server.getBoolean("enabled")?getString(R.string.disabled):states.getOrDefault(name+":"+sid,getString(R.string.offline))).append("\n");}
                connectionText.append(getString(R.string.delivery_status)).append(DeliveryQueue.summary(c));
            }catch(Exception ignored){}connection.setText(connectionText.toString());
            boolean blocked=false;
            try{JSONObject current=chat(account,name);int enabled=0,denied=0;JSONArray servers=account.getJSONArray("servers");
                for(int i=0;i<servers.length();i++){JSONObject server=servers.getJSONObject(i);if(server.getBoolean("enabled") && AccountData.contains(current.getJSONArray("serverIds"),server.getString("id"))){enabled++;if(revoked.contains(name+":"+server.getString("id")))denied++;}}
                blocked=enabled>0 && enabled==denied;
            }catch(Exception ignored){}
            JSONArray content=blocked?null:decoded.get(name); if(content==lastContent && blocked==lastRevoked && messages.getChildCount()>0) return;
            lastContent=content; lastRevoked=blocked; int previousY=scroll.getScrollY(); boolean bottom=scroll.getChildCount()==0 || messages.getHeight()-scroll.getHeight()-previousY<dp(80);
            messages.removeAllViews();
            if (content==null || content.length()==0) messages.addView(text(getString(R.string.no_messages),16));
            else for (int i=0;i<content.length();i++) {
                JSONObject m=content.optJSONObject(i); LinearLayout card=column();
                card.setPadding(dp(8),dp(4),dp(8),dp(4));
                card.setBackgroundColor(i%2==0?Color.WHITE:Color.rgb(229,240,236));
                TextView meta=text(m.optString("sender")+" · "+m.optString("createdAt")+" · "+getString(R.string.sources)+": "+(m.optJSONArray("sources")==null?0:m.optJSONArray("sources").length()),12);
                meta.setOnClickListener(v->showSources(m,account));card.addView(meta);
                if (m.optBoolean("error")) card.addView(text(getString(R.string.undecryptable),14));
                else {
                    JSONObject payload=m.optJSONObject("payload"); TextView t=text(payload.optString("text"),17); t.setTextIsSelectable(true); card.addView(t);
                    if ("file".equals(payload.optString("kind"))) {
                        card.addView(text(payload.optString("name"),14));
                        card.addView(button(R.string.download,()->{
                            try {
                                byte[] bytes=Crypto.unb64(payload.getString("data"));
                                if(bytes.length>MAX_FILE)throw new IOException(getString(R.string.file_large));
                                pendingBytes=bytes; createDocument(payload.optString("mime","application/octet-stream"),safeFilename(payload.optString("name","attachment")),DOWNLOAD);
                            } catch(Exception e) {error(e.getMessage());}
                        }));
                    }
                }
                MessageLayout.Side side=MessageLayout.side(getSharedPreferences("preferences",MODE_PRIVATE).getBoolean("chat_bubbles",false),m,account.optJSONArray("servers"));
                LinearLayout.LayoutParams position=new LinearLayout.LayoutParams(side==MessageLayout.Side.FULL?-1:Math.max(dp(180),(int)((getResources().getDisplayMetrics().widthPixels-dp(48))*0.84)),-2);
                position.gravity=side==MessageLayout.Side.RIGHT?Gravity.RIGHT:Gravity.LEFT;position.bottomMargin=dp(8);messages.addView(card,position);
            }
            if(bottom)scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN)); else scroll.post(()->scroll.scrollTo(0,previousY));
        }
    }
    private void sendPayload(String id,JSONObject payload,String sentDraft){
        if(!sending.add(id)){toast(R.string.wait);return;}int ticket=epoch.get();
        work(()->{try{
            JSONObject d=vault.snapshot(),c=chat(d,id);String mid=UUID.randomUUID().toString();payload.put("clientMessageId",mid);
            JSONObject envelope=Crypto.encrypt(roomKey(d,c),payload.toString().getBytes(StandardCharsets.UTF_8));
            try{DeliveryQueue.enqueue(d,c,envelope,mid);}catch(IllegalStateException e){throw new IOException(getString(R.string.select_servers));}vault.replace(d);decoded.put(id,decryptMessages(roomKey(d,c),c.getJSONArray("messages")));
        }catch(Exception e){sending.remove(id);throw e;}},()->{
            sending.remove(id);RoomView rv=roomViews.get(id);if(rv!=null && rv.input.getText().toString().equals(sentDraft))rv.input.setText("");else if(sentDraft.equals(drafts.get(id)))drafts.remove(id);
            toast(R.string.queued);refreshRooms();if(!polling)poll();
        });
    }
    private List<CheckBox> serverChecks(LinearLayout form,JSONArray servers,JSONArray selected){
        List<CheckBox> checks=new ArrayList<>();for(int i=0;i<servers.length();i++){
            JSONObject server=servers.optJSONObject(i);CheckBox box=new CheckBox(this);box.setText(server.optBoolean("enabled")?server.optString("name"):getString(R.string.disabled_server_label,server.optString("name")));
            box.setChecked(AccountData.contains(selected,server.optString("id")));form.addView(box);checks.add(box);
        }return checks;
    }
    private JSONArray checkedServers(JSONArray servers,List<CheckBox> boxes){JSONArray ids=new JSONArray();for(int i=0;i<boxes.size();i++)if(boxes.get(i).isChecked())ids.put(servers.optJSONObject(i).optString("id"));return ids;}
    private void addChat(){
        JSONObject d=snapshot();JSONArray servers=d.optJSONArray("servers"),keys=d.optJSONArray("keys");
        if(servers.length()==0){error(getString(R.string.account_required));return;}if(keys.length()==0){error(getString(R.string.key_required));return;}
        LinearLayout form=column();EditText name=field(R.string.chat_name,false,"");form.addView(name);Spinner spinner=new Spinner(this);
        String[] names=new String[keys.length()];for(int i=0;i<names.length;i++)names[i]=keys.optJSONObject(i).optString("name");spinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));form.addView(spinner);
        JSONArray defaults=new JSONArray();for(int i=0;i<servers.length();i++)if(servers.optJSONObject(i).optBoolean("enabled"))defaults.put(servers.optJSONObject(i).optString("id"));
        List<CheckBox> checks=serverChecks(form,servers,defaults);
        dialog(R.string.add_chat,form,R.string.open_chat,()->{
            String value=name.getText().toString().trim(),keyId=keys.optJSONObject(spinner.getSelectedItemPosition()).optString("id");JSONArray ids=checkedServers(servers,checks);final String[] created={""};
            if(value.codePointCount(0,value.length())<3 || value.codePointCount(0,value.length())>80){error(getString(R.string.chat_invalid));return;}
            work(()->{if(ids.length()==0)throw new IOException(getString(R.string.select_servers));
                vault.update(current->{JSONObject c=AccountData.addChat(current,value,keyId,ids);created[0]=c.getString("id");});
            },()->{selectedRoom=created[0];page="chats";showHome();if(!polling)poll();});
        });
    }
    private void editChatServers(String id){
        try{JSONObject d=snapshot(),c=chat(d,id);JSONArray servers=d.getJSONArray("servers");LinearLayout form=column();List<CheckBox> checks=serverChecks(form,servers,c.getJSONArray("serverIds"));
            dialog(R.string.chat_servers,form,R.string.save,()->{
                JSONArray ids=checkedServers(servers,checks);
                work(()->{if(ids.length()==0)throw new IOException(getString(R.string.select_servers));vault.update(current->chat(current,id).put("serverIds",ids));},()->{showHome();if(!polling)poll();});
            });
        }catch(Exception e){error(e.getMessage());}
    }
    private void removeChat(String id){
        remember(new AlertDialog.Builder(this).setTitle(R.string.close_chat).setMessage(R.string.close_hint).setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.delete,(v,w)->work(()->{
            vault.update(d->{JSONArray chats=d.getJSONArray("chats");for(int i=0;i<chats.length();i++)if(chats.getJSONObject(i).getString("id").equals(id)){chats.remove(i);break;}});
            SyncEngine current=engine;if(current!=null)current.forget(id);inFlight.removeIf(token->token.startsWith(id+":"));
            decoded.remove(id);byte[] raw=derived.remove(id);if(raw!=null)Arrays.fill(raw,(byte)0);
        },()->{drafts.remove(id);showHome();})).show());
    }
    private void showSources(JSONObject message,JSONObject account){
        StringBuilder details=new StringBuilder();JSONArray sources=message.optJSONArray("sources");if(sources!=null)for(int i=0;i<sources.length();i++){
            JSONObject source=sources.optJSONObject(i);String name=source.optString("serverId");try{name=AccountData.find(account.getJSONArray("servers"),name).getString("name");}catch(Exception ignored){}
            details.append(name).append(" · ").append(source.optString("sender")).append("\n").append(source.optString("createdAt")).append("\nID: ").append(source.optString("id")).append("\n\n");
        }
        details.append("SHA-256: ").append(message.optString("identity"));remember(new AlertDialog.Builder(this).setTitle(R.string.sources).setMessage(details.toString()).setPositiveButton(R.string.ok,null).show());
    }

    private void dialog(int title,View view,int action,Runnable run) {
        ScrollView scroll=new ScrollView(this); scroll.setPadding(dp(16),0,dp(16),0);scroll.addView(view);
        remember(new AlertDialog.Builder(this).setTitle(title).setView(scroll).setNegativeButton(R.string.cancel,null)
            .setPositiveButton(action,(d,w)->run.run()).show());
    }
    private void showKeys() {
        LinearLayout controls=row();controls.addView(button(R.string.new_key,this::newKey));controls.addView(button(R.string.scan_qr,()->{
            external=true;handler.postDelayed(externalTimeout,60000);
            IntentIntegrator scanner=new IntentIntegrator(this);scanner.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
            scanner.setPrompt(getString(R.string.scan_qr));scanner.setBeepEnabled(false);scanner.setOrientationLocked(false);scanner.initiateScan();
        }));body.addView(controls);
        body.addView(button(R.string.paste_qr,()->{
            LinearLayout f=column();EditText json=field(R.string.qr_text,true,"");f.addView(json);
            dialog(R.string.import_key,f,R.string.import_key,()->importKey(json.getText().toString()));
        }));
        LinearLayout list=column();scrollInto(body,list);JSONArray keys=snapshot().optJSONArray("keys");
        if(keys.length()==0)list.addView(text(getString(R.string.key_empty),16));
        for(int i=0;i<keys.length();i++) {
            JSONObject k=keys.optJSONObject(i); list.addView(text(k.optString("name"),22)); LinearLayout actions=row();list.addView(actions);
            actions.addView(button(R.string.qr,()->showQr(k)));actions.addView(button(R.string.rename,()->renameKey(k)));
            list.addView(button(R.string.reveal,()->remember(new AlertDialog.Builder(this).setTitle(k.optString("name"))
                .setMessage(getString(R.string.qr_hint)+"\n\n"+k.optString("phrase")).setPositiveButton(R.string.ok,null).show())));
            list.addView(button(R.string.delete,()->deleteKey(k)));
        }
    }
    private void newKey() {
        LinearLayout form=column(); form.addView(text(getString(R.string.key_hint),14));
        EditText name=field(R.string.key_name,false,"");EditText phrase=field(R.string.phrase,true,"");form.addView(name);form.addView(phrase);
        dialog(R.string.new_key,form,R.string.save,()->{
            String n=name.getText().toString().trim(),p=phrase.getText().toString();
            if(n.isEmpty() || n.length()>100 || (!p.isEmpty() && p.length()<12) || p.length()>1024){error(getString(R.string.key_invalid));return;}
            addKey(n,p.isEmpty()?Crypto.b64(Crypto.random(32)):p);
        });
    }
    private void addKey(String name,String phrase) {
        work(()->{
            JSONObject d=vault.snapshot();d.getJSONArray("keys").put(new JSONObject().put("id",UUID.randomUUID().toString()).put("name",name).put("phrase",phrase));vault.replace(d);
        },this::showHome);
    }
    private void renameKey(JSONObject k) {
        LinearLayout form=column();EditText n=field(R.string.key_name,false,k.optString("name"));form.addView(n);
        dialog(R.string.rename,form,R.string.save,()->{
            String name=n.getText().toString().trim();if(name.isEmpty() || name.length()>100){error(getString(R.string.key_invalid));return;}
            work(()->{JSONObject d=vault.snapshot();key(d,k.getString("id")).put("name",name);vault.replace(d);},this::showHome);
        });
    }
    private void deleteKey(JSONObject k) {
        remember(new AlertDialog.Builder(this).setTitle(R.string.delete).setMessage(k.optString("name")).setNegativeButton(R.string.cancel,null)
            .setPositiveButton(R.string.delete,(dialog,which)->work(()->{
                JSONObject d=vault.snapshot();JSONArray c=d.getJSONArray("chats");
                for(int i=0;i<c.length();i++)if(c.getJSONObject(i).getString("keyId").equals(k.getString("id")))throw new IOException(getString(R.string.key_in_use));
                JSONArray keys=d.getJSONArray("keys");for(int i=0;i<keys.length();i++)if(keys.getJSONObject(i).getString("id").equals(k.getString("id"))){keys.remove(i);break;}vault.replace(d);
            },this::showHome)).show());
    }
    private void importKey(String raw) {
        try {
            if(raw.length()>4096)throw new IllegalArgumentException();JSONObject qr=new JSONObject(raw);
            if(!"encrypted-chat-key".equals(qr.getString("format")) || qr.getInt("version")!=1 || !"phrase".equals(qr.getString("type")))throw new IllegalArgumentException();
            String name=qr.getString("name").trim(),phrase=qr.getString("phrase");
            if(name.isEmpty() || name.length()>100 || phrase.length()<12 || phrase.length()>1024)throw new IllegalArgumentException();
            remember(new AlertDialog.Builder(this).setTitle(R.string.import_key).setMessage(name+"\n\n"+getString(R.string.qr_hint))
                .setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.import_key,(d,w)->addKey(name,phrase)).show());
        }catch(Exception e){error(getString(R.string.qr_invalid));}
    }
    private void showQr(JSONObject k) {
        try {
            JSONObject data=new JSONObject().put("format","encrypted-chat-key").put("version",1).put("type","phrase")
                .put("name",k.getString("name")).put("phrase",k.getString("phrase"));
            Map<EncodeHintType,Object> hints=new EnumMap<>(EncodeHintType.class);hints.put(EncodeHintType.CHARACTER_SET,"UTF-8");hints.put(EncodeHintType.MARGIN,4);
            BitMatrix matrix=new MultiFormatWriter().encode(data.toString(),BarcodeFormat.QR_CODE,768,768,hints);
            Bitmap bitmap=Bitmap.createBitmap(768,768,Bitmap.Config.ARGB_8888);
            for(int y=0;y<768;y++)for(int x=0;x<768;x++)bitmap.setPixel(x,y,matrix.get(x,y)?Color.BLACK:Color.WHITE);
            LinearLayout form=column();form.addView(text(getString(R.string.qr_hint),14));
            ImageView image=new ImageView(this);image.setImageBitmap(bitmap);image.setAdjustViewBounds(true);form.addView(image,new LinearLayout.LayoutParams(-1,dp(300)));
            form.addView(button(R.string.export_qr,()->{
                ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,out);pendingBytes=out.toByteArray();createDocument("image/png","chat-key.png",QR_IMAGE);
            }));dialog(R.string.qr,form,R.string.ok,()->{});
        }catch(Exception e){error(getString(R.string.qr_invalid));}
    }
    private void showSettings(){
        LinearLayout form=column();scrollInto(body,form);
        Switch bubbles=new Switch(this);bubbles.setText(R.string.chat_bubbles);bubbles.setChecked(getSharedPreferences("preferences",MODE_PRIVATE).getBoolean("chat_bubbles",false));
        bubbles.setOnCheckedChangeListener((v,checked)->getSharedPreferences("preferences",MODE_PRIVATE).edit().putBoolean("chat_bubbles",checked).apply());form.addView(bubbles);
        form.addView(button(R.string.servers,this::showServers));form.addView(button(R.string.language,this::language));
        form.addView(button(R.string.change_password,this::changePassword));form.addView(button(R.string.export,this::exportAccount));form.addView(button(R.string.restore,this::importAccount));
        if(!notebook.exists())form.addView(button(R.string.setup_notebook,this::setupNotebook));
        form.addView(button(R.string.lock,this::lock));
    }
    private void showServers(){
        frame(getString(R.string.servers),true);LinearLayout form=column();scrollInto(body,form);form.addView(button(R.string.add_server,()->editServer(null)));
        JSONArray servers=snapshot().optJSONArray("servers");
        for(int i=0;i<servers.length();i++){
            JSONObject server=servers.optJSONObject(i);form.addView(text(server.optString("name")+"\n"+server.optString("url")+"\n"+server.optString("username"),18));
            Switch enabled=new Switch(this);enabled.setText(R.string.enabled);enabled.setChecked(server.optBoolean("enabled"));
            enabled.setOnCheckedChangeListener((v,checked)->work(()->{
                vault.update(d->AccountData.find(d.getJSONArray("servers"),server.getString("id")).put("enabled",checked));resetNetwork();
            },()->{showServers();if(!polling)poll();}));form.addView(enabled);form.addView(text(states.getOrDefault("probe:"+server.optString("id"),""),13));
            form.addView(button(R.string.test_connection,()->probeServer(server)));
            form.addView(button(R.string.edit,()->editServer(server)));
        }
    }
    private void probeServer(JSONObject server){
        SyncEngine current=engine;if(current==null)return;int ticket=epoch.get();
        try{if(!server.getBoolean("enabled")){toast(R.string.disabled);return;}
            current.probe(server,result->{if(ticket==epoch.get() && engine==current)handler.post(()->{
                if(ticket!=epoch.get() || engine!=current)return;
                String message=result.error==null?getString(R.string.connected):networkError(result.error);states.put("probe:"+server.optString("id"),message);
                if(result.error!=null)error(message);else toast(R.string.connected);
            });});
        }catch(Exception e){error(networkError(e));}
    }
    private void editServer(JSONObject original){
        JSONObject server=original==null?new JSONObject():original,proxy=server.optJSONObject("proxy");if(proxy==null)proxy=new JSONObject();LinearLayout form=column();form.addView(text(getString(R.string.multi_server_hint),14));
        EditText name=field(R.string.server_name,false,server.optString("name")),url=field(R.string.server_url,false,server.optString("url")),user=field(R.string.username,false,server.optString("username")),password=field(R.string.server_password,true,server.optString("password"));
        form.addView(name);form.addView(url);form.addView(user);form.addView(password);
        EditText host=field(R.string.proxy_host,false,proxy.optString("host")),port=field(R.string.proxy_port,false,proxy.has("port")?proxy.optString("port"):""),proxyUser=field(R.string.proxy_username,false,proxy.optString("username")),proxyPass=field(R.string.proxy_password,true,proxy.optString("password"));port.setInputType(InputType.TYPE_CLASS_NUMBER);
        form.addView(text(getString(R.string.proxy_hint),14));form.addView(host);form.addView(port);form.addView(proxyUser);form.addView(proxyPass);
        Switch enabled=new Switch(this);enabled.setText(R.string.enabled);enabled.setChecked(server.optBoolean("enabled",true));form.addView(enabled);
        dialog(R.string.servers,form,R.string.save,()->{
            try{
                String normalized=ChatApi.endpoint(url.getText().toString()).toString(),n=name.getText().toString().trim(),u=user.getText().toString().trim();
                if(n.isEmpty() || n.length()>100 || !u.matches("[a-zA-Z0-9_.-]{1,40}") || password.getText().length()==0)throw new IllegalArgumentException(getString(R.string.server_invalid));
                JSONObject p=new JSONObject();String h=host.getText().toString().trim();
                if(!h.isEmpty() || port.length()>0 || proxyUser.length()>0 || proxyPass.length()>0){try{p.put("host",h).put("port",Integer.parseInt(port.getText().toString())).put("username",proxyUser.getText().toString()).put("password",proxyPass.getText().toString());ProxySettings.parse(p);}catch(Exception e){throw new IOException(getString(R.string.proxy_invalid));}}
                JSONObject next=new JSONObject().put("name",n).put("url",normalized).put("username",u).put("password",password.getText().toString()).put("proxy",p).put("enabled",enabled.isChecked());
                boolean replacement=original!=null && (!normalized.equals(original.optString("url")) || !u.equals(original.optString("username")));
                next.put("id",original==null || replacement?UUID.randomUUID().toString():original.getString("id"));
                work(()->{
                    vault.update(d->AccountData.saveServer(d,original==null?null:original.getString("id"),next));resetNetwork();
                },()->{showServers();if(!polling)poll();});
            }catch(Exception e){error(e.getMessage());}
        });
    }

    private void language() {
        String[] labels={getString(R.string.system_language),"English","Русский"};String[] codes={"","en","ru"};
        remember(new AlertDialog.Builder(this).setTitle(R.string.language).setItems(labels,(d,i)->{
            getSharedPreferences("preferences",MODE_PRIVATE).edit().putString("language",codes[i]).apply();lock();recreate();
        }).show());
    }
    private void changePassword() {
        LinearLayout form=column();EditText old=field(R.string.current_password,true,""),p=field(R.string.password,true,""),repeat=field(R.string.repeat_password,true,"");
        form.addView(old);form.addView(p);form.addView(repeat);
        dialog(R.string.change_password,form,R.string.save,()->{
            String previous=old.getText().toString(),next=p.getText().toString();
            if(next.length()<12 || !next.equals(repeat.getText().toString())){error(getString(R.string.password_rule));return;}
            work(()->{
                Vault check=new Vault(this);try{check.unlock(previous);}catch(Exception e){throw new IOException(getString(R.string.unlock_error));}finally{check.lock();}
                if(notebook.verifyPassword(next))throw new IOException(getString(R.string.passwords_differ));
                vault.setPassword(next);
            },()->toast(R.string.saved));
        });
    }
    private void exportAccount() {
        LinearLayout form=column();form.addView(text(getString(R.string.backup_hint),14));
        EditText p=field(R.string.export_password,true,""),r=field(R.string.repeat_password,true,"");form.addView(p);form.addView(r);
        dialog(R.string.export,form,R.string.export,()->{
            String password=p.getText().toString();if(password.length()<12 || !password.equals(r.getText().toString())){error(getString(R.string.password_rule));return;}
            work(()->{
                byte[] bytes=Crypto.exportAccount(vault.snapshot(),password).toString(2).getBytes(StandardCharsets.UTF_8);
                if(bytes.length>MAX_BACKUP)throw new IOException(getString(R.string.export_failed));pendingBytes=bytes;
            },()->createDocument("application/json","encrypted-chat-account.json",EXPORT));
        });
    }
    private void importAccount() {
        LinearLayout form=column();form.addView(text(getString(R.string.import_hint),14));EditText p=field(R.string.import_password,true,"");form.addView(p);
        dialog(R.string.restore,form,R.string.restore,()->{
            pendingImportPassword=p.getText().toString();launchDocument(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE),IMPORT);
        });
    }
    private void createDocument(String mime,String filename,int request) {
        launchDocument(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(mime).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,filename),request);
    }
    private void launchDocument(Intent intent,int request) {
        external=true;handler.removeCallbacks(tick);handler.removeCallbacks(externalTimeout);handler.postDelayed(externalTimeout,60000);
        try {startActivityForResult(intent,request);}catch(ActivityNotFoundException e){external=false;handler.removeCallbacks(externalTimeout);error(e.getMessage());}
    }
    private String safeFilename(String raw) { return raw.replaceAll("[\\\\/\\p{Cntrl}]","_"); }
    private byte[] readUri(Uri uri,int limit) throws Exception {
        try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            if(in==null)throw new IOException("Cannot open file");byte[] buffer=new byte[8192];int n;
            while((n=in.read(buffer))!=-1){if(out.size()+n>limit)throw new IOException(getString(limit==MAX_FILE?R.string.file_large:R.string.export_failed));out.write(buffer,0,n);}return out.toByteArray();
        }
    }
    private String uriName(Uri uri) {
        try(android.database.Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(c!=null && c.moveToFirst())return safeFilename(c.getString(0));
        }catch(Exception ignored){}return "attachment";
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);external=false;handler.removeCallbacks(externalTimeout);
        IntentResult qr=IntentIntegrator.parseActivityResult(request,result,data);
        if(qr!=null){if(vault.open() && qr.getContents()!=null)importKey(qr.getContents());return;}
        if(!vault.open())return;
        handler.removeCallbacks(tick);handler.post(tick);
        if(result!=RESULT_OK || data==null || data.getData()==null){pendingBytes=null;pendingImportPassword=null;attachmentRoom=null;attachmentText=null;return;}
        Uri uri=data.getData();
        if(request==EXPORT || request==DOWNLOAD || request==QR_IMAGE) {
            byte[] bytes=pendingBytes;pendingBytes=null;if(bytes==null)return;
            work(()->{
                try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("Cannot write file");out.write(bytes);}
                finally{Arrays.fill(bytes,(byte)0);}
            },()->toast(R.string.saved));
        }else if(request==IMPORT) {
            String password=pendingImportPassword;pendingImportPassword=null;
            work(()->{
                try {
                    JSONObject restored=Crypto.importAccount(new JSONObject(new String(readUri(uri,MAX_BACKUP),StandardCharsets.UTF_8)),password);
                    Vault.validate(restored);vault.replace(restored);resetNetwork();
                }catch(Exception e){throw new IOException(getString(R.string.import_failed));}
            },()->{drafts.clear();showHome();toast(R.string.restored);if(!polling)poll();});
        }else if(request==ATTACH) {
            String name=attachmentRoom,value=attachmentText;attachmentRoom=null;attachmentText=null;
            if(name==null)return;
            final JSONObject[] payload=new JSONObject[1];
            work(()->{
                byte[] bytes=readUri(uri,MAX_FILE);String mime=getContentResolver().getType(uri);
                payload[0]=new JSONObject().put("kind","file").put("name",uriName(uri)).put("mime",mime==null?"application/octet-stream":mime)
                    .put("data",Crypto.b64(bytes)).put("text",value==null?"":value);Arrays.fill(bytes,(byte)0);
            },()->sendPayload(name,payload[0],value==null?"":value));
        }
    }
    private JSONObject noteDraft;
    private final Runnable saveNoteDebounced=()->saveNoteDraftNow();
    private void setupNotebook(){
        if(!vault.open() || notebook.exists())return;
        LinearLayout form=column();form.addView(text(getString(R.string.notebook_setup_hint),14));
        EditText password=field(R.string.notebook_password,true,""),repeat=field(R.string.repeat_password,true,"");form.addView(password);form.addView(repeat);
        dialog(R.string.setup_notebook,form,R.string.create,()->{
            String value=password.getText().toString();if(value.length()<12 || !value.equals(repeat.getText().toString())){error(getString(R.string.password_rule));return;}
            work(()->{
                if(vault.verifyPassword(value))throw new IOException(getString(R.string.passwords_differ));
                Vault separate=new Vault(this,true);try{separate.create(value);}finally{separate.lock();}
            },()->{toast(R.string.saved);showHome();});
        });
    }
    private void showNotebook(){
        if(!notebookMode || !notebook.open() || vault.open()){lock();return;}
        saveNoteDraftNow();noteDraft=null;
        frame(getString(R.string.app_name),false);
        LinearLayout tools=row();tools.addView(button(R.string.new_note,()->editNote(null)));tools.addView(button(R.string.change_password,this::changeNotebookPassword));body.addView(tools);
        LinearLayout list=column();scrollInto(body,list);
        try{JSONArray notes=notebook.snapshot().getJSONArray("notes");
            if(notes.length()==0)list.addView(text(getString(R.string.no_notes),18));
            for(int i=notes.length()-1;i>=0;i--){JSONObject note=notes.getJSONObject(i);Button open=new Button(this);open.setAllCaps(false);open.setText(note.optString("title").isEmpty()?getString(R.string.untitled):note.optString("title"));open.setOnClickListener(v->editNote(note));list.addView(open);}
        }catch(Exception e){error(getString(R.string.notes_save_error));}
        body.addView(button(R.string.lock,this::lock));
    }
    private void editNote(JSONObject existing){
        if(!notebookMode || !notebook.open() || vault.open())return;
        try{noteDraft=existing==null?new JSONObject().put("id",UUID.randomUUID().toString()).put("title","").put("text","").put("modifiedAt",System.currentTimeMillis()):new JSONObject(existing.toString());}
        catch(Exception e){error(getString(R.string.notes_save_error));return;}
        frame(getString(R.string.app_name),false);EditText title=field(R.string.note_title,false,noteDraft.optString("title"));title.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)});body.addView(title);
        EditText content=field(R.string.note_text,false,noteDraft.optString("text"));content.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(1000000)});content.setSingleLine(false);content.setGravity(Gravity.TOP);content.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);body.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        android.text.TextWatcher watcher=new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence value,int start,int count,int after){}
            public void onTextChanged(CharSequence value,int start,int before,int count){
                try{if(noteDraft!=null)noteDraft.put("title",title.getText().toString()).put("text",content.getText().toString()).put("modifiedAt",System.currentTimeMillis());}
                catch(JSONException ignored){}handler.removeCallbacks(saveNoteDebounced);handler.postDelayed(saveNoteDebounced,700);
            }
            public void afterTextChanged(android.text.Editable value){}
        };title.addTextChangedListener(watcher);content.addTextChangedListener(watcher);
        LinearLayout controls=row();controls.addView(button(R.string.save,()->{if(saveNoteDraftNow()){noteDraft=null;showNotebook();}}));
        controls.addView(button(R.string.delete,()->remember(new AlertDialog.Builder(this).setTitle(R.string.delete).setMessage(R.string.delete_note_hint).setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.delete,(dialog,which)->{
            String id=noteDraft.optString("id");handler.removeCallbacks(saveNoteDebounced);noteDraft=null;
            work(()->notebook.update(d->{JSONArray notes=d.getJSONArray("notes");for(int i=0;i<notes.length();i++)if(notes.getJSONObject(i).getString("id").equals(id)){notes.remove(i);break;}}),this::showNotebook);
        }).show())));controls.addView(button(R.string.lock,this::lock));body.addView(controls);
        body.addView(text(getString(R.string.notes_autosave),12));
    }
    private boolean saveNoteDraftNow(){
        handler.removeCallbacks(saveNoteDebounced);
        if(noteDraft==null || !notebookMode || !notebook.open())return true;
        try{JSONObject draft=new JSONObject(noteDraft.toString());notebook.update(d->{JSONArray notes=d.getJSONArray("notes");boolean found=false;
            for(int i=0;i<notes.length();i++)if(notes.getJSONObject(i).getString("id").equals(draft.getString("id"))){notes.put(i,draft);found=true;break;}if(!found)notes.put(draft);
        });return true;}catch(Exception e){toast(R.string.notes_save_error);return false;}
    }
    private void changeNotebookPassword(){
        if(!notebookMode || !notebook.open() || vault.open())return;
        LinearLayout form=column();EditText old=field(R.string.current_notebook_password,true,""),next=field(R.string.notebook_password,true,""),repeat=field(R.string.repeat_password,true,"");form.addView(old);form.addView(next);form.addView(repeat);
        dialog(R.string.change_password,form,R.string.save,()->{
            String before=old.getText().toString(),after=next.getText().toString();
            if(after.length()<12 || !after.equals(repeat.getText().toString())){error(getString(R.string.password_rule));return;}
            work(()->{
                if(!notebook.verifyPassword(before))throw new IOException(getString(R.string.unlock_error));
                if(vault.verifyPassword(after))throw new IOException(getString(R.string.passwords_differ));notebook.setPassword(after);
            },()->toast(R.string.saved));
        });
    }
    @Override public void onBackPressed(){
        if(notebookMode){if(noteDraft!=null){if(saveNoteDraftNow()){noteDraft=null;showNotebook();}}else lock();}
        else super.onBackPressed();
    }
}
