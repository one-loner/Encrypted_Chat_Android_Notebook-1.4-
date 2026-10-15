package com.loner.encryptedchat;
import org.json.*;
import java.security.MessageDigest;
import okio.ByteString;

/** Identity is the complete authenticated wire envelope, never plaintext or time. */
public final class MessageIndex {
    private MessageIndex() {}
    public static String identity(JSONObject envelope) throws Exception {
        byte[] iv=Crypto.unb64(envelope.getString("iv")), cipher=Crypto.unb64(envelope.getString("ciphertext"));
        if(iv.length!=12 || cipher.length<16)throw new IllegalArgumentException("Invalid message envelope");
        MessageDigest hash=MessageDigest.getInstance("SHA-256");hash.update(iv);hash.update(cipher);return ByteString.of(hash.digest()).hex();
    }
    public static boolean merge(JSONArray messages,JSONObject incoming,JSONObject server) throws Exception {
        JSONObject record=new JSONObject(incoming.toString());JSONObject source=new JSONObject().put("serverId",server.getString("id"));
        for(String field:new String[]{"id","fileId","sender","createdAt"})if(record.has(field))source.put(field,record.get(field));
        record.put("sources",new JSONArray().put(source));
        if(!server.optString("username").isEmpty() && record.optString("sender").equals(server.optString("username")))record.put("own",true);
        if(record.has("envelope")){
            try{record.put("identity",identity(record.getJSONObject("envelope")));}
            catch(Exception e){record.put("invalidEnvelope",true).put("identity","invalid:"+Crypto.hash(record.getJSONObject("envelope").toString()));}
        }
        return mergeUnified(messages,record);
    }
    public static boolean mergeUnified(JSONArray messages,JSONObject record) throws Exception {
        JSONObject match=null;String id=record.optString("identity");
        for(int i=0;i<messages.length();i++){
            JSONObject existing=messages.getJSONObject(i);
            if(!id.isEmpty() && id.equals(existing.optString("identity")) || (id.isEmpty() || existing.optString("identity").isEmpty()) && sameSource(existing,record)) {match=existing;break;}
        }
        if(match==null){messages.put(new JSONObject(record.toString()));return true;}
        String before=match.toString();
        if(record.has("envelope")){match.put("envelope",record.getJSONObject("envelope"));match.put("identity",record.getString("identity"));}
        if(record.optBoolean("own"))match.put("own",true);
        JSONArray sources=match.optJSONArray("sources");if(sources==null){sources=new JSONArray();match.put("sources",sources);}
        JSONArray added=record.optJSONArray("sources");
        if(added!=null)for(int i=0;i<added.length();i++){
            JSONObject source=added.getJSONObject(i);boolean exists=false;
            for(int j=0;j<sources.length();j++){JSONObject prior=sources.getJSONObject(j);if(prior.optString("serverId").equals(source.optString("serverId")) && prior.optString("id").equals(source.optString("id"))){exists=true;break;}}
            if(!exists)sources.put(source);
        }
        return !before.equals(match.toString());
    }
    private static boolean sameSource(JSONObject a,JSONObject b){
        JSONArray as=a.optJSONArray("sources"),bs=b.optJSONArray("sources");if(as==null || bs==null)return false;
        for(int i=0;i<as.length();i++)for(int j=0;j<bs.length();j++){
            JSONObject x=as.optJSONObject(i),y=bs.optJSONObject(j);
            if(!x.optString("id").isEmpty() && x.optString("serverId").equals(y.optString("serverId")) && x.optString("id").equals(y.optString("id")))return true;
        }
        return false;
    }
    public static JSONArray cacheFor(JSONArray messages,String serverId) throws Exception {
        JSONArray out=new JSONArray();
        for(int i=0;i<messages.length();i++){
            JSONObject m=messages.getJSONObject(i);JSONArray sources=m.optJSONArray("sources");if(sources==null)continue;
            for(int j=0;j<sources.length();j++){
                JSONObject s=sources.getJSONObject(j);if(!serverId.equals(s.optString("serverId")))continue;
                JSONObject copy=new JSONObject(s.toString());if(m.has("envelope"))copy.put("envelope",m.getJSONObject("envelope"));out.put(copy);
            }
        }return out;
    }
    public static boolean containsEnvelope(JSONArray records,JSONObject envelope) throws Exception {
        String id=identity(envelope);for(int i=0;i<records.length();i++)if(id.equals(identity(records.getJSONObject(i).getJSONObject("envelope"))))return true;return false;
    }
}
