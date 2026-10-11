package concurrency.experiment.RealUser.Freemarker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import concurrency.experiment.DeviceInfo;
import concurrency.experiment.RuleInfo;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;


public class RulesExecGenerator {

    public static void main(String[] args) {
        if (args.length != 3) {
            System.err.println("Usage: RulesExecGenerator <devices-file> <sync-log> <output-java-file>");
            System.exit(2);
        }
        try {
            // 1. 解析 device
            List<DeviceInfo> deviceList = parseDeviceFile(args[0]);
            // 2. 解析 rule
            List<RuleInfo> ruleList = parseRulesLog(args[1]);
            // 3. 初始化 FreeMarker
            Configuration cfg = new Configuration(Configuration.VERSION_2_3_31);
            cfg.setDirectoryForTemplateLoading(new File("src/main/java/concurrency/experiment/RealUser/Freemarker/template"));
            cfg.setDefaultEncoding("UTF-8");
            // 4. 获取模板
            Template template = cfg.getTemplate("RealUserTemplate.ftl");
            // 5. 构建数据模型
            Map<String, Object> dataModel = new HashMap<>();
            dataModel.put("devices", deviceList);
            dataModel.put("rules", ruleList);
            // 6. 生成 Main.java
            File outFile = new File(args[2]);
            try (Writer out = new FileWriter(outFile)) {
                template.process(dataModel, out);
            }

        } catch (IOException | TemplateException e) {
            System.err.println("生成 Main.java 失败: " + e.getMessage());
            e.printStackTrace();
        }
    }



    private static List<DeviceInfo> parseDeviceFile(String filePath) throws IOException {
        List<DeviceInfo> deviceList = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(Paths.get(filePath))) {
            String header = br.readLine(); // 跳过表头 (name,uuidVar)
            String line;
            while ((line = br.readLine()) != null) {
                String[] columns = line.split(",");
                if (columns.length >= 2) {
                    String name = columns[0].trim();
                    String uuidVar = columns[1].trim();
                    System.out.println("New device detected: " + name + ", " + uuidVar);
                    deviceList.add(new DeviceInfo(name, uuidVar));
                }
            }
        }
        return deviceList;
    }

    private static List<RuleInfo> parseRulesLog(String filePath) throws IOException {
        List<RuleInfo> ruleList = new ArrayList<>();
        Gson gson = new GsonBuilder()
                .setLenient() // 启用宽松模式，允许非标准 JSON 格式
                .create();

        try (BufferedReader br = Files.newBufferedReader(Paths.get(filePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                Map<String, Object> ruleData = gson.fromJson(line, Map.class);

                // 提取规则 ID 和描述
                int id = ((Double) ruleData.get("id")).intValue(); // Gson 解析数字为 Double
                String description = (String) ruleData.get("description");
                RuleInfo rule = new RuleInfo(id, description.replace("\\&", "&"));

                // 提取 triggers：只取设备名称
                List<String> triggers = new ArrayList<>();
                triggers.add((String) ((List<?>) ruleData.get("Trigger")).get(0));
                rule.getTriggers().addAll(triggers);

                // 提取 actions：只取设备名称
                List<String> actions = ((List<List<?>>) ruleData.get("Action"))
                        .stream()
                        .map(action -> action.get(0).toString()) // 仅取设备名称
                        .collect(Collectors.toList());
                rule.getActions().addAll(actions);

                ruleList.add(rule);
            }
        }
        return ruleList;
    }

}
