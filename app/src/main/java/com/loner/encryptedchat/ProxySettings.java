package com.loner.encryptedchat;
import java.nio.charset.StandardCharsets;
import org.json.*;
public final class ProxySettings {
    public final String host,username,password;public final int port;
    private ProxySettings(String host,int port,String username,String password){this.host=host;this.port=port;this.username=username;this.password=password;}
    public static ProxySettings parse(JSONObject value) throws Exception {
        if(value==null || value.length()==0)return null;
        String host=value.optString("host").trim(),user=value.optString("username"),pass=value.optString("password");
        int port=value.optInt("port",-1);
        if(host.isEmpty() && user.isEmpty() && pass.isEmpty() && !value.has("port"))return null;
        if(host.startsWith("[") && host.endsWith("]"))host=host.substring(1,host.length()-1);
        if(host.isEmpty() || host.length()>253 || !host.matches("[A-Za-z0-9.:-]+") || host.contains("://") || port<1 || port>65535)throw new IllegalArgumentException("Invalid SOCKS5 host or port");
        if(host.contains(":")){if(!(java.net.InetAddress.getByName(host) instanceof java.net.Inet6Address))throw new IllegalArgumentException("Invalid SOCKS5 IPv6 host");}
        if(user.isEmpty()!=pass.isEmpty() || user.getBytes(StandardCharsets.UTF_8).length>255 || pass.getBytes(StandardCharsets.UTF_8).length>255)throw new IllegalArgumentException("SOCKS5 username and password must both contain 1–255 UTF-8 bytes");
        return new ProxySettings(host,port,user,pass);
    }
}
