package com.loner.encryptedchat;

import javax.net.SocketFactory;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Per-client RFC1928/1929, remote destination DNS. No global Authenticator. */
public final class Socks5SocketFactory extends SocketFactory {
    public static final class ProxyException extends IOException {
        public final String reason;
        ProxyException(String reason){super("SOCKS5: "+reason);this.reason=reason;}
    }
    private final ProxySettings proxy;
    public Socks5SocketFactory(ProxySettings settings){if(settings==null)throw new IllegalArgumentException();proxy=settings;}
    @Override public Socket createSocket(){return new Tunnel();}
    @Override public Socket createSocket(String host,int port)throws IOException {Socket s=createSocket();s.connect(InetSocketAddress.createUnresolved(host,port),15000);return s;}
    @Override public Socket createSocket(InetAddress host,int port)throws IOException{return createSocket(host.getHostAddress(),port);}
    @Override public Socket createSocket(String host,int port,InetAddress local,int localPort)throws IOException{Socket s=createSocket();s.bind(new InetSocketAddress(local,localPort));s.connect(InetSocketAddress.createUnresolved(host,port),15000);return s;}
    @Override public Socket createSocket(InetAddress host,int port,InetAddress local,int localPort)throws IOException{return createSocket(host.getHostAddress(),port,local,localPort);}
    private static int read(InputStream in)throws IOException{int b=in.read();if(b<0)throw new ProxyException("unexpected_eof");return b;}
    private static void skip(InputStream in,int n)throws IOException{for(int i=0;i<n;i++)read(in);}
    private final class Tunnel extends Socket {
        Tunnel(){super(Proxy.NO_PROXY);}
        @Override public void connect(SocketAddress address)throws IOException{connect(address,15000);}
        @Override public void connect(SocketAddress address,int timeout)throws IOException {
            if(!(address instanceof InetSocketAddress))throw new ProxyException("invalid_destination");
            InetSocketAddress destination=(InetSocketAddress)address;
            int oldTimeout=getSoTimeout();
            try {
                super.connect(new InetSocketAddress(proxy.host,proxy.port),timeout);
                setSoTimeout(timeout>0?timeout:15000);
                InputStream in=getInputStream();OutputStream out=getOutputStream();boolean auth=!proxy.username.isEmpty();
                out.write(new byte[]{5,1,(byte)(auth?2:0)});out.flush();
                if(read(in)!=5 || read(in)!=(auth?2:0))throw new ProxyException("auth_method");
                if(auth){
                    byte[] user=proxy.username.getBytes(StandardCharsets.UTF_8),pass=proxy.password.getBytes(StandardCharsets.UTF_8);
                    out.write(1);out.write(user.length);out.write(user);out.write(pass.length);out.write(pass);out.flush();java.util.Arrays.fill(pass,(byte)0);
                    if(read(in)!=1 || read(in)!=0)throw new ProxyException("authentication");
                }
                String host=destination.getHostString();
                out.write(new byte[]{5,1,0});
                if(host.contains(":")){byte[] ip=InetAddress.getByName(host).getAddress();out.write(4);out.write(ip);}
                else if(host.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")){byte[] ip=InetAddress.getByName(host).getAddress();out.write(1);out.write(ip);}
                else {byte[] domain=IDN.toASCII(host).getBytes(StandardCharsets.US_ASCII);if(domain.length<1 || domain.length>255)throw new ProxyException("invalid_destination");out.write(3);out.write(domain.length);out.write(domain);}
                out.write((destination.getPort()>>>8)&255);out.write(destination.getPort()&255);out.flush();
                if(read(in)!=5)throw new ProxyException("protocol");int reply=read(in);if(reply!=0)throw new ProxyException("connect_"+reply);
                if(read(in)!=0)throw new ProxyException("protocol");int type=read(in);
                if(type==1)skip(in,4);else if(type==4)skip(in,16);else if(type==3)skip(in,read(in));else throw new ProxyException("protocol");skip(in,2);
                setSoTimeout(oldTimeout);
            }catch(IOException | IllegalArgumentException e){
                try{close();}catch(IOException ignored){}
                if(e instanceof ProxyException)throw (ProxyException)e;
                if(e instanceof SocketTimeoutException)throw new ProxyException("timeout");
                throw new ProxyException("unreachable");
            }
        }
    }
}
