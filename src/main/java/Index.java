import cn.6tail.tyme4j.*; // 第一行，确保正确
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * index 类用于生成和查询基于公历、农历及相关信息的每日JSON数据。
 * 支持同步历史数据、查询特定日期的指定类型信息，并记录操作日志。
 */
public class Index {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String OUTPUT_DIR = "Datefile";
    private static final String LOG_FILE = "generator_log.txt";
    private static final LocalDate START_DATE = LocalDate.of(2025, 1, 1);

    private static final DateTimeFormatter[] DATE_FORMATS = {
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd"),
        DateTimeFormatter.ofPattern("yyyyMMdd")
    };

    public static void main(String[] args) throws IOException {
    LocalDate today = LocalDate.now(BEIJING_ZONE);
    syncAllData(START_DATE, today);

    String todayHolidayJson = queryData("节假日", null);
    writeToLogFile("当天节假日查询结果:\n" + todayHolidayJson);

    String holidayJson1 = queryData("节假日", "2025-02-25");
    writeToLogFile("节假日 2025-02-25 查询结果:\n" + holidayJson1);

    String holidayJson2 = queryData("节假日", "2025/02/25");
    writeToLogFile("节假日 2025/02/25 查询结果:\n" + holidayJson2);

    String hoursJson = queryData("12时辰", "20250225");
    writeToLogFile("12时辰 2025-02-25 查询结果:\n" + hoursJson);
}

