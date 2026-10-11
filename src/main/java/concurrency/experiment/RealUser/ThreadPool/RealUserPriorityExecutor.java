package concurrency.experiment.RealUser.ThreadPool;

import concurrency.experiment.DeviceInfo;
import concurrency.experiment.RuleInfo;
import concurrency.scheduling.RuleTree;
import concurrency.scheduling.TaskNode;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/**
 *@Date：2024/12/30
 *@Time：下午8:13
 *@Author：魏浩东
 *@Description：基于优先级队列的线程池执行，模拟顺序规则并发->突然意识到好像不需要这里的线程池来做多线程，因为createTask本来就是ThreadStart方式启动了两个线程
 *@Description：这里面线程池的作用是确保任务提交后，立马开始被调度执行。因此，对于runTasks所用的线程池的线程数，是可以为1的，因为对于该线程来说，把任务提交执行（执行线程启动）就算完成，可以去开启下一个了。
 * @Description：这样做，主要是确保依赖的生成有顺序性。如果依赖生成前后导致了，那就变成后面来的看不见前者了。
 *
 */
public class RealUserPriorityExecutor {

    // 包装任务的类，带优先级
    public static class PriorityRunnable implements Runnable, Comparable<PriorityRunnable> {
        private final int priority;
        private final Runnable task;

        public PriorityRunnable(int priority, Runnable task) {
            this.priority = priority;
            this.task = task;
        }

        public int getPriority() {
            return priority;
        }

        @Override
        public void run() {
            task.run();
        }

        @Override
        public int compareTo(PriorityRunnable other) {
            return Integer.compare(this.priority, other.priority); // 优先级小的先执行,id小的规则先执行
        }
    }

    public static void runTasks(String devicesFilePath, String rulesFilePath) throws IOException {
        // 初始化 RuleTree
        RuleTree ruleTree = new RuleTree();

        // 自定义线程池，使用优先级队列
        ThreadPoolExecutor executorService = new ThreadPoolExecutor(1, // 核心线程数
                1, // 最大线程数
                60L, // 空闲线程存活时间
                TimeUnit.SECONDS,
                new PriorityBlockingQueue<>() // 使用优先级队列
        );

        Gson gson = new Gson();
        List<RuleTree.TaskHandle> handles = Collections.synchronizedList(new ArrayList<>());
        // 读取设备
        List<DeviceInfo> devices = gson.fromJson(new FileReader(devicesFilePath), new TypeToken<List<DeviceInfo>>() {}.getType());
        // 读取规则
        List<RuleInfo> rules = gson.fromJson(new FileReader(rulesFilePath), new TypeToken<List<RuleInfo>>() {}.getType());


        // 注册设备并构建映射表
        Map<String, UUID> deviceMap = new HashMap<>();
        for (DeviceInfo device : devices) {
            UUID deviceUUID = UUID.randomUUID();
            ruleTree.createDevice(device.getName(), deviceUUID);
            deviceMap.put(device.getName(), deviceUUID);
        }

        // 提交任务到线程池
        for (RuleInfo rule : rules) {
            int priority = rule.getId(); // 使用规则 ID 作为优先级
            executorService.execute(new PriorityRunnable(priority, () -> {
                try {
                    TreeSet<UUID> triggerDevices = new TreeSet<>();
                    TreeSet<UUID> actionDevices = new TreeSet<>();

                    // 根据名称从映射表中获取触发设备的 UUID
                    for (String trigger : rule.getTriggers()) {
                        if (deviceMap.containsKey(trigger)) {
                            triggerDevices.add(deviceMap.get(trigger));
                        } else {
                            System.err.println("触发设备未找到: " + trigger);
                        }
                    }

                    // 根据名称从映射表中获取动作设备的 UUID
                    for (String action : rule.getActions()) {
                        if (deviceMap.containsKey(action)) {
                            actionDevices.add(deviceMap.get(action));
                        } else {
                            System.err.println("动作设备未找到: " + action);
                        }
                    }

                    // 创建任务
                    handles.add(ruleTree.createTask("Rule-"+rule.getId(), triggerDevices, actionDevices,
                            new TaskNode.SimpleExecFunc("Rule-"+rule.getId(),rule.getDescription())));

                } catch (Exception e) {
                    System.err.println("任务执行失败: " + e.getMessage());
                    e.printStackTrace();
                }
            }));
        }
        // 关闭线程池
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                throw new IOException("rule submission did not finish within 30 seconds");
            }
            for (RuleTree.TaskHandle handle : handles) {
                if (!handle.await(15_000L)) throw new IOException("scheduled rule did not complete");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for scheduled rules", interrupted);
        }
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: RealUserPriorityExecutor <devices.json> <rules.json>");
            System.exit(2);
        }
        try {
            runTasks(args[0], args[1]);
        } catch (IOException e) {
            System.err.println("运行任务失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
