package edu.iscas.CCrashFuzzer;

import java.util.*;

public class ZkClassCategory {

    public enum Category {
        SERVICE_STARTUP_AND_ENV("服务启动与环境配置"),
        CLUSTER_CONSENSUS_AND_ROLE("集群共识与角色管理"),
        REQUEST_PROCESSING_AND_TRANSACTION("请求处理与事务调度"),
        DATA_STORE_AND_SNAPSHOT("数据存储与快照管理"),
        OTHER_TRANSACTIONS("其他事务");

        private final String desc;

        Category(String desc) {
            this.desc = desc;
        }

        public String getDesc() {
            return desc;
        }
    }

    // 类名→多分类映射（核心修改）
    private static final Map<String, List<Category>> CLASS_CATEGORY_MAP = new HashMap<>();

    static {
        // 1. 服务启动与环境配置（无跨分类）
        addSingleCategory("QuorumPeerConfig", Category.SERVICE_STARTUP_AND_ENV);
        addSingleCategory("QuorumPeerMain", Category.SERVICE_STARTUP_AND_ENV);
        addSingleCategory("Environment", Category.SERVICE_STARTUP_AND_ENV);
        addSingleCategory("ZookeeperBanner", Category.SERVICE_STARTUP_AND_ENV);
        addSingleCategory("ManagedUtil", Category.SERVICE_STARTUP_AND_ENV);
        addSingleCategory("ServerMetrics", Category.SERVICE_STARTUP_AND_ENV);
        addSingleCategory("X509Util", Category.SERVICE_STARTUP_AND_ENV);
        addSingleCategory("BlueThrottle", Category.SERVICE_STARTUP_AND_ENV);

        // 2. 集群共识与角色管理 + 跨分类
        addMultiCategory("QuorumPeer", Category.CLUSTER_CONSENSUS_AND_ROLE, Category.REQUEST_PROCESSING_AND_TRANSACTION);
        addSingleCategory("FastLeaderElection", Category.CLUSTER_CONSENSUS_AND_ROLE);
        addSingleCategory("FastLeaderElection$Messenger$WorkerReceiver", Category.CLUSTER_CONSENSUS_AND_ROLE);
        addMultiCategory("Leader", Category.CLUSTER_CONSENSUS_AND_ROLE, Category.REQUEST_PROCESSING_AND_TRANSACTION);
        addMultiCategory("Follower", Category.CLUSTER_CONSENSUS_AND_ROLE, Category.REQUEST_PROCESSING_AND_TRANSACTION);
        addSingleCategory("Learner", Category.CLUSTER_CONSENSUS_AND_ROLE);
        addSingleCategory("Learner$LeaderConnector", Category.CLUSTER_CONSENSUS_AND_ROLE);
        addSingleCategory("LearnerHandler", Category.CLUSTER_CONSENSUS_AND_ROLE);
        addSingleCategory("LearnerMaster", Category.CLUSTER_CONSENSUS_AND_ROLE);
        addSingleCategory("LearnerSessionTracker", Category.CLUSTER_CONSENSUS_AND_ROLE);
        addMultiCategory("QuorumZooKeeperServer", Category.CLUSTER_CONSENSUS_AND_ROLE, Category.REQUEST_PROCESSING_AND_TRANSACTION);

        // 3. 请求处理与事务调度（无新增跨分类，仅接收其他类跨入）
        addSingleCategory("Server", Category.REQUEST_PROCESSING_AND_TRANSACTION);
        addSingleCategory("RequestThrottler", Category.REQUEST_PROCESSING_AND_TRANSACTION);
        addSingleCategory("PrepRequestProcessor", Category.REQUEST_PROCESSING_AND_TRANSACTION);
        addSingleCategory("ProposalRequestProcessor", Category.REQUEST_PROCESSING_AND_TRANSACTION);
        addSingleCategory("CommitProcessor", Category.REQUEST_PROCESSING_AND_TRANSACTION);
        addSingleCategory("SyncRequestProcessor", Category.REQUEST_PROCESSING_AND_TRANSACTION);
        addSingleCategory("FinalRequestProcessor", Category.REQUEST_PROCESSING_AND_TRANSACTION);
        addSingleCategory("LeaderRequestProcessor", Category.REQUEST_PROCESSING_AND_TRANSACTION);
        addSingleCategory("FollowerRequestProcessor", Category.REQUEST_PROCESSING_AND_TRANSACTION);
        addSingleCategory("Leader$ToBeAppliedRequestProcessor", Category.REQUEST_PROCESSING_AND_TRANSACTION);
        addSingleCategory("FourLetterCommands", Category.REQUEST_PROCESSING_AND_TRANSACTION);
        addSingleCategory("ResponseCache", Category.REQUEST_PROCESSING_AND_TRANSACTION);
        addSingleCategory("RequestPathMetricsCollector", Category.REQUEST_PROCESSING_AND_TRANSACTION);

        // 4. 数据存储与快照管理（无新增跨分类，仅接收其他类跨入）
        addSingleCategory("FileTxnSnapLog", Category.DATA_STORE_AND_SNAPSHOT);
        addSingleCategory("FileTxnLog", Category.DATA_STORE_AND_SNAPSHOT);
        addSingleCategory("FileSnap", Category.DATA_STORE_AND_SNAPSHOT);
        addSingleCategory("ZKDatabase", Category.DATA_STORE_AND_SNAPSHOT);
        addSingleCategory("DataTree", Category.DATA_STORE_AND_SNAPSHOT);
        addSingleCategory("SnapStream", Category.DATA_STORE_AND_SNAPSHOT);
        addSingleCategory("DatadirCleanupManager", Category.DATA_STORE_AND_SNAPSHOT);
        addSingleCategory("ContainerManager", Category.DATA_STORE_AND_SNAPSHOT);

        // 5. 其他事务 + 跨分类
        addMultiCategory("QuorumCnxManager", Category.OTHER_TRANSACTIONS, Category.CLUSTER_CONSENSUS_AND_ROLE);
        addSingleCategory("ServerCnxnFactory", Category.OTHER_TRANSACTIONS);
        addSingleCategory("NIOServerCnxnFactory", Category.OTHER_TRANSACTIONS);
        addSingleCategory("NIOServerCnxn", Category.OTHER_TRANSACTIONS);
        addSingleCategory("AbstractConnector", Category.OTHER_TRANSACTIONS);
        addSingleCategory("QuorumCnxManager$Listener", Category.OTHER_TRANSACTIONS);
        addSingleCategory("QuorumCnxManager$Listener$ListenerHandler", Category.OTHER_TRANSACTIONS);
        addSingleCategory("DefaultSessionIdManager", Category.OTHER_TRANSACTIONS);
        addSingleCategory("SessionTrackerImpl", Category.OTHER_TRANSACTIONS);
        addSingleCategory("LeaderSessionTracker", Category.OTHER_TRANSACTIONS);
        addSingleCategory("WatchManagerFactory", Category.OTHER_TRANSACTIONS);
        addSingleCategory("HouseKeeper", Category.OTHER_TRANSACTIONS);
        addSingleCategory("ZKAuditProvider", Category.OTHER_TRANSACTIONS);
        addSingleCategory("ContextHandler", Category.OTHER_TRANSACTIONS);
        addSingleCategory("JettyAdminServer", Category.OTHER_TRANSACTIONS);
        addSingleCategory("ZooKeeperServerListenerImpl", Category.OTHER_TRANSACTIONS);
        addSingleCategory("Log", Category.OTHER_TRANSACTIONS);

        // 单独处理ZooKeeperServer（跨2个分类）
        addMultiCategory("ZooKeeperServer", Category.REQUEST_PROCESSING_AND_TRANSACTION, Category.DATA_STORE_AND_SNAPSHOT);
    }

