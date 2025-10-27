package edu.iscas.CCrashFuzzer;

import java.util.concurrent.ConcurrentHashMap;

public class RoleInfo {
    private long stableTimstamp;
    private ConcurrentHashMap<String,String> roleMap;

    public RoleInfo(long stableTimstamp, ConcurrentHashMap<String, String> roleMap){
        this.stableTimstamp = stableTimstamp;
        this.roleMap = roleMap;
    }

    public long getStableTimestamp(){
        return stableTimstamp;
    }

    public ConcurrentHashMap<String,String> getRoleMap(){
        return roleMap;
    }

    public boolean isStable(){
        return roleMap.values().stream().anyMatch(role->"leader".equals(role));
    }
}
