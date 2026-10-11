package concurrency.experiment.RealUser.ThreadPool;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import concurrency.experiment.RuleInfo;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SyncLogParser {

    public static void parseRuleLogToJson(String logFilePath, String outputJsonPath) throws IOException {
        List<RuleInfo> rules = new ArrayList<>();
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        try (BufferedReader br = new BufferedReader(new FileReader(logFilePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                // 清理单引号，确保 JSON 格式正确
                String cleanedLine = line.replace("'", "\"");

                // 使用 Gson 解析日志
                Map<String, Object> ruleData = gson.fromJson(cleanedLine, Map.class);
                int id = ((Double) ruleData.get("id")).intValue(); // 转换 ID
                String description = (String) ruleData.get("description");

                List<String> triggers = new ArrayList<>();
                List<String> actions = new ArrayList<>();

                // 提取 Trigger
                triggers.add((String) ((List<?>) ruleData.get("Trigger")).get(0));

                // 提取 Action
                List<List<?>> actionList = (List<List<?>>) ruleData.get("Action");
                for (List<?> action : actionList) {
                    actions.add(action.get(0).toString());
                }

                // 创建规则并添加到列表
                RuleInfo rule = new RuleInfo(id, description.replace("\\&", "&"));
                rule.getTriggers().addAll(triggers);
                rule.getActions().addAll(actions);
                rules.add(rule);
            }
        }

        // 将解析的规则写入 JSON 文件
        try (FileWriter writer = new FileWriter(outputJsonPath)) {
            gson.toJson(rules, writer);
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: SyncLogParser <sync-log> <rules-json-output>");
            System.exit(2);
        }
        parseRuleLogToJson(args[0], args[1]);
    }
}