    // 工具方法：添加单一分类（兼容Java 8）
    private static void addSingleCategory(String className, Category category) {
        List<Category> list = new ArrayList<>();
        list.add(category);
        CLASS_CATEGORY_MAP.put(className, list);
    }

    // 工具方法：添加多个分类（兼容Java 8）
    private static void addMultiCategory(String className, Category... categories) {
        List<Category> list = new ArrayList<>();
        Collections.addAll(list, categories); // 使用Collections工具类添加元素
        CLASS_CATEGORY_MAP.put(className, list);
    }

    // 获取类名对应的所有分类（兼容Java 8）
    public static List<Category> getCategories(String className) {
        List<Category> defaultList = new ArrayList<>();
        defaultList.add(Category.OTHER_TRANSACTIONS);
        return CLASS_CATEGORY_MAP.getOrDefault(className, defaultList);
    }

    // 示例：打印类的所有分类
    public static void main(String[] args) {
        String testClass = "FastLeaderElection";
        List<Category> categories = getCategories(testClass);
        System.out.printf("类名：%s → 所有分类：", testClass);
        for (Category c : categories) {
            System.out.print(c.getDesc() + "、");
        }
        // 输出：类名：QuorumPeer → 所有分类：集群共识与角色管理、请求处理与事务调度、
    }
}