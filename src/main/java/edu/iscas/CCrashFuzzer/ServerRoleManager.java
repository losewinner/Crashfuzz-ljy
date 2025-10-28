package edu.iscas.CCrashFuzzer;

import edu.iscas.CCrashFuzzer.utils.FileUtil;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//ljy--这个类主要做以下的事情：
//1. 截住每轮故障注入后的日志。(成功）
//2. 截取每轮故障注入后的IOPoint，因为每次运行后IOPoint有更新。
//3. 更新每次的IO序列的ServerRole

public class ServerRoleManager {
    public static ConcurrentHashMap<String,String> currentServerRoleMap = new ConcurrentHashMap<>();

    public static String logDir;
    public static ArrayList<File> serverLogs = new ArrayList<>();

    // 1. 定义正则表达式：匹配时间戳和my state状态
    // 1. 时间戳正则（通用，匹配 yyyy-MM-dd HH:mm:ss,SSS）
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},\\d{3})");


    // 1. 匹配选举通知日志：Notification: my state:XXX;（如LOOKING/FOLLOWING/LEADING）
    private static final Pattern NOTIFICATION_STATE_PATTERN = Pattern.compile("Notification: my state:([A-Z]+);");

    // 2. 匹配纯角色切换日志：Peer state changed: XXX（如following/leading，转为大写）
    private static final Pattern PEER_SIMPLE_STATE_PATTERN = Pattern.compile("Peer state changed: ([a-z]+)");

    // 3. 匹配角色+阶段变更日志：Peer state changed: following - XXX（如following-discovery）
    private static final Pattern PEER_STAGE_STATE_PATTERN = Pattern.compile("Peer state changed: (following - [a-z]+)");

    // 4. 匹配显式角色日志：QuorumPeer@1465] - XXX（如FOLLOWING/LOOKING，纯大写角色）
    private static final Pattern EXPLICIT_ROLE_PATTERN = Pattern.compile("QuorumPeer@1465\\] - ([A-Z]+)");


    //ljy--截取每轮故障注入后的monitor文件夹，包含了各个log/zkData文件
    public static void collectTmpMonitorLogs(ArrayList<String> logInfo,String runInfoPath, String testID){
        String monitorSource = runInfoPath+ FileUtil.monitorDir;
        String backupDir = "./crashfuzz-outputs/monitor_backup/"+testID;
        File sourceDir = new File(monitorSource);
        File destDir = new File(backupDir);
        if(sourceDir.exists()&&sourceDir.isDirectory()){
            try{
                //1. 复制monitor文件夹到backupdir
                FileUtils.copyDirectory(sourceDir,destDir);
                logInfo.add(Stat.log("Monitor logs copied to: "+ backupDir));

                //2. 剪切各个服务器logs目录下的文件到testID根目录
                Collection<File> outFiles = FileUtils.listFiles(
                        destDir,
                        new String[]{"out"},
                        true
                );

                for(File outFile : outFiles){
                    String relativePath = destDir.toURI().relativize(outFile.toURI()).getPath();
                    if(relativePath.contains("/logs/")){
                        File targetFile = new File(destDir, outFile.getName());
                        if(targetFile.exists()){
                            FileUtils.deleteQuietly(targetFile);
                        }

                        FileUtils.moveFile(outFile, targetFile);
                        logInfo.add(Stat.log("Moved .out file: " + outFile.getAbsolutePath() + " -> " + targetFile.getAbsolutePath()));
                    }


                }
                logDir = backupDir;

            }catch(IOException e){
                logInfo.add(Stat.log("Failed to copy monitor logs:" + e.getMessage()));
                e.printStackTrace();
            }
        }
    }

    //ljy--遍历五个日志文件，抓取诸如：
    //2025-10-28 16:45:45,262 [myid:4] - INFO  [WorkerReceiver[myid=4]:FastLeaderElection$Messenger$WorkerReceiver@389] -
    // Notification: my state:LOOKING; n.sid:4, n.state:LOOKING, n.leader:4, n.round:0x1, n.peerEpoch:0x0, n.zxid:0x0, message format version:0x2, n.config version:0x0
    //的日志信息；
    //TODO： 主要提取两个东西：
    //1. 时间---“20245-10-28 16：45：45，262”
    //2. 状态---“my state”
    // 2. 核心方法：提取日志并生成Map（仅记录状态变化）
    //例子：
    //2025-10-28 16:45:45,262 -> C1ZK4:LOOKING
    //2025-10-28 16:45:46,059 -> C1ZK4:FOLLOWING
    public static Map<String, String> extractStateMap(String logFilePath, String zkServer) throws IOException {
        Map<String, String> stateMap = new HashMap<>();
        String logFileRealPath = logDir+File.separator+logFilePath;
        File logFile = new File(logFileRealPath);
        if (!logFile.exists()) {
            throw new IllegalArgumentException("日志文件不存在：" + logFileRealPath);
        }

        String lastState = null; // 用于去重：保存上一条记录的状态
        // 遍历每一行日志，逐一匹配所有角色变更规则
        for (String logLine : FileUtils.readLines(logFile, "UTF-8")) {
            String timestamp = extractTimestamp(logLine); // 提取时间戳（通用逻辑）
            String state = null; // 提取的角色/状态

            // 分支1：匹配选举通知日志（如Notification: my state:LOOKING;）
            if (logLine.contains("Notification: my state:")) {
                state = matchPattern(logLine, NOTIFICATION_STATE_PATTERN);
            }
            // 分支2：匹配角色+阶段变更日志（如Peer state changed: following - discovery）
            else if (logLine.contains("Peer state changed: following - ")) {
                state = matchPattern(logLine, PEER_STAGE_STATE_PATTERN);
            }
            // 分支3：匹配纯角色切换日志（如Peer state changed: following）
            else if (logLine.contains("Peer state changed: ")) {
                state = matchPattern(logLine, PEER_SIMPLE_STATE_PATTERN);
                if (state != null) {
                    state = state.toUpperCase(); // 转为大写（如following → FOLLOWING）
                }
            }
            // 分支4：匹配显式角色日志（如QuorumPeer@1465] - FOLLOWING）
            else if (logLine.contains("QuorumPeer@1465] - ")) {
                state = matchPattern(logLine, EXPLICIT_ROLE_PATTERN);
            }

            // 去重逻辑：仅当时间戳、状态非空，且与上一条状态不同时记录
            if (timestamp != null && state != null) {
                if (lastState == null || !state.equals(lastState)) {
                    String key = timestamp;
                    String value = zkServer+":"+ state; // 服务器标识固定为C1ZK4（可动态提取）
                    stateMap.put(key, value);
                    lastState = state; // 更新上一条状态，用于下一轮去重
                    System.out.printf("提取成功：%s -> %s%n", key, value);
                }
            }
        }
        return stateMap;
    }

    // -------------------------- 工具方法：提取时间戳 + 正则匹配 --------------------------
    /**
     * 提取日志行的时间戳
     */
    private static String extractTimestamp(String logLine) {
        Matcher matcher = TIMESTAMP_PATTERN.matcher(logLine);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 通用正则匹配方法：传入日志行和Pattern，返回匹配到的结果（第一组）
     */
    private static String matchPattern(String logLine, Pattern pattern) {
        Matcher matcher = pattern.matcher(logLine);
        return matcher.find() ? matcher.group(1) : null;
    }

    //ljy--根据日志文件更新新的IO序列
    public static void updateIOPointServerRole(QueueEntry entry) throws IOException, ParseException {
        //ljy--设置读取的文件
        ArrayList<String> zkLogsPath = new ArrayList<>();
        for(int i = 1;i<6;i++){
            String zkLogFile = "zookeeper--server-C1ZK"+i+".out";
            zkLogsPath.add(zkLogFile);
        }

        ArrayList<NavigableMap<Long,String>> zkStateMaps = new ArrayList<>();
        for(int i = 0;i<zkLogsPath.size();i++){
            Map<String,String> stateMap = extractStateMap(zkLogsPath.get(i),("C1ZK"+(i+1)));
            zkStateMaps.add(getSortedStateMap(stateMap));
        }

        //更新entry中每个IOPoint
        for(IOPoint iop:entry.ioSeq){
            //ljy--首先查看是哪个节点的IO操作
            String[] ipSegments = iop.ip.split("\\.");
            int zkID = Integer.parseInt(ipSegments[ipSegments.length-1])-1; //172.30.0.2 对应C1ZK1
            NavigableMap<Long,String> zkLog = zkStateMaps.get(zkID-1);

            //ljy--查看第一个Map的时间戳，对比当前IO操作的时间戳大小，更新操作对应的节点的角色
            Map.Entry<Long,String> latestEntry = zkLog.floorEntry(iop.TIMESTAMP);
            if(latestEntry !=null){
                String[] roleSegments = latestEntry.getValue().split(":");
                iop.serverRole = roleSegments[roleSegments.length-1];
            }
            else{
                //若IOpoint时间线早于所有日志状态，标记为looking
                iop.serverRole = "LOOKING";
            }
        }

    }

    //时间转时间戳函数
    public static long logTimeToTimestamp(String logTime) throws ParseException {
        // 定义日志时间格式（注意逗号分隔毫秒）
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS");
        // 若日志时间包含时区，需指定（如UTC或本地时区）
        sdf.setTimeZone(TimeZone.getDefault()); // 或TimeZone.getTimeZone("UTC")
        Date date = sdf.parse(logTime);
        return date.getTime(); // 返回与IOPoint.TIMESTAMP一致的毫秒级时间戳
    }

    //提取的stateMap转换为按时间戳排序的TreeMap
    public static NavigableMap<Long,String> getSortedStateMap(Map<String,String> stateMap) throws ParseException {
        NavigableMap<Long,String> sortedMap = new TreeMap<>();
        for(Map.Entry<String ,String> entry: stateMap.entrySet()){
            long time = logTimeToTimestamp(entry.getKey());
            String state = entry.getValue();
            sortedMap.put(time,state);
        }
        return sortedMap;
    }
}
