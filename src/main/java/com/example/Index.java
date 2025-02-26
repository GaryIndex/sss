package com.example;

import cn.hutool.core.io.FileUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tyme.culture.Holiday;
import com.tyme.culture.Hour;
import com.tyme.culture.SixtyCycle;
import com.tyme.lunar.LunarDay;
import com.tyme.lunar.LunarMonth;
import com.tyme.lunar.LunarYear;
import com.tyme.solar.SolarDay;
import com.tyme.solar.SolarTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Index 类用于生成和查询基于公历、农历及相关信息的每日JSON数据。
 * 支持同步历史数据、查询特定日期的指定类型信息，并记录操作日志。
 */
public class Index {
    // 使用 SLF4J 日志框架
    private static final Logger LOGGER = LoggerFactory.getLogger(Index.class);

    // 配置常量（可移至外部配置文件）
    private static final String BASE_DIR = System.getProperty("user.dir");
    private static final String OUTPUT_DIR = BASE_DIR + "/Datefile";
    private static final String LOG_DIR = BASE_DIR + "/logs";
    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate START_DATE = LocalDate.of(2025, 1, 1);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // 支持的日期格式
    private static final DateTimeFormatter[] DATE_FORMATS = {
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd"),
        DateTimeFormatter.ofPattern("yyyyMMdd")
    };

    // 文件操作锁，确保并发安全
    private static final ReentrantLock FILE_LOCK = new ReentrantLock();

    // 类别拼音映射表，提升扩展性
    private static final Map<String, String> CATEGORY_PINYIN_MAP = new HashMap<>();

    static {
        CATEGORY_PINYIN_MAP.put("节假日", "jiejiari");
        CATEGORY_PINYIN_MAP.put("12时辰", "shierShichen");
        CATEGORY_PINYIN_MAP.put("24节气", "ershisiJieqi");
        CATEGORY_PINYIN_MAP.put("基本日期信息", "jibenRqiXinxi");
        CATEGORY_PINYIN_MAP.put("老黄历", "laohuangli");
        CATEGORY_PINYIN_MAP.put("星座", "xingzuo");
    }

    public static void main(String[] args) {
        try {
            initDirectories();
            LocalDate today = LocalDate.now(BEIJING_ZONE);
            LOGGER.info("程序启动，当前日期: {}", today);

            syncAllData(START_DATE, today);

            String todayHolidayJson = queryData("节假日", null);
            LOGGER.info("当天节假日查询结果:\n{}", todayHolidayJson);

            String holidayJson1 = queryData("节假日", "2025-02-25");
            LOGGER.info("节假日 2025-02-25 查询结果:\n{}", holidayJson1);

            String holidayJson2 = queryData("节假日", "2025/02/25");
            LOGGER.info("节假日 2025/02/25 查询结果:\n{}", holidayJson2);

            String hoursJson = queryData("12时辰", "20250225");
            LOGGER.info("12时辰 2025-02-25 查询结果:\n{}", hoursJson);
        } catch (IOException e) {
            LOGGER.error("程序运行异常", e);
        }
    }

    // 初始化目录结构
    private static void initDirectories() throws IOException {
        try {
            FileUtil.mkdir(OUTPUT_DIR);
            FileUtil.mkdir(LOG_DIR);
            LOGGER.info("目录初始化完成，输出目录: {}, 日志目录: {}", OUTPUT_DIR, LOG_DIR);
        } catch (Exception e) {
            LOGGER.error("无法创建目录", e);
            throw new IOException("目录初始化失败", e);
        }
    }

    // 美化JSON输出（优化为可选）
    private static String prettyPrintJson(String json, boolean pretty) throws IOException {
        if (!pretty) return json;
        Object jsonObject = MAPPER.readValue(json, Object.class);
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(jsonObject);
    }

    // 同步所有数据
    private static void syncAllData(LocalDate startDate, LocalDate today) throws IOException {
        LOGGER.info("开始同步所有数据，起始日期: {}, 结束日期: {}", startDate, today);
        for (int year = startDate.getYear(); year <= today.getYear(); year++) {
            int startMonth = (year == startDate.getYear()) ? startDate.getMonthValue() : 1;
            int endMonth = (year == today.getYear()) ? today.getMonthValue() : 12;

            for (int month = startMonth; month <= endMonth; month++) {
                syncMonthlyData(year, month, today);
            }
        }
        LOGGER.info("完成同步所有数据，结束时间: {}", LocalDateTime.now(BEIJING_ZONE));
    }

