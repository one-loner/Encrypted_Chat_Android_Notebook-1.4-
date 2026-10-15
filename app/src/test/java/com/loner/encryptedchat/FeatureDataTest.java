package com.loner.encryptedchat;
import org.junit.Test;
import org.json.*;
import java.util.*;
import static org.junit.Assert.*;

public class FeatureDataTest {
    static JSONObject server(String id,String username)throws Exception{return new JSONObject().put("id",id).put("name",id).put("url","https://"+id+".example/index.php").put("username",username).put("password","server password").put("enabled",true).put("proxy",new JSONObject());}
    static JSONObject account()throws Exception{
        JSONObject d=AccountData.empty();d.getJSONArray("servers").put(server("a","alice")).put(server("b","alice-b"));
        d.getJSONArray("keys").put(new JSONObject().put("id","k1").put("name","One").put("phrase","shared encryption phrase"));return d;
    }
    static JSONObject envelope(String text)throws Exception{return Crypto.encrypt(new byte[32],text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    static JSONObject record(String id,JSONObject e,String sender)throws Exception{return new JSONObject().put("id",id).put("sender",sender).put("createdAt","2026-10-15T12:00:00Z").put("envelope",e);}
    @Test public void legacyMigrationKeepsCredentialsKeysHistoryAndIsIdempotent()throws Exception{
        JSONObject d=account();JSONObject legacy=new JSONObject().put("schema",1).put("account",new JSONObject().put("url","https://chat.example/").put("username","alice").put("password","old secret"))
            .put("keys",d.getJSONArray("keys")).put("chats",new JSONArray().put(new JSONObject().put("name","Old room").put("keyId","k1").put("messages",new JSONArray().put(record("m1",envelope("old text"),"alice")))));
        String before=legacy.toString();JSONObject migrated=AccountData.migrate(legacy);AccountData.validate(migrated);
        assertEquals(before,legacy.toString());assertEquals("old secret",migrated.getJSONArray("servers").getJSONObject(0).getString("password"));
        assertEquals(legacy.getJSONArray("keys").toString(),migrated.getJSONArray("keys").toString());
        JSONObject m=migrated.getJSONArray("chats").getJSONObject(0).getJSONArray("messages").getJSONObject(0);
        assertTrue(m.getBoolean("own"));assertEquals("legacy-server",m.getJSONArray("sources").getJSONObject(0).getString("serverId"));
        assertEquals(migrated.toString(),AccountData.migrate(migrated).toString());
    }
    @Test public void equalNamesAndEqualSecretsMergeRegardlessOfKeyNames()throws Exception{
        JSONObject d=account();d.getJSONArray("keys").put(new JSONObject().put("id","k2").put("name","Renamed copy").put("phrase","shared encryption phrase"));
        JSONObject a=AccountData.addChat(d,"Common room","k1",new JSONArray().put("a"));
        JSONObject b=AccountData.addChat(d,"Common room","k2",new JSONArray().put("b"));
        assertSame(a,b);assertEquals(1,d.getJSONArray("chats").length());assertEquals(2,a.getJSONArray("serverIds").length());AccountData.validate(d);
    }
    @Test public void equalRoomNamesWithDifferentKeysRemainSeparate()throws Exception{
        JSONObject d=account();d.getJSONArray("keys").put(new JSONObject().put("id","k2").put("name","Other").put("phrase","other encryption phrase"));
        AccountData.addChat(d,"Common room","k1",new JSONArray().put("a"));AccountData.addChat(d,"Common room","k2",new JSONArray().put("b"));AccountData.validate(d);assertEquals(2,d.getJSONArray("chats").length());
    }
    @Test public void replicatedCiphertextDeduplicatesAndRetainsSources()throws Exception{
        JSONArray messages=new JSONArray();JSONObject wire=envelope("same message");
        MessageIndex.merge(messages,record("id-a",wire,"alice"),server("a","alice"));MessageIndex.merge(messages,record("id-b",wire,"alice-b"),server("b","alice-b"));
        MessageIndex.merge(messages,record("id-b",wire,"alice-b"),server("b","alice-b"));
        assertEquals(1,messages.length());assertEquals(2,messages.getJSONObject(0).getJSONArray("sources").length());
        assertEquals("id-b",MessageIndex.cacheFor(messages,"b").getJSONObject(0).getString("id"));
    }
    @Test public void equalPlaintextAndEqualRemoteIdsNeverCollapseDistinctMessages()throws Exception{
        JSONArray messages=new JSONArray();JSONObject a=server("a","alice");
        MessageIndex.merge(messages,record("same-id",envelope("repeated text"),"bob"),a);MessageIndex.merge(messages,record("same-id",envelope("repeated text"),"bob"),a);
        assertEquals(2,messages.length());
    }
    @Test public void unknownLegacyEnvelopeHydratesWithoutDuplicate()throws Exception{
        JSONArray messages=new JSONArray();JSONObject a=server("a","alice");MessageIndex.merge(messages,new JSONObject().put("id","legacy").put("fileId","f1").put("sender","bob"),a);
        MessageIndex.merge(messages,record("legacy",envelope("old text"),"bob").put("fileId","f1"),a);assertEquals(1,messages.length());assertTrue(messages.getJSONObject(0).has("identity"));
    }
    @Test public void bubbleLayoutUsesEveryServerIdentityAndCanBeTurnedOff()throws Exception{
        JSONObject d=account(),m=record("m",envelope("text"),"alice-b");JSONArray messages=new JSONArray();MessageIndex.merge(messages,m,d.getJSONArray("servers").getJSONObject(1));m=messages.getJSONObject(0);
        assertEquals(MessageLayout.Side.RIGHT,MessageLayout.side(true,m,d.getJSONArray("servers")));
        assertEquals(MessageLayout.Side.FULL,MessageLayout.side(false,m,d.getJSONArray("servers")));
        m=new JSONObject().put("sources",new JSONArray().put(new JSONObject().put("serverId","a").put("sender","bob")));
        assertEquals(MessageLayout.Side.LEFT,MessageLayout.side(true,m,d.getJSONArray("servers")));
    }
    @Test public void queueIsDurableAndRetriesReuseWireIdentity()throws Exception{
        JSONObject d=account(),c=AccountData.addChat(d,"Queue room","k1",new JSONArray().put("a").put("b"));JSONObject wire=envelope("queued text");DeliveryQueue.enqueue(d,c,wire,"client-id");
        JSONObject restored=AccountData.migrate(new JSONObject(d.toString()));AccountData.validate(restored);JSONObject item=restored.getJSONArray("chats").getJSONObject(0).getJSONArray("outbox").getJSONObject(0);
        assertEquals(MessageIndex.identity(wire),MessageIndex.identity(item.getJSONObject("envelope")));JSONObject target=item.getJSONObject("targets").getJSONObject("a");target.put("attempts",1);
        DeliveryQueue.result(target,false,false,1000);assertFalse(DeliveryQueue.due(target,2000));assertTrue(DeliveryQueue.due(target,3000));DeliveryQueue.result(target,true,false,3000);assertFalse(DeliveryQueue.due(target,Long.MAX_VALUE));
        assertEquals(1,c.getJSONArray("messages").length());
    }
    @Test public void disabledServersAreNotTargetsAndSettingsRemain()throws Exception{
        JSONObject d=account();d.getJSONArray("servers").getJSONObject(1).put("enabled",false);JSONObject c=AccountData.addChat(d,"Queue room","k1",new JSONArray().put("a").put("b"));JSONObject item=DeliveryQueue.enqueue(d,c,envelope("text"),"client-id");
        assertEquals(1,item.getJSONObject("targets").length());assertEquals(2,d.getJSONArray("servers").length());
    }
    @Test public void passwordRouterHasNoCrossDomainFallbackAndRejectsAmbiguity()throws Exception{
        PasswordRouter.Probe main=p->p.equals("main"),notes=p->p.equals("notes");
        assertEquals(PasswordRouter.Mode.MAIN,PasswordRouter.resolve("main",main,notes));assertEquals(PasswordRouter.Mode.NOTEBOOK,PasswordRouter.resolve("notes",main,notes));
        assertEquals(PasswordRouter.Mode.INVALID,PasswordRouter.resolve("",main,notes));assertEquals(PasswordRouter.Mode.INVALID,PasswordRouter.resolve("wrong",main,notes));
        try{PasswordRouter.resolve("same",p->true,p->true);fail();}catch(IllegalStateException expected){}
        assertEquals(PasswordRouter.Mode.MAIN,PasswordRouter.resolve("main",main,p->{throw new java.io.IOException("damaged notebook");}));
    }
    @Test public void passwordBackoffIsBounded() {assertEquals(1000,PasswordRouter.delayAfterFailure(1));assertEquals(30000,PasswordRouter.delayAfterFailure(100));}
    @Test public void addingServerIncludesExistingChatsWithoutAddingTabs()throws Exception{
        JSONObject d=account();JSONObject c=AccountData.addChat(d,"Shared room","k1",new JSONArray().put("a"));AccountData.saveServer(d,null,server("c","alice-c"));
        assertEquals(1,d.getJSONArray("chats").length());assertTrue(AccountData.contains(c.getJSONArray("serverIds"),"c"));AccountData.validate(d);
    }
    @Test public void replacingServerNeverRedirectsAnOldQueuedMessage()throws Exception{
        JSONObject d=account(),c=AccountData.addChat(d,"Shared room","k1",new JSONArray().put("a"));DeliveryQueue.enqueue(d,c,envelope("old pending message"),"pending-id");
        AccountData.saveServer(d,"a",server("new-a","new-alice"));AccountData.validate(d);
        assertFalse(AccountData.find(d.getJSONArray("servers"),"a").getBoolean("enabled"));assertTrue(AccountData.contains(c.getJSONArray("serverIds"),"new-a"));
        JSONObject targets=c.getJSONArray("outbox").getJSONObject(0).getJSONObject("targets");assertTrue(targets.has("a"));assertFalse(targets.has("new-a"));
    }
    @Test public void malformedLegacyCiphertextRemainsVisibleAsUndecryptableRatherThanBlockingMigration()throws Exception{
        JSONObject d=account();JSONArray m=new JSONArray();MessageIndex.merge(m,record("broken",new JSONObject().put("iv","bad").put("ciphertext","bad"),"bob"),server("a","alice"));
        assertEquals(1,m.length());assertTrue(m.getJSONObject(0).getBoolean("invalidEnvelope"));
    }
    @Test public void confirmedDeliveryCannotRegressOnALateFailureCallback()throws Exception{
        JSONObject target=new JSONObject().put("state","pending").put("attempts",1);DeliveryQueue.result(target,true,false,0);DeliveryQueue.result(target,false,true,1000);
        assertEquals("sent",target.getString("state"));assertFalse(DeliveryQueue.due(target,Long.MAX_VALUE));
    }
}
