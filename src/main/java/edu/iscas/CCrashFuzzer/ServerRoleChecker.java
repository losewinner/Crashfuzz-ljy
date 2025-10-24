package edu.iscas.CCrashFuzzer;


import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ServerRoleChecker {
    //ljy---注意，节点角色信息是会出现如下模式：
    //1. leader
    //2. follower
    //3. looking


    public static ServerRoleManager manager;

    public static ConcurrentHashMap<String,String> currentServerRoles = new ConcurrentHashMap<>();

    //ljy--读取当前节点角色信息，写入到文件中。
    public static void updateServerRoles(){
        manager = new ServerRoleManager();

        String nodeList = "172.30.0.2:11181,172.30.0.3:11181,172.30.0.4:11181,172.30.0.5:11181,172.30.0.6:11181";

        currentServerRoles = manager.getIpToRoleMap(nodeList);
        String file = manager.getAndSaveIpToRoleMap(nodeList);
    }


    //ljy--故障注入运行中的时候，节点角色会在中间阶段发生变化，所以需要记录发生了变化的节点以及对应的IO操作。
    //从这个IO操作开始，更新接下来IO操作的执行节点的角色，直到再遇到下一个需要变化的IO操作。
    //意思就是说， A（角色未改），B（角色未改），C（角色改变）【更新节点角色】，D（角色未改--在C的角色改变后角色未发生改变），E（角色未改）。
    public static void updateFaultSequenceRole(QueueEntry q){
        AtomicInteger fileSequence = new AtomicInteger(1);
        //ljy--初始更新
        String filepath = "ServerRole-"+fileSequence+".txt";
        currentServerRoles = ServerRoleManager.readFromFile(filepath);
        fileSequence.incrementAndGet();

        for(IOPoint iop: q.ioSeq){
            //ljy--说明有角色发生改变
            if(iop.roleChange){
                //Todo:这里iopoint的锚点设置的有些问题，可能存在leader没有改变的情况。


                //ljy--读取文件
                filepath = "ServerRole-"+fileSequence+".txt";
                currentServerRoles = ServerRoleManager.readFromFile(filepath);
                fileSequence.incrementAndGet();
            }
            //ljy--更新当前IOPoint的节点角色
            String ip = "172.30.0.2";
            iop.serverRole = currentServerRoles.get(ip);

        }
    }
}
