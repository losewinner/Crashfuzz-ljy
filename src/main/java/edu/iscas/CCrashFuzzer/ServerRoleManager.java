package edu.iscas.CCrashFuzzer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public class ServerRoleManager {

    //连接超时时间（毫秒）
    public int connectTimeout;
    //读取超时时间（毫秒）
    private int soTimeout;

    private final AtomicInteger fileSequence = new AtomicInteger(1);
    // 存储文件的目录（默认当前目录，可通过setter修改）
    private String outputDir = ".";

    // 匹配文件名格式：ServerRole-数字.txt
    private static final Pattern FILE_PATTERN = Pattern.compile("ServerRole-(\\d+)\\.txt");

    public ServerRoleManager(){
        this(5000,5000);
    }
    /**
     * 自定义超时设置的构造函数
     * @param connectTimeout 连接超时时间(毫秒)
     * @param soTimeout 读取超时时间(毫秒)
     */
    public ServerRoleManager(int connectTimeout, int soTimeout) {
        this.connectTimeout = connectTimeout;
        this.soTimeout = soTimeout;
    }

    /**
     * 从指定文件中读取节点角色映射
     * @param filePath 文件路径（如"./ServerRole-3.txt"）
     * @return ConcurrentHashMap<节点IP, 角色>，读取失败返回空Map
     */
    public static ConcurrentHashMap<String, String> readFromFile(String filePath) {
        ConcurrentHashMap<String, String> roleMap = new ConcurrentHashMap<>();
        File file = new File(filePath);

        // 检查文件是否存在
        if (!file.exists() || !file.isFile()) {
            System.err.println("文件不存在或不是有效文件：" + filePath);
            return roleMap;
        }

        // 读取文件内容并解析
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            // 跳过文件头部（前两行：时间戳和分隔线）
            // 读取并忽略第一行（时间戳）
            reader.readLine();
            // 读取并忽略第二行（分隔线）
            reader.readLine();

            // 解析后续行（IP -> 角色）
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // 按" -> "分割（兼容文件中的格式）
                String[] parts = line.split(" -> ");
                if (parts.length == 2) {
                    String ip = parts[0].trim();
                    String role = parts[1].trim();
                    roleMap.put(ip, role);
                } else {
                    System.err.println("忽略无效格式行：" + line);
                }
            }
            System.out.println("成功从文件读取角色映射：" + filePath);

        } catch (IOException e) {
            System.err.println("读取文件失败：" + e.getMessage());
        }

        return roleMap;
    }


    /**
     * 获取所有节点的IP与角色映射，并写入新文件（ServerRole-1.txt, ServerRole-2.txt...）
     * @param nodeList 节点列表字符串，格式：ip1:port1,ip2:port2,...
     * @return 本次写入的文件名（如"ServerRole-3.txt"）
     */
    public String getAndSaveIpToRoleMap(String nodeList) {
        // 1. 获取角色映射
        ConcurrentHashMap<String, String> roleMap = getIpToRoleMap(nodeList);

        // 2. 生成新文件名（线程安全的序号递增）
        String fileName = String.format("ServerRole-%d.txt", fileSequence.getAndIncrement());
        String filePath = outputDir + File.separator + fileName;

        // 3. 写入文件
        writeMapToFile(roleMap, filePath);

        return fileName;
    }

    /**
     * 将角色映射写入文件
     * @param roleMap IP->角色的映射
     * @param filePath 目标文件路径
     */
    private void writeMapToFile(ConcurrentHashMap<String, String> roleMap, String filePath) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
            // 写入文件头部（时间戳，便于追溯）
            writer.write("节点角色信息（更新时间：" + new java.util.Date() + "）\n");
            writer.write("==============================\n");

            // 写入每个节点的角色
            for (Map.Entry<String, String> entry : roleMap.entrySet()) {
                writer.write(String.format("%s -> %s%n", entry.getKey(), entry.getValue()));
            }
            System.out.println("已写入角色信息到：" + filePath);
        } catch (IOException e) {
            System.err.println("写入文件失败：" + e.getMessage());
        }
    }

    /**
     * 确保输出目录存在，不存在则创建
     */
    private void ensureDirExists(String dirPath) {
        File dir = new File(dirPath);
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            if (!created) {
                System.err.println("警告：无法创建输出目录 " + dirPath + "，将使用当前目录");
                outputDir = ".";
            }
        }
    }


    /**
     * 获取所有节点的IP与角色映射（存储到ConcurrentHashMap）
     * @param nodeList 节点列表字符串，格式：ip1:port1,ip2:port2,...
     * @return  ConcurrentHashMap<节点IP, 角色> （如 {"172.30.0.2": "follower", "172.30.0.4": "leader"}）
     */
    public ConcurrentHashMap<String, String> getIpToRoleMap(String nodeList) {
        // 解析节点列表为 IP->端口 的映射
        ConcurrentHashMap<String, Integer> ipToPort = parseNodeList(nodeList);
        // 存储最终的 IP->角色 映射
        ConcurrentHashMap<String, String> ipToRole = new ConcurrentHashMap<>();
        // 遍历所有节点，查询角色并存储
        for (Map.Entry<String, Integer> entry : ipToPort.entrySet()) {
            String ip = entry.getKey();
            int port = entry.getValue();
            try {
                String role = getNodeRole(ip, port);
                ipToRole.put(ip, role); // 存储 IP->角色
            } catch (IOException e) {
                // 异常时存储错误信息（如"connect failed"）
                ipToRole.put(ip, "error: " + e.getMessage().split("\n")[0]);
            }
        }
        return ipToRole;
    }

    /**
     * 解析节点列表字符串为 IP->端口 的映射
     * @param nodeList 节点列表（如"172.30.0.2:11181,172.30.0.3:11181"）
     * @return ConcurrentHashMap<IP, 端口>
     */
    private ConcurrentHashMap<String, Integer> parseNodeList(String nodeList) {
        ConcurrentHashMap<String, Integer> ipToPort = new ConcurrentHashMap<>();
        if (nodeList == null || nodeList.trim().isEmpty()) {
            return ipToPort;
        }

        String[] nodes = nodeList.trim().split(",");
        for (String node : nodes) {
            String[] parts = node.trim().split(":");
            if (parts.length == 2) {
                String ip = parts[0].trim();
                try {
                    int port = Integer.parseInt(parts[1].trim());
                    ipToPort.put(ip, port);
                } catch (NumberFormatException e) {
                    System.err.println("无效的端口格式：" + node + "，已忽略");
                }
            } else {
                System.err.println("无效的节点格式（应为ip:port）：" + node + "，已忽略");
            }
        }
        return ipToPort;
    }

    /**
     * 获取单个节点的角色
     * @param host 节点IP地址
     * @param port 节点端口
     * @return 节点角色(leader/follower/looking/unknown)
     * @throws IOException 连接或通信异常
     */
    public String getNodeRole(String host, int port) throws IOException {
        String cmd = "srvr";
        String role = "unknown";

        Socket sock = null;
        BufferedReader reader = null;
        try {
            // 建立连接
            InetSocketAddress address = new InetSocketAddress(host, port);
            sock = new Socket();
            sock.connect(address, connectTimeout);
            sock.setSoTimeout(soTimeout);

            // 发送命令
            OutputStream outstream = sock.getOutputStream();
            outstream.write(cmd.getBytes());
            outstream.flush();
            sock.shutdownOutput();

            // 读取响应并解析角色
            reader = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("Mode: ")) {
                    role = line.replace("Mode: ", "").trim();
                    break;
                }
            }
            return role;
        } catch (SocketTimeoutException e) {
            throw new IOException(String.format("节点 %s:%d 超时(连接或读取)", host, port), e);
        } finally {
            // 关闭资源
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {}
            }
            if (sock != null) {
                try {
                    sock.close();
                } catch (IOException ignored) {}
            }
        }
    }

    /**
     * 查询所有节点的角色
     * @param nodeList 节点列表字符串，格式：ip1:port1,ip2:port2,...
     * @return 所有节点的角色映射(IP:port -> 角色)
     */
    public Map<String, String> getAllNodeRoles(String nodeList) {
        Map<String, Integer> nodes = parseNodeList(nodeList);
        return getAllNodeRoles(nodes);
    }

    /**
     * 查询所有节点的角色
     * @param nodes IP-端口映射表
     * @return 所有节点的角色映射(IP:port -> 角色)
     */
    public Map<String, String> getAllNodeRoles(Map<String, Integer> nodes) {
        Map<String, String> roleMap = new HashMap<>();
        if (nodes == null || nodes.isEmpty()) {
            return roleMap;
        }

        for (Map.Entry<String, Integer> entry : nodes.entrySet()) {
            String ip = entry.getKey();
            int port = entry.getValue();
            String nodeKey = String.format("%s:%d", ip, port);

            try {
                String role = getNodeRole(ip, port);
                roleMap.put(nodeKey, role);
            } catch (IOException e) {
                roleMap.put(nodeKey, String.format("error: %s", e.getMessage()));
            }
        }
        return roleMap;
    }

    /**
     * 打印节点角色信息
     * @param roleMap 节点角色映射表
     */
    public void printRoles(Map<String, String> roleMap) {
        if (roleMap == null || roleMap.isEmpty()) {
            System.out.println("没有节点角色信息可显示");
            return;
        }

        System.out.println("ZooKeeper集群节点角色信息：");
        System.out.println("---------------------------");
        for (Map.Entry<String, String> entry : roleMap.entrySet()) {
            System.out.printf("%s -> %s%n", entry.getKey(), entry.getValue());
        }
    }

    // Getter和Setter方法
    public int getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public int getSoTimeout() {
        return soTimeout;
    }

    public void setSoTimeout(int soTimeout) {
        this.soTimeout = soTimeout;
    }

    // Getter和Setter（用于修改输出目录）
    public void setOutputDir(String outputDir) {
        this.outputDir = outputDir;
        ensureDirExists(outputDir); // 确保新目录存在
    }

    public String getOutputDir() {
        return outputDir;
    }

}
