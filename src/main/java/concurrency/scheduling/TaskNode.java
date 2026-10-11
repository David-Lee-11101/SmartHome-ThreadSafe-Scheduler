package concurrency.scheduling;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class TaskNode {
    //所属根节点
    private RootNode root;

    private UUID taskUUID;
    private String taskName;

    //依赖集合：需要等待其他TaskNode先执行完成
    private CopyOnWriteArraySet<TaskNode> dependencies = new CopyOnWriteArraySet<>();
    //被依赖集合：等待当前TaskNode的集合
    private CopyOnWriteArraySet<TaskNode> notifies = new CopyOnWriteArraySet<>();
    //Trigger 设备集合
    private TreeSet<UUID> triggerDevices;
    //Action 设备集合
    private TreeSet<UUID> actionDevices;
    //根据Trigger推理出规则触发的时间
    private Long timeStamp;
    private final long sequence;
    //规则执行内容
    private ExecFunc execFunction;

    //当前taskThread是否结束执行
    private AtomicBoolean hasFinished;

    public TaskNode(RootNode root, UUID taskUUID, String taskName, TreeSet<UUID> triggerDevices, TreeSet<UUID> actionDevices, SimpleExecFunc execFunction) {
        this.root = root;
        this.taskUUID = taskUUID;
        this.taskName = taskName;
        this.triggerDevices = triggerDevices;
        this.actionDevices = actionDevices;
        this.timeStamp = System.currentTimeMillis();
        this.sequence = root.nextTaskSequence();
        this.execFunction = execFunction;
        this.hasFinished = new AtomicBoolean(false);
    }

    public UUID getTaskUUID() {
        return taskUUID;
    }

    public String getTaskName() {
        return taskName;
    }

    public Long getTimeStamp() {
        return timeStamp;
    }

    public void runTask(AtomicBoolean arrivalFlag, AtomicBoolean timeWindowFlag) {
        runTask(arrivalFlag, timeWindowFlag, 100L);
    }

    public void runTask(AtomicBoolean arrivalFlag, AtomicBoolean timeWindowFlag, long pollIntervalMillis) {
        runTask(arrivalFlag, timeWindowFlag, pollIntervalMillis, new CountDownLatch(0));
    }

    public void runTask(AtomicBoolean arrivalFlag, AtomicBoolean timeWindowFlag, long pollIntervalMillis,
                        CountDownLatch dependenciesReady) {

        //依赖关系生成的时候，必须要加锁。但由于生成依赖很快，此时全局加锁消耗忽略不计
        synchronized (TaskNode.class) {
            // 首先将当前 task 的 trigger devices 和 action devices 映射添加进去
            root.addRuleDeviceRelation(triggerDevices, this);
            //用于生成依赖关系
            generatingDependency();
        }
        dependenciesReady.countDown();
        List<DeviceNode> lockedDevices = new ArrayList<>();
        try {
            while (!dependencies.isEmpty()) {
                try {
                    Thread.sleep(pollIntervalMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            for (DeviceNode device : root.getDeviceNodes()) {
                if (actionDevices.contains(device.getDeviceUUID())) {
                    device.getWriteLock().lock();
                    lockedDevices.add(device);
                }
            }
            executeAction(arrivalFlag, timeWindowFlag, pollIntervalMillis);
        } finally {
            hasFinished.set(true);
            cleanupDependenciesAndSelf();
            root.removeRuleDeviceRelation(triggerDevices, this);
            for (int index = lockedDevices.size() - 1; index >= 0; index--) {
                lockedDevices.get(index).getWriteLock().unlock();
            }
        }
    }

    private void generatingDependency() {

//        Set<DeviceNode> devices = root.getDeviceNodes();
//        devices.forEach((device) -> {
//            if (actionDevices.contains(device.getDeviceUUID())) {
//                device.getReadLock().lock();
//            }
//        });
//        System.out.println(this.taskName+ " 获取了相关设备的读锁");

        //调度规则2：作用在同一个device上的rule task按照时间的先后顺序执行
        root.getDeviceNodes().forEach((device) -> {
            if (actionDevices.contains(device.getDeviceUUID())) {
                //先讲自己的占位符加进去
                device.getTaskNodes().add(this);
                //根据已有的生成依赖关系
                device.getTaskNodes().forEach((t) -> {
                    if (t.sequence < this.sequence && !t.hasFinished.get()) {
                        t.notifies.add(this);
                        dependencies.add(t);
                    }
                });
            }
        });

        //调度规则1：由同一个device触发的rule task，按照trigger触发的先后顺序，编排trigger对应的action的动作
        Set<TaskNode> taskNodesForTriggerDevices = root.getRuleDeviceRelation().get(triggerDevices);
        if (taskNodesForTriggerDevices != null) {
            taskNodesForTriggerDevices.forEach((taskNode) -> {
                if (taskNode.sequence < this.sequence && !taskNode.hasFinished.get()) {
                    taskNode.notifies.add(this);
                    this.dependencies.add(taskNode);
                }
            });
        }
//        *****
//        System.out.println(this.taskName + "生成依赖关系完毕 " + this.dependencies.toString() + this.notifies.toString());

//        devices.forEach((device) -> {
//            if (actionDevices.contains(device.getDeviceUUID())) {
//                device.getReadLock().unlock();
//            }
//        });
//        System.out.println(this.taskName + "释放了相关设备的读锁 ");
    }

    private void executeAction(AtomicBoolean arrivalFlag, AtomicBoolean timeWindowFlag, long pollIntervalMillis) {

//        System.out.println(this.taskName + " 任务开始等待真实action ");

        //判断真实action已到达或者等待时间窗口结束
        while (!arrivalFlag.get() && !timeWindowFlag.get()) {
            try {
                //睡眠等待降低线程占用率
                Thread.sleep(pollIntervalMillis);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        //处理真实action到达，开始正常执行
        if (arrivalFlag.get()) {
            try {
//                System.out.println(this.taskName + " 任务收到真实action，开始执行");
                execFunction.exec();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        } else {
            if (timeWindowFlag.get()) {
//                System.out.println(this.taskName + " 任务超出等待时间窗口，结束执行");
                return;
            }
        }
    }

    private void cleanupDependenciesAndSelf() {
        // 主动去修改别的任务的 dependencies
        notifies.forEach((n) -> n.dependencies.remove(this));

        // 从设备节点中移除自身
        Set<DeviceNode> devices = root.getDeviceNodes();
        devices.forEach((device) -> {
            if (actionDevices.contains(device.getDeviceUUID())) {
                device.getTaskNodes().remove(this);
            }
        });
    }

    @Override
    public String toString() {
        return taskName;
    }


    public interface ExecFunc {
        void exec() throws InterruptedException;
    }

    public static class SimpleExecFunc implements ExecFunc {
        private final String taskName;
        private final String description;
        private final long simulatedWorkMillis;
        private final Path logPath;
        private final Runnable completionAction;
        private final boolean printExecution;

        public SimpleExecFunc(String taskName,String description) {
            this(taskName, description, 100L, null, null, true);
        }

        public SimpleExecFunc(String taskName, String description, long simulatedWorkMillis,
                              Path logPath, Runnable completionAction) {
            this(taskName, description, simulatedWorkMillis, logPath, completionAction, true);
        }

        public SimpleExecFunc(String taskName, String description, long simulatedWorkMillis,
                              Path logPath, Runnable completionAction, boolean printExecution) {
            this.taskName = taskName;
            this.description = description;
            this.simulatedWorkMillis = simulatedWorkMillis;
            this.logPath = logPath;
            this.completionAction = completionAction;
            this.printExecution = printExecution;
        }

        @Override
        public void exec() throws InterruptedException {
            Thread.sleep(simulatedWorkMillis);
            if (printExecution) System.out.println(taskName + " executed, description: " + description);
            if (completionAction != null) completionAction.run();
            if (logPath != null) logTaskExecution(taskName, description);
        }

        private void logTaskExecution(String taskName, String description) {
            try (BufferedWriter writer = Files.newBufferedWriter(logPath, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(String.format("%s,%s%n", taskName.substring(taskName.lastIndexOf("-")+1), description));
            } catch (IOException e) {
                System.err.println("写入日志失败: " + e.getMessage());
            }
        }
    }

}
