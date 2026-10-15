package com.loner.encryptedchat;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Independent server workers. One unreachable destination never blocks another. */
public final class SyncEngine implements AutoCloseable {
    public interface Factory { ChatApi create(JSONObject server) throws Exception; }
    public interface Observer { void complete(Result result); }
    public static final class Result {
        public final String serverId,chatId;public final JSONArray records;public final Exception error;public final boolean sent;
        Result(String server,String chat,JSONArray records,Exception error,boolean sent){this.serverId=server;this.chatId=chat;this.records=records;this.error=error;this.sent=sent;}
    }
    private static final class Connection {volatile ChatApi api;boolean revoked;}
    private final Factory factory;
    private final Map<String,ExecutorService> workers=new HashMap<>();
    private final Map<String,Connection> connections=new ConcurrentHashMap<>();
    private final Set<String> removedChats=ConcurrentHashMap.newKeySet();
    private final Set<String> polling=ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    public SyncEngine(){this(ChatApi::new);}
    SyncEngine(Factory factory){this.factory=factory;}
    private synchronized ExecutorService worker(String server) {
        if(closed)throw new RejectedExecutionException("Locked");
        ExecutorService executor=workers.get(server);
        if(executor==null){executor=Executors.newFixedThreadPool(2);workers.put(server,executor);}return executor;
    }
    private Connection connection(String server,String chat){return connections.computeIfAbsent(server+":"+chat,k->new Connection());}
    private ChatApi ready(Connection c,JSONObject server,JSONObject chat)throws Exception{
        if(closed || removedChats.contains(chat.optString("id")))throw new IOException("Locked or removed");
        if(c.revoked)throw new ChatApi.AuthException("Session revoked; reconnect required");
        if(c.api==null){c.api=factory.create(server);try{c.api.connect(server,chat.getString("name"));}catch(Exception e){failed(c,e);throw e;}}
        if(closed){c.api.close();throw new IOException("Locked");}return c.api;
    }
    private void failed(Connection c,Exception e){if(e instanceof ChatApi.AuthException)c.revoked=true;if(c.api!=null){c.api.close();c.api=null;}}
    public void poll(JSONObject server,JSONObject chat,Observer observer)throws Exception{
        if(!server.getBoolean("enabled"))return;
        String sid=server.getString("id"),cid=chat.getString("id"),token=sid+":"+cid;
        if(!polling.add(token))return;
        try{worker(sid).execute(()->{
            JSONArray records=null;Exception error=null;Connection c=connection(sid,cid);
            try{synchronized(c){records=ready(c,server,chat).messages(MessageIndex.cacheFor(chat.getJSONArray("messages"),sid));}}
            catch(Exception e){synchronized(c){failed(c,e);}error=e;}
            finally{polling.remove(token);}
            if(!closed)observer.complete(new Result(sid,cid,records,error,false));
        });}catch(RejectedExecutionException e){polling.remove(token);if(!closed)throw e;}
    }
    public void send(JSONObject server,JSONObject chat,JSONObject envelope,boolean reconcile,Observer observer)throws Exception{
        if(!server.getBoolean("enabled"))return;
        String sid=server.getString("id"),cid=chat.getString("id");
        try{worker(sid).execute(()->{
            JSONArray records=null;Exception error=null;Connection c=connection(sid,cid);boolean sent=false;
            try{synchronized(c){
                ChatApi api=ready(c,server,chat);
                if(reconcile)records=api.messages(MessageIndex.cacheFor(chat.getJSONArray("messages"),sid));
                if(records==null || !MessageIndex.containsEnvelope(records,envelope))api.send(envelope);
                sent=true;
            }}catch(Exception e){synchronized(c){failed(c,e);}error=e;}
            if(!closed)observer.complete(new Result(sid,cid,records,error,sent));
        });}catch(RejectedExecutionException e){if(!closed)throw e;}
    }
    public void probe(JSONObject server,Observer observer)throws Exception{
        if(!server.getBoolean("enabled"))return;String sid=server.getString("id"),token="probe:"+sid;
        if(!polling.add(token))return;
        try{worker(sid).execute(()->{
            Exception error=null;Connection c=connection(sid,"connection-probe");
            try{synchronized(c){ready(c,server,new JSONObject().put("name","AndroidConnectionCheck")).close();c.api=null;}}
            catch(Exception e){synchronized(c){failed(c,e);c.revoked=false;}error=e;}
            finally{polling.remove(token);}
            if(!closed)observer.complete(new Result(sid,"connection-probe",null,error,false));
        });}catch(RejectedExecutionException e){polling.remove(token);if(!closed)throw e;}
    }
    public void forget(String chatId){
        removedChats.add(chatId);
        for(Map.Entry<String,Connection> entry:connections.entrySet())if(entry.getKey().endsWith(":"+chatId)){
            ChatApi api=entry.getValue().api;if(api!=null)api.close();
        }
    }
    public void reconnect(String chatId){
        for(Map.Entry<String,Connection> item:connections.entrySet())if(item.getKey().endsWith(":"+chatId)){
            Connection c=item.getValue(); // cancel blocked I/O before taking its monitor
            if(c.api!=null)c.api.close();
            synchronized(c){c.api=null;c.revoked=false;}
        }
    }
    @Override public synchronized void close(){
        closed=true;
        for(Connection c:connections.values())if(c.api!=null)c.api.close();
        for(ExecutorService e:workers.values())e.shutdownNow();workers.clear();connections.clear();polling.clear();
    }
}