    // 同步月度数据
    private static void syncMonthlyData(int year, int month, LocalDate today) throws IOException {
        String dirPath = OUTPUT_DIR + "/" + year + "/" + String.format("%02d", month);
        FileUtil.mkdir(dirPath);
        String filePath = dirPath + "/data.json";
        File file = new File(filePath);

        int daysInMonth = SolarDay.fromYmd(year, month, 1).getLastDayOfMonth().getDay();
        int endDay = (year == today.getYear() && month == today.getMonthValue()) ? today.getDayOfMonth() : daysInMonth;

        Map<String, Map<String, Object>> monthlyData;
        FILE_LOCK.lock();
        try {
            if (file.exists()) {
                monthlyData = MAPPER.readValue(file, MAPPER.getTypeFactory().constructMapType(Map.class, String.class, Map.class));
                monthlyData = updateOrFillMonthlyData(monthlyData, year, month, endDay);
            } else {
                monthlyData = generateFullMonthlyData(year, month, endDay);
            }
            MAPPER.writeValue(file, monthlyData);
            LOGGER.info("更新月度文件，路径: {}，共 {} 天", filePath, monthlyData.size());
        } finally {
            FILE_LOCK.unlock();
        }
    }

    // 生成完整月度数据
    private static Map<String, Map<String, Object>> generateFullMonthlyData(int year, int month, int endDay) throws IOException {
        Map<String, Map<String, Object>> monthlyData = new HashMap<>();
        for (int day = 1; day <= endDay; day++) {
            SolarDay solarDay = SolarDay.fromYmd(year, month, day);
            monthlyData.put(solarDay.toString(), generateDailyData(solarDay));
        }
        LOGGER.info("生成完整月度数据，年月: {}-{}", year, String.format("%02d", month));
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
                LOGGER.info("补全月度数据，日期: {}", dateKey);
            }
        }
        return updatedData;
    }

    // 检查数据是否完整（扩展检查所有关键字段）
    private static boolean isDataIncomplete(Map<String, Object> dayData) {
        return dayData == null || !dayData.containsKey("shierShichen") || !dayData.containsKey("ershisiJieqi")
            || !dayData.containsKey("jiejiari") || !dayData.containsKey("laohuangli");
    }

    // 生成单日数据
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

        LOGGER.debug("生成单日数据，日期: {}, 数据详情: {}", solarDay, MAPPER.writeValueAsString(dailyData));
        return dailyData;
    }

    // 查询数据
    public static String queryData(String category, String dateStr) throws IOException {
        LocalDate date = parseDate(dateStr);
        String filePath = OUTPUT_DIR + "/" + date.getYear() + "/" + String.format("%02d", date.getMonthValue()) + "/data.json";
        File file = new File(filePath);
        Map<String, Map<String, Object>> monthlyData;

        LOGGER.info("查询数据，类型: {}, 日期: {}", category, date);

        FILE_LOCK.lock();
        try {
            if (file.exists()) {
                monthlyData = MAPPER.readValue(file, MAPPER.getTypeFactory().constructMapType(Map.class, String.class, Map.class));
            } else {
                monthlyData = new HashMap<>();
                LOGGER.warn("查询文件不存在，路径: {}", filePath);
            }

            String dateKey = date.toString();
            Map<String, Object> result = new HashMap<>();

            if (!monthlyData.containsKey(dateKey) || isDataIncomplete(monthlyData.get(dateKey))) {
                SolarDay solarDay = SolarDay.fromYmd(date.getYear(), date.getMonthValue(), date.getDayOfMonth());
                monthlyData.put(dateKey, generateDailyData(solarDay));
                MAPPER.writeValue(file, monthlyData);
                LOGGER.info("补全丢失数据，日期: {}", dateKey);
            }

            Map<String, Object> dayData = monthlyData.get(dateKey);
            String pinyinCategory = convertToPinyin(category);
            if (dayData.containsKey(pinyinCategory)) {
                Map<String, Object> categoryData = new HashMap<>();
                categoryData.put(pinyinCategory, dayData.get(pinyinCategory));
                result.put(dateKey, categoryData);
            }

            String jsonResult = MAPPER.writeValueAsString(result);
            return prettyPrintJson(jsonResult, true); // 可配置是否美化
        } finally {
            FILE_LOCK.unlock();
        }
    }

    // 转换为拼音
    private static String convertToPinyin(String category) {
        return CATEGORY_PINYIN_MAP.getOrDefault(category, category);
    }

    // 解析日期
    private static LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            LocalDate today = LocalDate.now(BEIJING_ZONE);
            LOGGER.info("使用默认日期，当天: {}", today);
            return today;
        }

        for (DateTimeFormatter formatter : DATE_FORMATS) {
            try {
                LocalDate date = LocalDate.parse(dateStr, formatter);
                LOGGER.debug("日期解析成功，{} -> {}", dateStr, date);
                return date;
            } catch (DateTimeParseException ignored) {
                // 尝试下一个格式
            }
        }
        LOGGER.error("无法解析日期格式: {}", dateStr);
        throw new IllegalArgumentException("无效的日期格式: " + dateStr + "，支持格式: yyyy-MM-dd, yyyy/MM/dd, yyyyMMdd");
    }
}