package com.loner.encryptedchat;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Persist before fan-out. Retries reuse precisely the original IV and ciphertext. */
public final class DeliveryQueue {
    private DeliveryQueue(){}
    public static JSONObject enqueue(JSONObject account,JSONObject chat,JSONObject envelope,String clientId)throws Exception{
        JSONObject targets=new JSONObject();String sender="";
        JSONArray servers=account.getJSONArray("servers"),members=chat.getJSONArray("serverIds");
        for(int i=0;i<servers.length();i++){
            JSONObject server=servers.getJSONObject(i);String sid=server.getString("id");
            if(server.getBoolean("enabled") && AccountData.contains(members,sid)){
                targets.put(sid,new JSONObject().put("state","pending").put("attempts",0).put("nextAttempt",0));
                if(sender.isEmpty())sender=server.getString("username");
            }
        }
        if(targets.length()==0)throw new IllegalStateException("No enabled servers for this chat");
        JSONObject item=new JSONObject().put("id",clientId).put("envelope",envelope).put("targets",targets);
        chat.getJSONArray("outbox").put(item);
        SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.ROOT);format.setTimeZone(TimeZone.getTimeZone("UTC"));
        MessageIndex.mergeUnified(chat.getJSONArray("messages"),new JSONObject().put("identity",MessageIndex.identity(envelope)).put("envelope",envelope).put("sender",sender).put("createdAt",format.format(new Date())).put("own",true).put("sources",new JSONArray()));
        return item;
    }
    public static boolean due(JSONObject target,long now){return !"sent".equals(target.optString("state")) && !"auth".equals(target.optString("state")) && now>=target.optLong("nextAttempt");}
    public static void result(JSONObject target,boolean sent,boolean auth,long now)throws Exception{
        if(!sent && "sent".equals(target.optString("state")))return; // Confirmation is monotonic across racing callbacks.
        int attempts=target.optInt("attempts");target.put("state",sent?"sent":auth?"auth":"unknown");
        target.put("nextAttempt",sent?0:now+Math.min(60000L,2000L*(1L<<Math.min(5,Math.max(0,attempts-1)))));
    }
    public static String summary(JSONObject chat)throws Exception{
        int pending=0,sent=0,auth=0;JSONArray out=chat.getJSONArray("outbox");
        for(int i=0;i<out.length();i++){
            JSONObject targets=out.getJSONObject(i).getJSONObject("targets");Iterator<String> it=targets.keys();
            while(it.hasNext()){String state=targets.getJSONObject(it.next()).optString("state");if("sent".equals(state))sent++;else{pending++;if("auth".equals(state))auth++;}}
        }return sent+"/"+(sent+pending)+(auth>0?" · auth "+auth:"");
    }
}
