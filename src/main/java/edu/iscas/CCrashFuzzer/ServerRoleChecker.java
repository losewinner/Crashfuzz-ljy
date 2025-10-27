package edu.iscas.CCrashFuzzer;


import com.sun.security.ntlm.Server;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ServerRoleChecker {
    //ljy---注意，节点角色信息是会出现如下模式：
    //1. leader
    //2. follower
    //3. looking


    public static ServerRoleManager manager;

    public static ConcurrentHashMap<String,String> currentServerRoles = new ConcurrentHashMap<>();

    public static long currentStableTimestamp;

    //ljy--读取当前节点角色信息，写入到文件中。
    public static void updateServerRoles(long stableTimestamp){
        manager = new ServerRoleManager();

        String nodeList = "172.30.0.2:11181,172.30.0.3:11181,172.30.0.4:11181,172.30.0.5:11181,172.30.0.6:11181";

        currentServerRoles = manager.getIpToRoleMap(nodeList);
        currentStableTimestamp = stableTimestamp;
        //ljy--文件中需要插入时间戳
        String file = manager.getAndSaveIpToRoleMap(nodeList,stableTimestamp);
    }


    //ljy--故障注入运行中的时候，节点角色会在中间阶段发生变化，所以需要记录发生了变化的节点以及对应的IO操作。
    //从这个IO操作开始，更新接下来IO操作的执行节点的角色，直到再遇到下一个需要变化的IO操作。
    //意思就是说， A（角色未改），B（角色未改），C（角色改变）【更新节点角色】，D（角色未改--在C的角色改变后角色未发生改变），E（角色未改）。
    public static void updateQueueEntryRole(QueueEntry q){
        AtomicInteger fileSequence = new AtomicInteger(1);

        for(IOPoint iop: q.ioSeq){
            //先读取文件，查看时间戳。
            int fileIndex1 = fileSequence.intValue();
            String filepath1 = manager.getOutputDir()+ File.separator+"ServerRole-"+fileIndex1+".txt";
            RoleInfo currentRoleInfo = ServerRoleManager.readFromFileWithTimestamp(filepath1);
            int fileIndex2 = fileIndex1+1;
            String filepath2 = manager.getOutputDir()+ File.separator+"ServerRole-"+fileIndex2+".txt";
            RoleInfo nextRoleInfo = ServerRoleManager.readFromFileWithTimestamp(filepath2);

            if(nextRoleInfo.getStableTimestamp()!=-2){
                if(iop.TIMESTAMP>=currentRoleInfo.getStableTimestamp() &&
                        iop.TIMESTAMP< nextRoleInfo.getStableTimestamp()){
                    //ljy--这个io操作是否在当前角色变更文件的时间戳之后发生的，同时也要在下一个角色变更文件的时间戳之前发生。
                    iop.serverRole = currentRoleInfo.getRoleMap().getOrDefault(iop.ip,"dead");
                }
                else if(iop.TIMESTAMP>= nextRoleInfo.getStableTimestamp()){
                    fileSequence.incrementAndGet();
                    iop.serverRole = nextRoleInfo.getRoleMap().getOrDefault(iop.ip,"dead");
                }
                else{
                    iop.serverRole = "unknown";
                }
            }
            else { //说明遍历到最后一个文件了，
                if (iop.TIMESTAMP >= currentRoleInfo.getStableTimestamp()) {
                    iop.serverRole = currentRoleInfo.getRoleMap().getOrDefault(iop.ip, "dead");
                }
                else{
                    int fileIndex = fileSequence.intValue()-1;
                    String filepath = "ServerRole-"+fileIndex+".txt";
                    RoleInfo beforeRoleInfo = ServerRoleManager.readFromFileWithTimestamp(filepath2);
                    iop.serverRole = beforeRoleInfo.getRoleMap().getOrDefault(iop.ip,"dead");
                }
            }



        }
    }
}
