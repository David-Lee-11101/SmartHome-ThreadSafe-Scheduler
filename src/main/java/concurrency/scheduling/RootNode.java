package concurrency.scheduling;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

public class RootNode {
    private CopyOnWriteArraySet<DeviceNode> deviceNodes = new CopyOnWriteArraySet<>();
    // 用于记录该RootNode下触发规则的 trigger devices 和 rule task 之间的关系
    private ConcurrentHashMap<Set<UUID>, Set<TaskNode>> ruleDeviceRelation = new ConcurrentHashMap<>();
    private final AtomicLong taskSequence = new AtomicLong();

    public long nextTaskSequence() {
        return taskSequence.getAndIncrement();
    }

    // 获取 deviceNodes 集合
    public Set<DeviceNode> getDeviceNodes() {
        // 返回一个按字典序排序的设备节点集合
        List<DeviceNode> sortedList = new ArrayList<>(deviceNodes);
        // 按设备名称字典序排序
        sortedList.sort(Comparator.comparing(DeviceNode::getDeviceUUID));
        // 转换为 LinkedHashSet 保持顺序
        return new LinkedHashSet<>(sortedList);
    }

    // 添加单个 DeviceNode
    public void addDeviceNode(DeviceNode deviceNode) {
        deviceNodes.add(deviceNode);
    }

    // 移除单个 DeviceNode
    public void removeDeviceNode(DeviceNode deviceNode) {
        deviceNodes.remove(deviceNode);
    }


    // 获取 ruleDeviceRelation 集合
    public ConcurrentHashMap<Set<UUID>, Set<TaskNode>> getRuleDeviceRelation() {
        return ruleDeviceRelation;
    }

    // 添加到 ruleDeviceRelation
    public void addRuleDeviceRelation(Set<UUID> triggerDevices, TaskNode taskNode) {
        ruleDeviceRelation.compute(triggerDevices, (key, existingSet) -> {
            if (existingSet == null) {
                existingSet = ConcurrentHashMap.newKeySet(); // 使用线程安全的 Set
            }
            existingSet.add(taskNode); // 添加 TaskNode
            return existingSet;
        });
    }

    // 获取特定 triggerDevices 对应的 TaskNodes
    public Set<TaskNode> getTaskNodesByTriggerDevices(Set<UUID> triggerDevices) {
        return ruleDeviceRelation.get(triggerDevices);
    }

    // 在一个task执行完成后，移除特定的 triggerDevices 对应的任务
    public void removeRuleDeviceRelation(Set<UUID> triggerDevices, TaskNode taskNode) {
        ruleDeviceRelation.compute(triggerDevices, (key, existingSet) -> {
            if (existingSet == null || !existingSet.contains(taskNode)) {
                // 如果不存在对应的 Set 或 Set 不包含任务，直接返回
                return existingSet;
            }
            existingSet.remove(taskNode); // 移除任务
            return existingSet.isEmpty() ? null : existingSet; // 如果为空，则移除整个键
        });
    }
}
