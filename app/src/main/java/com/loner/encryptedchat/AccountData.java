package com.loner.encryptedchat;

import org.json.*;
import java.util.*;

/** Pure, idempotent migration. Does not alter any cryptographic key or secret. */
public final class AccountData {
    private AccountData() {}
    public static JSONObject empty() throws Exception {
        return new JSONObject().put("schema",2).put("account",new JSONObject()).put("servers",new JSONArray()).put("keys",new JSONArray()).put("chats",new JSONArray());
    }
    public static JSONObject migrate(JSONObject input) throws Exception {
        JSONObject d=new JSONObject(input.toString());
        int schema=d.getInt("schema");
        if(schema!=1 && schema!=2)throw new IllegalArgumentException("Unsupported account schema");
        JSONArray servers=d.optJSONArray("servers");
        if(schema==1){
            servers=new JSONArray();JSONObject old=d.getJSONObject("account");
            if(old.length()>0)servers.put(new JSONObject(old.toString()).put("id","legacy-server").put("name",old.getString("url")).put("enabled",true).put("proxy",new JSONObject()));
            d.put("servers",servers).put("schema",2);
        }
        JSONArray result=new JSONArray(), chats=d.getJSONArray("chats");
        Map<String,JSONObject> groups=new LinkedHashMap<>();
        for(int i=0;i<chats.length();i++){
            JSONObject c=chats.getJSONObject(i);
            if(!c.has("id"))c.put("id",UUID.randomUUID().toString());
            if(!c.has("serverIds")) {JSONArray ids=new JSONArray();for(int s=0;s<servers.length();s++)ids.put(servers.getJSONObject(s).getString("id"));c.put("serverIds",ids);}
            if(!c.has("outbox"))c.put("outbox",new JSONArray());
            if(schema==1){
                JSONArray old=c.getJSONArray("messages"), upgraded=new JSONArray();
                for(int m=0;m<old.length();m++){
                    JSONObject record=old.getJSONObject(m);
                    if(servers.length()>0)MessageIndex.merge(upgraded,record,servers.getJSONObject(0));
                    else upgraded.put(record);
                }
                c.put("messages",upgraded);
            }
            String group=groupKey(d,c.getString("name"),c.getString("keyId"));
            JSONObject prior=groups.get(group);
            if(prior==null){groups.put(group,c);result.put(c);}
            else {
                for(int s=0;s<c.getJSONArray("serverIds").length();s++)addUnique(prior.getJSONArray("serverIds"),c.getJSONArray("serverIds").getString(s));
                for(int m=0;m<c.getJSONArray("messages").length();m++)MessageIndex.mergeUnified(prior.getJSONArray("messages"),c.getJSONArray("messages").getJSONObject(m));
                for(int m=0;m<c.getJSONArray("outbox").length();m++)prior.getJSONArray("outbox").put(c.getJSONArray("outbox").getJSONObject(m));
            }
        }
        return d.put("chats",result);
    }
    public static JSONObject key(JSONObject d,String id) throws Exception {return find(d.getJSONArray("keys"),id);}
    public static JSONObject find(JSONArray array,String id) throws Exception {
        for(int i=0;i<array.length();i++)if(array.getJSONObject(i).getString("id").equals(id))return array.getJSONObject(i);
        throw new IllegalArgumentException("Item no longer exists");
    }
    public static String groupKey(JSONObject d,String name,String keyId) throws Exception {return Crypto.hash(new JSONArray().put(name).put(key(d,keyId).getString("phrase")).toString());}
    public static boolean contains(JSONArray values,String value){for(int i=0;i<values.length();i++)if(value.equals(values.optString(i)))return true;return false;}
    public static void addUnique(JSONArray values,String value){if(!contains(values,value))values.put(value);}
    public static JSONObject addChat(JSONObject d,String name,String keyId,JSONArray serverIds) throws Exception {
        String group=groupKey(d,name,keyId);JSONArray chats=d.getJSONArray("chats");
        for(int i=0;i<chats.length();i++){
            JSONObject c=chats.getJSONObject(i);
            if(group.equals(groupKey(d,c.getString("name"),c.getString("keyId")))){
                for(int s=0;s<serverIds.length();s++)addUnique(c.getJSONArray("serverIds"),serverIds.getString(s));return c;
            }
        }
        JSONObject c=new JSONObject().put("id",UUID.randomUUID().toString()).put("name",name).put("keyId",keyId).put("serverIds",serverIds).put("messages",new JSONArray()).put("outbox",new JSONArray());chats.put(c);return c;
    }
    public static void saveServer(JSONObject d,String originalId,JSONObject next)throws Exception{
        JSONArray servers=d.getJSONArray("servers"),chats=d.getJSONArray("chats");String nextId=next.getString("id");
        if(originalId==null){servers.put(next);for(int i=0;i<chats.length();i++)addUnique(chats.getJSONObject(i).getJSONArray("serverIds"),nextId);}
        else if(!originalId.equals(nextId)){
            find(servers,originalId).put("enabled",false);servers.put(next);
            for(int i=0;i<chats.length();i++){JSONArray ids=chats.getJSONObject(i).getJSONArray("serverIds");if(contains(ids,originalId))addUnique(ids,nextId);}
        }else for(int i=0;i<servers.length();i++)if(servers.getJSONObject(i).getString("id").equals(originalId)){servers.put(i,next);break;}
    }
    private static boolean validId(String value){return value.matches("[A-Za-z0-9_.-]{1,80}");}
    public static void validate(JSONObject d) throws Exception {
        if(d.getInt("schema")!=2)throw new IllegalArgumentException("Unsupported account schema");
        JSONArray servers=d.getJSONArray("servers"),keys=d.getJSONArray("keys"),chats=d.getJSONArray("chats");
        if(servers.length()>16 || keys.length()>1000 || chats.length()>100)throw new IllegalArgumentException("Account limit exceeded");
        Set<String> serverIds=new HashSet<>(), keyIds=new HashSet<>(), chatIds=new HashSet<>(), groups=new HashSet<>();
        for(int i=0;i<servers.length();i++){
            JSONObject s=servers.getJSONObject(i);
            if(!validId(s.getString("id")) || !serverIds.add(s.getString("id")) || s.getString("name").trim().isEmpty())throw new IllegalArgumentException("Invalid server");
            ChatApi.endpoint(s.getString("url"));s.getString("username");s.getString("password");s.getBoolean("enabled");
            ProxySettings.parse(s.optJSONObject("proxy"));
        }
        for(int i=0;i<keys.length();i++){
            JSONObject k=keys.getJSONObject(i);int n=k.getString("phrase").length();
            if(!keyIds.add(k.getString("id")) || k.getString("name").trim().isEmpty() || k.getString("name").length()>100 || n<12 || n>1024)throw new IllegalArgumentException("Invalid key");
        }
        for(int i=0;i<chats.length();i++){
            JSONObject c=chats.getJSONObject(i);String name=c.getString("name");int n=name.codePointCount(0,name.length());
            if(n<3 || n>80 || !name.equals(name.trim()) || !keyIds.contains(c.getString("keyId")) || !validId(c.getString("id")) || !chatIds.add(c.getString("id")) || !groups.add(groupKey(d,name,c.getString("keyId"))))throw new IllegalArgumentException("Invalid chat");
            JSONArray ids=c.getJSONArray("serverIds");Set<String> unique=new HashSet<>();
            for(int s=0;s<ids.length();s++)if(!serverIds.contains(ids.getString(s)) || !unique.add(ids.getString(s)))throw new IllegalArgumentException("Invalid chat servers");
            c.getJSONArray("messages");JSONArray out=c.getJSONArray("outbox");
            Set<String> outgoing=new HashSet<>();
            for(int m=0;m<out.length();m++){
                JSONObject o=out.getJSONObject(m);if(!validId(o.getString("id")) || !outgoing.add(o.getString("id")))throw new IllegalArgumentException("Duplicate outgoing ID");
                MessageIndex.identity(o.getJSONObject("envelope"));JSONObject targets=o.getJSONObject("targets");
                Iterator<String> names=targets.keys();while(names.hasNext()){String id=names.next();if(!serverIds.contains(id))throw new IllegalArgumentException("Unknown delivery target");targets.getJSONObject(id).getString("state");}
            }
        }
    }
}
