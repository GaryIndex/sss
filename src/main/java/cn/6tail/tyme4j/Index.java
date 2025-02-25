package cn.6tail.tyme4j;

import cn.6tail.tyme4j.culture.Holiday;
import cn.6tail.tyme4j.culture.Hour;
import cn.6tail.tyme4j.lunar.LunarDay;
import cn.6tail.tyme4j.lunar.LunarMonth;
import cn.6tail.tyme4j.lunar.LunarYear;
import cn.6tail.tyme4j.culture.SixtyCycle;
import cn.6tail.tyme4j.solar.SolarDay;
import cn.6tail.tyme4j.solar.SolarTerm;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Index 类用于生成和查询基于公历、农历及相关信息的每日JSON数据。
 * 支持同步历史数据、查询特定日期的指定类型信息，并记录操作日志。
 */
public class Index {
    // 顶层配置常量
    private static final String BASE_DIR = System.getProperty("user.dir");
    private static final String OUTPUT_DIR = BASE_DIR + "/Datefile";
    private static final String LOG_DIR = BASE_DIR + "/logs";
    private static final String LOG_FILE = LOG_DIR + "/generator_log.txt";
    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate START_DATE = LocalDate.of(2025, 1, 1);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DateTimeFormatter[] DATE_FORMATS = {
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd"),
        DateTimeFormatter.ofPattern("yyyyMMdd")
    };

    public static void main(String[] args) throws IOException {
        initDirectories();
        LocalDate today = LocalDate.now(BEIJING_ZONE);
        log("程序启动", "当前日期: " + today);

        syncAllData(START_DATE, today);

        String todayHolidayJson = queryData("节假日", null);
        log("查询结果", "当天节假日:\n" + prettyPrintJson(todayHolidayJson));

        String holidayJson1 = queryData("节假日", "2025-02-25");
        log("查询结果", "节假日 2025-02-25:\n" + prettyPrintJson(holidayJson1));

        String holidayJson2 = queryData("节假日", "2025/02/25");
        log("查询结果", "节假日 2025/02/25:\n" + prettyPrintJson(holidayJson2));

        String hoursJson = queryData("12时辰", "20250225");
        log("查询结果", "12时辰 2025-02-25:\n" + prettyPrintJson(hoursJson));
    }

