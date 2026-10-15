package com.loner.encryptedchat;
import org.junit.Test;
import org.json.*;
import okhttp3.mockwebserver.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class SyncEngineTest {
    private MockResponse json(JSONObject body){return new MockResponse().setBody(body.toString());}
    private void login(MockWebServer s,String room)throws Exception{s.enqueue(new MockResponse().setBody("{\"csrf\":\"token\"}"));s.enqueue(json(new JSONObject().put("chatId",Crypto.hash(room))));}
    @Test public void slowFailedServerDoesNotDelaySuccessfulDeliveryToAnother()throws Exception{
        try(MockWebServer slow=new MockWebServer();MockWebServer fast=new MockWebServer()){
            slow.enqueue(new MockResponse().setResponseCode(503).setBody("{\"error\":\"down\"}").setBodyDelay(5,TimeUnit.SECONDS));login(fast,"Common room");fast.enqueue(new MockResponse().setBody("{\"ok\":true}"));slow.start();fast.start();
            JSONObject d=FeatureDataTest.account(),c=AccountData.addChat(d,"Common room","k1",new JSONArray().put("a").put("b")),wire=FeatureDataTest.envelope("message");
            SyncEngine engine=new SyncEngine(server->new ChatApi("a".equals(server.getString("id"))?slow.url("/index.php"):fast.url("/index.php")));
            CountDownLatch delivered=new CountDownLatch(1),failed=new CountDownLatch(1);AtomicReference<SyncEngine.Result> result=new AtomicReference<>();
            try{
                engine.send(d.getJSONArray("servers").getJSONObject(0),c,wire,false,r->failed.countDown());
                engine.send(d.getJSONArray("servers").getJSONObject(1),c,wire,false,r->{result.set(r);delivered.countDown();});
                assertTrue("healthy destination must not wait for slow server",delivered.await(2,TimeUnit.SECONDS));assertTrue(result.get().sent);assertNull(result.get().error);assertEquals(1,failed.getCount());
                fast.takeRequest();fast.takeRequest();assertEquals(wire.toString(),new JSONObject(fast.takeRequest().getBody().readUtf8()).toString());
            }finally{engine.close();}
        }
    }
    @Test public void uncertainRetryReconcilesBeforePostAndDoesNotSendAgain()throws Exception{
        try(MockWebServer server=new MockWebServer()){
            login(server,"Retry room");JSONObject wire=FeatureDataTest.envelope("same queued envelope");server.enqueue(json(new JSONObject().put("messages",new JSONArray().put(FeatureDataTest.record("echo",wire,"alice").put("iv",wire.getString("iv")).put("ciphertext",wire.getString("ciphertext"))))));server.start();
            JSONObject d=FeatureDataTest.account(),c=AccountData.addChat(d,"Retry room","k1",new JSONArray().put("a"));SyncEngine engine=new SyncEngine(s->new ChatApi(server.url("/index.php")));CountDownLatch done=new CountDownLatch(1);AtomicReference<SyncEngine.Result> result=new AtomicReference<>();
            try{engine.send(d.getJSONArray("servers").getJSONObject(0),c,wire,true,r->{result.set(r);done.countDown();});assertTrue(done.await(3,TimeUnit.SECONDS));assertNull(result.get().error);assertTrue(result.get().sent);assertEquals(3,server.getRequestCount());assertEquals(1,result.get().records.length());}finally{engine.close();}
        }
    }
    @Test public void revokedSessionRequiresExplicitReconnect()throws Exception{
        try(MockWebServer server=new MockWebServer()){
            server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\":\"revoked\"}"));login(server,"Reconnect room");server.enqueue(new MockResponse().setBody("{\"messages\":[]}"));server.start();
            JSONObject d=FeatureDataTest.account(),c=AccountData.addChat(d,"Reconnect room","k1",new JSONArray().put("a")),s=d.getJSONArray("servers").getJSONObject(0);SyncEngine engine=new SyncEngine(x->new ChatApi(server.url("/index.php")));
            try{
                CountDownLatch first=new CountDownLatch(1);AtomicReference<Exception> error=new AtomicReference<>();engine.poll(s,c,r->{error.set(r.error);first.countDown();});assertTrue(first.await(3,TimeUnit.SECONDS));assertTrue(error.get() instanceof ChatApi.AuthException);
                CountDownLatch second=new CountDownLatch(1);engine.poll(s,c,r->second.countDown());assertTrue(second.await(3,TimeUnit.SECONDS));assertEquals(1,server.getRequestCount());
                engine.reconnect(c.getString("id"));CountDownLatch third=new CountDownLatch(1);engine.poll(s,c,r->{error.set(r.error);third.countDown();});assertTrue(third.await(3,TimeUnit.SECONDS));assertNull(error.get());assertEquals(4,server.getRequestCount());
            }finally{engine.close();}
        }
    }
    @Test public void closedApiCannotIssueNewRequests()throws Exception{
        try(MockWebServer server=new MockWebServer()){
            server.start();ChatApi api=new ChatApi(server.url("/index.php"));api.close();try{api.messages(new JSONArray());fail();}catch(java.io.IOException expected){}assertEquals(0,server.getRequestCount());
        }
    }
    @Test public void connectionProbeTestsLoginWithoutReadingAnyMessages()throws Exception{
        try(MockWebServer server=new MockWebServer()){
            login(server,"AndroidConnectionCheck");server.start();SyncEngine engine=new SyncEngine(s->new ChatApi(server.url("/index.php")));CountDownLatch done=new CountDownLatch(1);AtomicReference<Exception> error=new AtomicReference<>();
            try{engine.probe(FeatureDataTest.server("a","alice"),result->{error.set(result.error);done.countDown();});assertTrue(done.await(3,TimeUnit.SECONDS));assertNull(error.get());assertEquals(2,server.getRequestCount());}finally{engine.close();}
        }
    }
    @Test public void removingChatPreventsQueuedNetworkWork()throws Exception{
        try(MockWebServer server=new MockWebServer()){
            server.start();JSONObject d=FeatureDataTest.account(),chat=AccountData.addChat(d,"Removed room","k1",new JSONArray().put("a"));SyncEngine engine=new SyncEngine(s->new ChatApi(server.url("/index.php")));
            CountDownLatch done=new CountDownLatch(1);AtomicReference<Exception> error=new AtomicReference<>();
            try{engine.forget(chat.getString("id"));engine.send(d.getJSONArray("servers").getJSONObject(0),chat,FeatureDataTest.envelope("never send"),false,r->{error.set(r.error);done.countDown();});assertTrue(done.await(3,TimeUnit.SECONDS));assertNotNull(error.get());assertEquals(0,server.getRequestCount());}finally{engine.close();}
        }
    }
}
