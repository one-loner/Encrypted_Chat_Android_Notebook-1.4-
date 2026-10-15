package com.loner.encryptedchat;
import org.json.*;
import java.util.*;
public final class NotebookData {
    private NotebookData(){}
    public static JSONObject empty()throws Exception{return new JSONObject().put("schema",1).put("kind","notebook").put("notes",new JSONArray());}
    public static void validate(JSONObject d)throws Exception{
        if(d.getInt("schema")!=1 || !"notebook".equals(d.getString("kind")))throw new IllegalArgumentException("Unsupported notebook");
        JSONArray notes=d.getJSONArray("notes");Set<String> ids=new HashSet<>();int total=0;
        if(notes.length()>1000)throw new IllegalArgumentException("Notebook limit exceeded");
        for(int i=0;i<notes.length();i++){
            JSONObject n=notes.getJSONObject(i);String title=n.getString("title"),text=n.getString("text");total+=title.length()+text.length();
            if(!ids.add(n.getString("id")) || title.length()>200 || text.length()>1000000 || total>5000000)throw new IllegalArgumentException("Notebook limit exceeded");
            n.getLong("modifiedAt");
        }
    }
}
