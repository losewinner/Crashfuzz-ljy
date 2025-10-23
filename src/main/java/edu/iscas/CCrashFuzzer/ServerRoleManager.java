package edu.iscas.CCrashFuzzer;

import java.io.*;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

public class ServerRoleManager {
    public static ConcurrentHashMap<String,String> currentServerRoles = new ConcurrentHashMap<>();

    public static void GetServerRole(){
        String[] command = {
                "java",
                "-cp",
                "zkcases-0.jar",
                "edu.iscas.ZKCases.GetLeader",
                "172.30.0.2:11181,172.30.0.3:11181,172.30.0.4:11181,172.30.0.5:11181,172.30.0.6:11181",
                "$workdir/failTest.sh",  // 注意：$workdir需要替换为实际路径（如"/path/to/workdir/failTest.sh"）
                "10"
        };

        try {
            // 创建ProcessBuilder并设置命令
            ProcessBuilder pb = new ProcessBuilder(command);

            // 重定向输出到serverRole.txt（与原命令的> serverRoles.txt效果一致）
            pb.redirectOutput(new File("serverRoles.txt"));
            // 若需要重定向错误输出，可添加：
            // pb.redirectErrorStream(true);  // 合并错误输出到标准输出
            // 或单独重定向错误到文件：
            // pb.redirectError(new File("error.log"));

            // 启动进程
            Process process = pb.start();

            // 等待命令执行完成（可选，根据需求决定是否阻塞等待）
            int exitCode = process.waitFor();



        } catch (IOException e) {
            e.printStackTrace();  // 处理IO异常（如命令不存在、文件无法访问等）
        } catch (InterruptedException e) {
            e.printStackTrace();  // 处理线程中断异常
            Thread.currentThread().interrupt();  // 恢复中断状态
        }

        String leader = getLeaderIp("serverRoles.txt");
        currentServerRoles.computeIfAbsent(leader,k->"leader");

    }

    // 核心方法：读取文件最后一行，并提取leader的IP
    public static String getLeaderIp(String filePath) {
        // 1. 初始化变量：暂存最后一行内容，默认IP为null（表示未找到）
        String lastLine = null;
        BufferedReader reader = null;

        try {
            // 2. 创建文件读取流（指定文件路径）
            reader = new BufferedReader(new FileReader(filePath));
            String currentLine;

            // 3. 循环读取所有行，最后一行会覆盖lastLine
            while ((currentLine = reader.readLine()) != null) {
                lastLine = currentLine; // 每次读取一行，更新lastLine为当前行
            }

            // 4. 处理“文件为空”的情况
            if (lastLine == null) {
                System.out.println("错误：文件为空，未找到任何内容");
                return null;
            }

            // 5. 验证最后一行格式是否符合预期（避免文件格式异常导致解析失败）
            if (!lastLine.startsWith("Current leader is ")) {
                System.out.println("错误：最后一行格式不匹配，预期格式为'Current leader is [IP]'");
                System.out.println("实际最后一行内容：" + lastLine);
                return null;
            }

            // 6. 解析IP地址：按" is "拆分字符串，取第二部分（索引1）
            String[] parts = lastLine.split(" is ");
            if (parts.length < 2) { // 极端情况：拆分后无IP部分
                System.out.println("错误：无法从最后一行提取IP");
                return null;
            }
            String leaderIp = parts[1].trim(); // 去除可能的空格（兼容格式微小差异）
            return leaderIp;

        } catch (FileNotFoundException e) {
            System.out.println("错误：文件不存在，路径：" + filePath);
            e.printStackTrace();
        } catch (IOException e) {
            System.out.println("错误：读取文件时发生IO异常");
            e.printStackTrace();
        } finally {
            // 7. 关闭流（避免资源泄漏）
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        // 若发生异常，返回null
        return null;
    }
}
