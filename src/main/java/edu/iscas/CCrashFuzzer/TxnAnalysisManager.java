package edu.iscas.CCrashFuzzer;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TxnAnalysisManager {
    //ljy--用于收集日志中各个线程属于什么样的事务的
    public List<Map<String,List<String>>> TxnToClassNames;

    // 需要跳过的FAVTrigger标识
    private static final String FAVTRIGGER_FLAG = "[FAVTrigger]";
    // INFO级别日志的固定前缀（含空格，需与日志格式一致）
    private static final String INFO_FLAG = " - INFO  ";

    /**
     * 提取INFO后第一组完整[]内的@前类名（解决所有干扰场景）
     * @param logLine 单条ZooKeeper日志
     * @return @前的完整类名，失败返回null
     */
    public static String extractTargetFromLogLine(String logLine) {
        // 步骤1：跳过含FAVTrigger的日志
        if (logLine.contains(FAVTRIGGER_FLAG)) {
            return null;
        }

        // 步骤2：定位INFO位置，只处理INFO日志
        int infoStartIdx = logLine.indexOf(INFO_FLAG);
        if (infoStartIdx == -1) {
            return null;
        }

        // 步骤3：找INFO后第一组[]的左括号[
        int targetLeftBracketIdx = logLine.indexOf('[', infoStartIdx + INFO_FLAG.length());
        if (targetLeftBracketIdx == -1) {
            System.out.printf("⚠️ INFO后无左括号[：%s%n", logLine);
            return null;
        }

        // 步骤4：通过嵌套层级计数，找目标[]的闭合右括号]（核心修复）
        int bracketLevel = 1; // 初始层级为1（已找到一个[）
        int targetRightBracketIdx = -1;
        // 从左括号后开始遍历，直到层级归0或遍历结束
        for (int i = targetLeftBracketIdx + 1; i < logLine.length(); i++) {
            char c = logLine.charAt(i);
            if (c == '[') {
                bracketLevel++; // 遇到[，层级+1
            } else if (c == ']') {
                bracketLevel--; // 遇到]，层级-1
                if (bracketLevel == 0) {
                    // 层级归0，找到目标[]的闭合右括号
                    targetRightBracketIdx = i;
                    break;
                }
            }
        }
        // 未找到闭合右括号（日志格式异常）
        if (targetRightBracketIdx == -1) {
            System.out.printf("⚠️ INFO后无匹配的右括号]：%s%n", logLine);
            return null;
        }

        // 步骤5：提取目标[]内的完整内容（无尾部干扰）
        String bracketContent = logLine.substring(targetLeftBracketIdx + 1, targetRightBracketIdx).trim();

        // 步骤6：找最后一个冒号:（避免前面的冒号干扰）
        int lastColonIdx = bracketContent.lastIndexOf(':');
        if (lastColonIdx == -1) {
            System.out.printf("⚠️ []内无冒号:：%s → 内容：%s%n", logLine, bracketContent);
            return null;
        }

        // 步骤7：找第一个@（@前即为目标类名）
        int atIdx = bracketContent.indexOf('@');
        if (atIdx == -1 || atIdx <= lastColonIdx) {
            System.out.printf("⚠️ []内无@或@在冒号前：%s → 内容：%s%n", logLine, bracketContent);
            return null;
        }

        // 步骤8：截取“冒号后@前”的完整类名
        return bracketContent.substring(lastColonIdx + 1, atIdx).trim();
    }

    /**
     * 批量提取日志文件中的目标类名（去重+统计）
     * @param logFilePath 日志路径
     * @return 去重后的类名列表
     * @throws IOException 读取异常
     */
    public static List<String> batchExtractFromLogFile(String logFilePath) throws IOException {
        List<String> targetList = new ArrayList<>();
        int totalLines = 0;
        int processedLines = 0;
        int skippedLines = 0;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(logFilePath), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                totalLines++;
                // 统计跳过的FAVTrigger日志
                if (line.contains(FAVTRIGGER_FLAG)) {
                    skippedLines++;
                    continue;
                }
                // 提取目标内容
                String target = extractTargetFromLogLine(line);
                if (target != null && !target.isEmpty()) {
                    targetList.add(target);
                    processedLines++;
                }
            }
            // 打印统计信息
            System.out.printf("📊 日志处理统计：%n");
            System.out.printf("  - 日志总行数：%d%n", totalLines);
            System.out.printf("  - 跳过FAVTrigger行数：%d%n", skippedLines);
            System.out.printf("  - 有效INFO行数（提取成功）：%d%n", processedLines);
            System.out.printf("  - 最终提取类名数（去重后）：%d%n", new java.util.LinkedHashSet<>(targetList).size());
        }

        // 去重（保留首次出现顺序）
        return new ArrayList<>(new java.util.LinkedHashSet<>(targetList));
    }

    //ljy--分类函数，类似如下分类方式，但比下面还要更精细一些，后面打算将数据同步事务再划分的细一点，
    // 可划分为写请求，读请求，选举，其他事务。
    //1. Leader选举事务
    //├─ QuorumPeer线程
    //│  ├─ QuorumPeer@1383：切换为LOOKING状态
    //│  └─ QuorumPeer@1106：初始化currentEpoch（选举基础参数）
    //├─ FastLeaderElection线程
    //│  ├─ FastLeaderElection@944：发起新选举（My id=2，zxid=0x0）
    //│  └─ FastLeaderElection@389：处理选举通知（解析n.sid/n.leader等投票信息）
    //├─ WorkerReceiver线程
    //│  └─ FastLeaderElection$Messenger$WorkerReceiver@389：接收其他节点的投票通知
    //└─ QuorumConnectionThread线程
    //   └─ QuorumCnxManager@383：连接C1ZK3/C1ZK4/C1ZK5（选举通信）
    //
    //2. 数据同步事务
    //├─ Follower线程
    //│  ├─ Follower@75：记录选举耗时（1639ms）
    //│  └─ Follower@125：读取Leader发送的数据包
    //├─ Learner线程
    //│  ├─ Learner$LeaderConnector@408：连接Leader（C1ZK3/172.30.0.4:11888）
    //│  ├─ Learner@551：从Leader获取数据差异（diff from 0x0）
    //│  └─ Learner@701：接收UPTODATE（同步完成）
    //├─ SyncThread线程
    //│  └─ FileTxnLog@284：创建事务日志log.100000001
    //└─ CommitProcessor线程
    //   ├─ CommitProcessor@438：初始化28个worker线程
    //   └─ LearnerSessionTracker@116：提交全局会话0x2005a793bf20000
    public static List<Map<String,List<String>>> getTxnToClassNameGroup(List<String> ClassNameFromLog){

        return null;
    }

}