    // 初始化目录结构
    private static void initDirectories() throws IOException {
        File outputDir = new File(OUTPUT_DIR);
        File logDir = new File(LOG_DIR);
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IOException("无法创建输出目录: " + OUTPUT_DIR);
        }
        if (!logDir.exists() && !logDir.mkdirs()) {
            throw new IOException("无法创建日志目录: " + LOG_DIR);
        }
        log("目录初始化", "输出目录: " + OUTPUT_DIR + ", 日志目录: " + LOG_DIR);
    }

    // 美化JSON输出
    private static String prettyPrintJson(String json) throws IOException {
        Object jsonObject = MAPPER.readValue(json, Object.class);
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(jsonObject);
    }

    // 同步所有数据
    private static void syncAllData(LocalDate startDate, LocalDate today) throws IOException {
        log("开始同步所有数据", "起始日期: " + startDate + ", 结束日期: " + today);
        for (int year = startDate.getYear(); year <= today.getYear(); year++) {
            int startMonth = (year == startDate.getYear()) ? startDate.getMonthValue() : 1;
            int endMonth = (year == today.getYear()) ? today.getMonthValue() : 12;

            for (int month = startMonth; month <= endMonth; month++) {
                syncMonthlyData(year, month, today);
            }
        }
        log("完成同步所有数据", "结束时间: " + LocalDateTime.now(BEIJING_ZONE));
    }

    // 同步月度数据
    private static void syncMonthlyData(int year, int month, LocalDate today) throws IOException {
        String dirPath = OUTPUT_DIR + "/" + year + "/" + String.format("%02d", month);
        File dir = new File(dirPath);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建月度目录: " + dirPath);
        }

        String filePath = dirPath + "/data.json";
        File file = new File(filePath);
        int daysInMonth = SolarDay.fromYmd(year, month, 1).getLastDayOfMonth().getDay();
        int endDay = (year == today.getYear() && month == today.getMonthValue()) ? today.getDayOfMonth() : daysInMonth;

        Map<String, Map<String, Object>> monthlyData;
        if (file.exists()) {
            monthlyData = MAPPER.readValue(file, MAPPER.getTypeFactory().constructMapType(Map.class, String.class, Map.class));
            monthlyData = updateOrFillMonthlyData(monthlyData, year, month, endDay);
        } else {
            monthlyData = generateFullMonthlyData(year, month, endDay);
        }

        MAPPER.writeValue(file, monthlyData);
        log("更新月度文件", "路径: " + filePath + "，共 " + monthlyData.size() + " 天");
    }

    // 生成完整月度数据
    private static Map<String, Map<String, Object>> generateFullMonthlyData(int year, int month, int endDay) throws IOException {
        Map<String, Map<String, Object>> monthlyData = new HashMap<>();
        for (int day = 1; day <= endDay; day++) {
            SolarDay solarDay = SolarDay.fromYmd(year, month, day);
            monthlyData.put(solarDay.toString(), generateDailyData(solarDay));
        }
        log("生成完整月度数据", "年月: " + year + "-" + String.format("%02d", month));
        return monthlyData;
    }

    // 更新或填充月度数据
    private static Map<String, Map<String, Object>> updateOrFillMonthlyData(Map<String, Map<String, Object>> existingData, int year, int month, int endDay) throws IOException {
        Map<String, Map<String, Object>> updatedData = new HashMap<>(existingData);
        for (int day = 1; day <= endDay; day++) {
            String dateKey = String.format("%d-%02d-%02d", year, month, day);
            if (!updatedData.containsKey(dateKey) || isDataIncomplete(updatedData.get(dateKey))) {
                SolarDay solarDay = SolarDay.fromYmd(year, month, day);
                updatedData.put(dateKey, generateDailyData(solarDay));
                log("补全月度数据", "日期: " + dateKey);
            }
        }
        return updatedData;
    }

    // 检查数据是否完整
    private static boolean isDataIncomplete(Map<String, Object> dayData) {
        return dayData == null || !dayData.containsKey("shierShichen") || !dayData.containsKey("ershisiJieqi");
    }

    // 生成单日数据（支持Tyme全功能）
    private static Map<String, Object> generateDailyData(SolarDay solarDay) throws IOException {
        Map<String, Object> dailyData = new HashMap<>();
        LunarDay lunarDay = solarDay.getLunarDay();

        // 基本日期信息
        Map<String, String> basicInfo = new HashMap<>();
        basicInfo.put("nongliRiq", lunarDay.toString());
        basicInfo.put("xingqi", solarDay.getWeek().getName());
        dailyData.put("jibenRqiXinxi", basicInfo);

        // 节假日
        Map<String, String> holidayInfo = new HashMap<>();
        Holiday holiday = solarDay.getHoliday();
        holidayInfo.put("fadingJiejiari", holiday != null ? holiday.getName() : "无");
        holidayInfo.put("shifouFangjia", holiday != null && holiday.isOffDay() ? "是" : "否");
        dailyData.put("jiejiari", holidayInfo);

        // 12时辰
        Map<String, String> hoursInfo = new HashMap<>();
        List<Hour> hours = solarDay.getHours();
        for (Hour hour : hours) {
            hoursInfo.put(hour.getName(), hour.getStartHour() + ":00-" + hour.getEndHour() + ":00, 干支: " + hour.getSixtyCycle().getName());
        }
        dailyData.put("shierShichen", hoursInfo);

        // 24节气
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

        // 老黄历
        Map<String, String> almanacInfo = new HashMap<>();
        LunarYear lunarYear = lunarDay.getLunarYear();
        LunarMonth lunarMonth = lunarDay.getLunarMonth();
        almanacInfo.put("ganzhiNian", lunarYear.getSixtyCycle().getName());
        almanacInfo.put("ganzhiYue", lunarMonth.getSixtyCycle().getName());
        almanacInfo.put("ganzhiRi", lunarDay.getSixtyCycle().getName());
        almanacInfo.put("shengxiao", lunarYear.getZodiac());
        almanacInfo.put("wuxingNian", lunarYear.getSixtyCycle().getElement().getName());
        dailyData.put("laohuangli", almanacInfo);

        // 星座
        Map<String, String> zodiacInfo = new HashMap<>();
        zodiacInfo.put("xingzuo", solarDay.getZodiac().getName());
        dailyData.put("xingzuo", zodiacInfo);

        log("生成单日数据", "日期: " + solarDay.toString() + ", 数据详情: " + MAPPER.writeValueAsString(dailyData));
        return dailyData;
    }

    // 查询数据
    public static String queryData(String category, String dateStr) throws IOException {
        LocalDate date = parseDate(dateStr);
        String filePath = OUTPUT_DIR + "/" + date.getYear() + "/" + String.format("%02d", date.getMonthValue()) + "/data.json";
        File file = new File(filePath);
        Map<String, Map<String, Object>> monthlyData;

        log("查询数据", "类型: " + category + ", 日期: " + date);

        if (file.exists()) {
            monthlyData = MAPPER.readValue(file, MAPPER.getTypeFactory().constructMapType(Map.class, String.class, Map.class));
        } else {
            monthlyData = new HashMap<>();
            log("文件不存在", "路径: " + filePath);
        }

        String dateKey = date.toString();
        Map<String, Object> result = new HashMap<>();

        if (!monthlyData.containsKey(dateKey) || isDataIncomplete(monthlyData.get(dateKey))) {
            SolarDay solarDay = SolarDay.fromYmd(date.getYear(), date.getMonthValue(), date.getDayOfMonth());
            monthlyData.put(dateKey, generateDailyData(solarDay));
            MAPPER.writeValue(file, monthlyData);
            log("补全丢失数据", "日期: " + dateKey);
        }

        Map<String, Object> dayData = monthlyData.get(dateKey);
        String pinyinCategory = convertToPinyin(category);
        if (dayData.containsKey(pinyinCategory)) {
            Map<String, Object> categoryData = new HashMap<>();
            categoryData.put(pinyinCategory, dayData.get(pinyinCategory));
            result.put(dateKey, categoryData);
        }

        String jsonResult = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        log("查询结果生成", "类型: " + category + ", 日期: " + dateKey + ", 结果:\n" + jsonResult);
        return jsonResult;
    }

    // 转换为拼音
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

    // 解析日期
    private static LocalDate parseDate(String dateStr) throws IOException {
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
            } catch (DateTimeParseException ignored) {
                // 尝试下一个格式
            }
        }
        log("日期解析失败", "输入: " + dateStr);
        throw new IllegalArgumentException("无法解析日期格式: " + dateStr);
    }

    // 日志记录
    private static void log(String action, String details) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(LOG_FILE, true))) {
            String timestamp = LocalDateTime.now(BEIJING_ZONE).toString();
            writer.write(String.format("[%s] %s: %s%n", timestamp, action, details));
        } catch (IOException e) {
            System.err.println("日志记录失败: " + e.getMessage());
            throw e;
        }
    }
}