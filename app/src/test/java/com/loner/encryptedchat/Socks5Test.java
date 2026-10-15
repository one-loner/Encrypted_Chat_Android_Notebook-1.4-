package com.loner.encryptedchat;

import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import okhttp3.*;
import okhttp3.mockwebserver.*;

public class Socks5Test {
    private static int read(InputStream in)throws IOException{int b=in.read();if(b<0)throw new EOFException();return b;}
    static final class ProxyServer implements AutoCloseable {
        final ServerSocket listener=new ServerSocket(0,8,InetAddress.getLoopbackAddress());
        final ExecutorService tasks=Executors.newCachedThreadPool();
        final java.util.List<Socket> sockets=new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile String destination,user,password;final int upstream;final boolean auth,reject;volatile boolean closed;
        ProxyServer(int upstream,boolean auth,boolean reject)throws IOException{
            this.upstream=upstream;this.auth=auth;this.reject=reject;
            tasks.execute(()->{while(!closed)try{Socket socket=listener.accept();sockets.add(socket);tasks.execute(()->serve(socket));}catch(IOException e){if(!closed)throw new RuntimeException(e);}});
        }
        private void serve(Socket socket){try{
            socket.setSoTimeout(5000);InputStream in=socket.getInputStream();OutputStream out=socket.getOutputStream();
            if(read(in)!=5)throw new IOException();int count=read(in);for(int i=0;i<count;i++)read(in);
            out.write(new byte[]{5,(byte)(auth?2:0)});out.flush();
            if(auth){if(read(in)!=1)throw new IOException();byte[] u=new byte[read(in)];new DataInputStream(in).readFully(u);byte[] p=new byte[read(in)];new DataInputStream(in).readFully(p);user=new String(u,StandardCharsets.UTF_8);password=new String(p,StandardCharsets.UTF_8);out.write(new byte[]{1,(byte)(reject?1:0)});out.flush();if(reject)return;}
            if(read(in)!=5 || read(in)!=1 || read(in)!=0)throw new IOException();int type=read(in);int size=type==3?read(in):type==1?4:16;byte[] host=new byte[size];new DataInputStream(in).readFully(host);destination=type==3?new String(host,StandardCharsets.US_ASCII):InetAddress.getByAddress(host).getHostAddress();read(in);read(in);
            out.write(new byte[]{5,0,0,1,127,0,0,1,0,0});out.flush();
            if(upstream>0){Socket remote=new Socket(InetAddress.getLoopbackAddress(),upstream);sockets.add(remote);tasks.execute(()->pump(socket,remote));pump(remote,socket);}
            else{int b=read(in);out.write(b);out.flush();}
        }catch(IOException ignored){}finally{try{socket.close();}catch(IOException ignored){}}}
        private void pump(Socket from,Socket to){try{byte[] b=new byte[4096];int n;while((n=from.getInputStream().read(b))!=-1){to.getOutputStream().write(b,0,n);to.getOutputStream().flush();}}catch(IOException ignored){}finally{try{from.close();to.close();}catch(IOException ignored){}}}
        JSONObject config(String u,String p)throws Exception{return new JSONObject().put("host","127.0.0.1").put("port",listener.getLocalPort()).put("username",u).put("password",p);}
        public void close()throws IOException{closed=true;listener.close();for(Socket s:sockets)try{s.close();}catch(IOException ignored){}tasks.shutdownNow();}
    }
    @Test public void noProxyFieldsMeanDirectAndInvalidSettingsAreRejected()throws Exception{
        assertNull(ProxySettings.parse(new JSONObject()));
        for(JSONObject invalid:new JSONObject[]{new JSONObject().put("host","socks://bad").put("port",1080),new JSONObject().put("host","localhost").put("port",0),new JSONObject().put("host","localhost").put("port",1080).put("username","user"),new JSONObject().put("host","localhost").put("port",65536)}){
            try{ProxySettings.parse(invalid);fail(invalid.toString());}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void unauthenticatedTunnelUsesRemoteDns()throws Exception{
        try(ProxyServer proxy=new ProxyServer(0,false,false);Socket socket=new Socks5SocketFactory(ProxySettings.parse(proxy.config("",""))).createSocket("never-resolve.invalid",443)){
            socket.getOutputStream().write(42);assertEquals(42,socket.getInputStream().read());assertEquals("never-resolve.invalid",proxy.destination);
        }
    }
    @Test public void perClientAuthenticationNeverUsesGlobalAuthenticator()throws Exception{
        Object before=java.net.Authenticator.class.getMethod("getDefault").invoke(null);
        try(ProxyServer a=new ProxyServer(0,true,false);ProxyServer b=new ProxyServer(0,true,false)){
            ExecutorService pool=Executors.newFixedThreadPool(2);
            try{
                Future<?> one=pool.submit(()->{try(Socket s=new Socks5SocketFactory(ProxySettings.parse(a.config("alice","pass-a"))).createSocket("room.invalid",443)){s.getOutputStream().write(1);assertEquals(1,s.getInputStream().read());}catch(Exception e){throw new RuntimeException(e);}});
                Future<?> two=pool.submit(()->{try(Socket s=new Socks5SocketFactory(ProxySettings.parse(b.config("bob","pass-b"))).createSocket("room.invalid",443)){s.getOutputStream().write(2);assertEquals(2,s.getInputStream().read());}catch(Exception e){throw new RuntimeException(e);}});
                one.get(5,TimeUnit.SECONDS);two.get(5,TimeUnit.SECONDS);
                assertEquals("alice",a.user);assertEquals("pass-a",a.password);assertEquals("bob",b.user);assertEquals("pass-b",b.password);assertSame(before,java.net.Authenticator.class.getMethod("getDefault").invoke(null));
            }finally{pool.shutdownNow();}
        }
    }
    @Test public void authenticationFailureIsTypedAndContainsNoSecrets()throws Exception{
        try(ProxyServer p=new ProxyServer(0,true,true)){
            try{new Socks5SocketFactory(ProxySettings.parse(p.config("private-user","private-password"))).createSocket("room.invalid",443);fail();}
            catch(Socks5SocketFactory.ProxyException e){assertEquals("authentication",e.reason);assertFalse(e.getMessage().contains("private"));}
        }
    }
    @Test public void handshakeTimeoutIsBounded()throws Exception{
        try(ServerSocket stalled=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            ProxySettings p=ProxySettings.parse(new JSONObject().put("host","127.0.0.1").put("port",stalled.getLocalPort()));
            try(Socket socket=new Socks5SocketFactory(p).createSocket()){
                try{socket.connect(InetSocketAddress.createUnresolved("room.invalid",443),150);fail();}catch(Socks5SocketFactory.ProxyException e){assertEquals("timeout",e.reason);}
            }
        }
    }
    @Test public void okhttpApiRunsInsideTunnelWithoutResolvingOriginLocally()throws Exception{
        try(MockWebServer origin=new MockWebServer()){
            origin.enqueue(new MockResponse().setBody("{\"csrf\":\"token\"}"));origin.enqueue(new MockResponse().setBody(new JSONObject().put("chatId",Crypto.hash("Proxy room")).toString()));origin.enqueue(new MockResponse().setBody("{\"messages\":[]}"));origin.start();
            try(ProxyServer proxy=new ProxyServer(origin.getPort(),true,false)){
                ChatApi api=new ChatApi(HttpUrl.parse("http://not-in-local-dns.invalid:"+origin.getPort()+"/index.php"),ProxySettings.parse(proxy.config("u","p")));
                try{api.connect(new JSONObject().put("username","alice").put("password","server-secret"),"Proxy room");assertEquals(0,api.messages(new JSONArray()).length());assertEquals("not-in-local-dns.invalid",proxy.destination);assertEquals(3,origin.getRequestCount());}finally{api.close();}
            }
        }
    }
    @Test public void httpsAndHostnameVerificationSurviveSocksTunnel()throws Exception{
        // This self-signed certificate/private key exists exclusively in test resources.
        java.security.KeyStore keys=java.security.KeyStore.getInstance("PKCS12");
        try(InputStream file=getClass().getResourceAsStream("/socks-test-only.p12")){keys.load(file,"test-fixture-password".toCharArray());}
        javax.net.ssl.KeyManagerFactory km=javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());km.init(keys,"test-fixture-password".toCharArray());
        javax.net.ssl.TrustManagerFactory tm=javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());tm.init(keys);
        javax.net.ssl.SSLContext tls=javax.net.ssl.SSLContext.getInstance("TLS");tls.init(km.getKeyManagers(),tm.getTrustManagers(),null);
        try(MockWebServer origin=new MockWebServer()){
            origin.useHttps(tls.getSocketFactory(),false);origin.enqueue(new MockResponse().setBody("secure response"));origin.start();
            try(ProxyServer proxy=new ProxyServer(origin.getPort(),true,false);
                Socket tunnel=new Socks5SocketFactory(ProxySettings.parse(proxy.config("u","p"))).createSocket("not-in-local-dns.invalid",origin.getPort());
                javax.net.ssl.SSLSocket secure=(javax.net.ssl.SSLSocket)tls.getSocketFactory().createSocket(tunnel,"not-in-local-dns.invalid",origin.getPort(),true)){
                javax.net.ssl.SSLParameters parameters=secure.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");secure.setSSLParameters(parameters);secure.setSoTimeout(3000);secure.startHandshake();
                secure.getOutputStream().write("GET / HTTP/1.1\r\nHost: not-in-local-dns.invalid\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));secure.getOutputStream().flush();
                assertTrue(new BufferedReader(new InputStreamReader(secure.getInputStream(),StandardCharsets.US_ASCII)).readLine().contains("200"));assertEquals("not-in-local-dns.invalid",proxy.destination);
            }
        }
    }
}
