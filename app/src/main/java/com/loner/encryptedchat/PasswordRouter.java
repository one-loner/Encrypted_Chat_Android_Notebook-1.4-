package com.loner.encryptedchat;
/** Routing contains no password storage and never falls back after a successful route. */
public final class PasswordRouter {
    public enum Mode{MAIN,NOTEBOOK,INVALID}
    public interface Probe{boolean verify(String password)throws Exception;}
    private PasswordRouter(){}
    public static Mode resolve(String password,Probe main,Probe notebook)throws Exception{
        if(password==null || password.isEmpty())return Mode.INVALID;
        boolean m=false,n=false;
        try{m=main.verify(password);}catch(Exception ignored){}
        try{n=notebook.verify(password);}catch(Exception ignored){}
        if(m && n)throw new IllegalStateException("Passwords must differ");
        if(m)return Mode.MAIN;if(n)return Mode.NOTEBOOK;
        // A damaged unrelated container cannot prevent access to a valid domain.
        return Mode.INVALID;
    }
    public static long delayAfterFailure(int attempts){return attempts<3?1000:Math.min(30000,1000L*(1L<<Math.min(5,attempts-2)));}
}