    // 添加新的方法来写入日志文件
    private static void writeToLogFile(String content) throws IOException {
    File logDir = new File("file");
    if (!logDir.exists()) {
        logDir.mkdirs(); // 创建目录如果不存在
    }
    
    File logFile = new File("file/log.log");
    try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
        writer.write(content);
        writer.newLine();
        writer.newLine(); // 添加额外的空行分隔不同条目
}
}

    private static void syncAllData(LocalDate startDate, LocalDate today) throws IOException {
        log("开始同步所有数据", "起始日期: " + startDate + ", 结束日期: " + today);
        for (int year = startDate.getYear(); year <= today.getYear(); year++) {
            int startMonth = (year == startDate.getYear()) ? startDate.getMonthValue() : 1;
            int endMonth = (year == today.getYear()) ? today.getMonthValue() : 12;

            for (int month = startMonth; month <= endMonth; month++) {
                syncMonthlyData(year, month, today);
            }
        }
        log("完成同步所有数据", "结束时间: " + LocalDateTime.now());
    }

    private static void syncMonthlyData(int year, int month, LocalDate today) throws IOException {
        String dirPath = OUTPUT_DIR + "/" + year + "/" + String.format("%02d", month);
        File dir = new File(dirPath);
        if (!dir.exists()) {
            dir.mkdirs();
            log("创建目录", dirPath);
        }

        String filePath = dirPath + ".json";
        File file = new File(filePath);
        int daysInMonth = SolarDay.fromYmd(year, month, 1).getLastDayOfMonth().getDay();
        int endDay = (year == today.getYear() && month == today.getMonthValue()) ? today.getDayOfMonth() : daysInMonth;

        Map<String, Map<String, Object>> monthlyData;
        if (file.exists()) {
            monthlyData = mapper.readValue(file, mapper.getTypeFactory().constructMapType(Map.class, String.class, Map.class));
            monthlyData = updateOrFillMonthlyData(monthlyData, year, month, endDay);
        } else {
            monthlyData = generateFullMonthlyData(year, month, endDay);
        }

        mapper.writeValue(file, monthlyData);
        log("更新月度文件", filePath + "，共 " + monthlyData.size() + " 天");
    }

    private static Map<String, Map<String, Object>> generateFullMonthlyData(int year, int month, int endDay) {
        Map<String, Map<String, Object>> monthlyData = new HashMap<>();
        for (int day = 1; day <= endDay; day++) {
            SolarDay solarDay = SolarDay.fromYmd(year, month, day);
            monthlyData.put(solarDay.toString(), generateDailyData(solarDay));
        }
        log("生成完整月度数据", year + "-" + month);
        return monthlyData;
    }

    private static Map<String, Map<String, Object>> updateOrFillMonthlyData(Map<String, Map<String, Object>> existingData, int year, int month, int endDay) {
        Map<String, Map<String, Object>> updatedData = new HashMap<>(existingData);
        for (int day = 1; day <= endDay; day++) {
            String dateKey = String.format("%d-%02d-%02d", year, month, day);
            if (!updatedData.containsKey(dateKey) || isDataIncomplete(updatedData.get(dateKey))) {
                SolarDay solarDay = SolarDay.fromYmd(year, month, day);
                updatedData.put(dateKey, generateDailyData(solarDay));
                log("补全月度数据", dateKey);
            }
        }
        return updatedData;
    }

    private static boolean isDataIncomplete(Map<String, Object> dayData) {
        return !dayData.containsKey("shierShichen") || !dayData.containsKey("ershisiJieqi");
    }

    private static Map<String, Object> generateDailyData(SolarDay solarDay) {
        Map<String, Object> dailyData = new HashMap<>();

        Map<String, String> basicInfo = new HashMap<>();
        basicInfo.put("nongliRiq", solarDay.getLunarDay().toString());
        basicInfo.put("xingqi", solarDay.getWeek().getName());
        dailyData.put("jibenRqiXinxi", basicInfo);

        Map<String, String> holidayInfo = new HashMap<>();
        Holiday holiday = solarDay.getHoliday();
        holidayInfo.put("fadingJiejiari", holiday != null ? holiday.getName() : "无");
        holidayInfo.put("shifouFangjia", holiday != null && holiday.isOffDay() ? "是" : "否");
        dailyData.put("jiejiari", holidayInfo);

        Map<String, String> hoursInfo = new HashMap<>();
        List<Hour> hours = solarDay.getHours();
        for (Hour hour : hours) {
            hoursInfo.put(hour.getName(), hour.getStartHour() + ":00-" + hour.getEndHour() + ":00, 干支: " + hour.getSixtyCycle().getName());
        }
        dailyData.put("shierShichen", hoursInfo);

        Map<String, String> solarTermInfo = new HashMap<>();
        SolarTerm solarTerm = solarDay.getSolarTerm();
        if (solarTerm != null) {
            solarTermInfo.put("dangriJieqi", solarTerm.getName());
        } else {
            solarTermInfo.put("dangriJieqi", "无");
            solarTermInfo.put("shangyiJieqi", solarDay.prevSolarTerm().getName());
            solarTermInfo.put("xiayiJieqi", solarDay.nextSolarTerm().getName());
        }
        dailyData.put("ershisiJieqi", solarTermInfo);

        LunarDay lunarDay = solarDay.getLunarDay();
        Map<String, String> almanacInfo = new HashMap<>();
        almanacInfo.put("ganzhiNian", lunarDay.getLunarYear().getSixtyCycle().getName());
        almanacInfo.put("ganzhiYue", lunarDay.getLunarMonth().getSixtyCycle().getName());
        almanacInfo.put("ganzhiRi", lunarDay.getSixtyCycle().getName());
        almanacInfo.put("shengxiao", lunarDay.getLunarYear().getZodiac());
        almanacInfo.put("wuxingNian", lunarDay.getLunarYear().getSixtyCycle().getElement().getName());
        dailyData.put("laohuangli", almanacInfo);

        Map<String, String> zodiacInfo = new HashMap<>();
        zodiacInfo.put("xingzuo", solarDay.getZodiac().getName());
        dailyData.put("xingzuo", zodiacInfo);

        log("生成单日数据", solarDay.toString());
        return dailyData;
    }

    public static String queryData(String category, String dateStr) throws IOException {
        LocalDate date = parseDate(dateStr);
        int year = date.getYear();
        int month = date.getMonthValue();

        String filePath = OUTPUT_DIR + "/" + year + "/" + String.format("%02d", month) + ".json";
        File file = new File(filePath);
        Map<String, Map<String, Object>> monthlyData;

        log("查询数据", "类型: " + category + ", 日期: " + date);

        if (file.exists()) {
            monthlyData = mapper.readValue(file, mapper.getTypeFactory().constructMapType(Map.class, String.class, Map.class));
        } else {
            monthlyData = new HashMap<>();
            log("文件不存在", filePath);
        }

        String dateKey = date.toString();
        Map<String, Object> result = new HashMap<>();

        if (!monthlyData.containsKey(dateKey) || isDataIncomplete(monthlyData.get(dateKey))) {
            SolarDay solarDay = SolarDay.fromYmd(year, month, date.getDayOfMonth());
            monthlyData.put(dateKey, generateDailyData(solarDay));
            mapper.writeValue(file, monthlyData);
            log("补全丢失数据", dateKey);
        }

        Map<String, Object> dayData = monthlyData.get(dateKey);
        category = convertToPinyin(category);
        if (dayData.containsKey(category)) {
            Map<String, Object> categoryData = new HashMap<>();
            categoryData.put(category, dayData.get(category));
            result.put(dateKey, categoryData);
        }

        String jsonResult = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        log("查询结果生成", "类型: " + category + ", 日期: " + dateKey);
        return jsonResult;
    }

    private static String convertToPinyin(String category) {
        switch (category) {
            case "节假日": return "jiejiari";
            case "12时辰": return "shierShichen";
            case "24节气": return "ershisiJieqi";
            case "基本日期信息": return "jibenRqiXinxi";
            case "老黄历": return "laohuangli";
            case "星座": return "xingzuo";
            default: return category;
        }
    }

    private static LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            LocalDate today = LocalDate.now(BEIJING_ZONE);
            log("使用默认日期", "当天: " + today);
            return today;
        }

        for (DateTimeFormatter formatter : DATE_FORMATS) {
            try {
                LocalDate date = LocalDate.parse(dateStr, formatter);
                log("日期解析成功", dateStr + " -> " + date);
                return date;
            } catch (DateTimeParseException e) {
                // 尝试下一个格式
            }
        }
        log("日期解析失败", dateStr);
        throw new IllegalArgumentException("无法解析日期格式: " + dateStr);
    }

    private static void updateDailyData(int year, int month, LocalDate date, Map<String, Object> updatedData) throws IOException {
        String filePath = OUTPUT_DIR + "/" + year + "/" + String.format("%02d", month) + ".json";
        File file = new File(filePath);
        Map<String, Map<String, Object>> monthlyData;

        if (file.exists()) {
            monthlyData = mapper.readValue(file, mapper.getTypeFactory().constructMapType(Map.class, String.class, Map.class));
        } else {
            monthlyData = new HashMap<>();
        }

        monthlyData.put(date.toString(), updatedData);
        mapper.writeValue(file, monthlyData);
        log("修改数据", "文件: " + filePath + ", 日期: " + date);
    }

    private static void log(String action, String details) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(LOG_FILE, true))) {
            String timestamp = LocalDateTime.now().toString();
            writer.write(String.format("[%s] %s: %s\n", timestamp, action, details));
        }
    }
}
