package com.loner.encryptedchat;
import org.json.*;
public final class MessageLayout {
    public enum Side { FULL, LEFT, RIGHT }
    private MessageLayout() {}
    public static boolean own(JSONObject message,JSONArray servers){
        if(message.optBoolean("own"))return true;
        JSONArray sources=message.optJSONArray("sources");if(sources==null)return false;
        for(int i=0;i<sources.length();i++)for(int j=0;j<servers.length();j++){
            JSONObject s=sources.optJSONObject(i),server=servers.optJSONObject(j);
            if(s.optString("serverId").equals(server.optString("id")) && !server.optString("username").isEmpty() && s.optString("sender").equals(server.optString("username")))return true;
        }return false;
    }
    public static Side side(boolean bubbles,JSONObject message,JSONArray servers){return !bubbles?Side.FULL:own(message,servers)?Side.RIGHT:Side.LEFT;}
}
