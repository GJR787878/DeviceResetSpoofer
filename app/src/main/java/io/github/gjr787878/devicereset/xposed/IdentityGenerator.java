package io.github.gjr787878.devicereset.xposed;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * 随机设备身份生成器。
 * 每次生成一套完整、自洽的设备信息（型号、品牌、指纹等保持一致）。
 */
public class IdentityGenerator {
    private static final SecureRandom RANDOM = new SecureRandom();

    // 真实设备型号池（25套），随机选一套保持一致性
    private static final String[][] DEVICE_PROFILES = {
        // {BRAND, MODEL, MANUFACTURER, DEVICE, PRODUCT, HARDWARE, FINGERPRINT}
        {"Xiaomi", "23049PCD8G", "Xiaomi", "socrates", "socrates_global", "qcom",
         "Xiaomi/socrates_global/socrates:14/UKQ1.230804.001/V816.0.7.0.UMXMIXM:user/release-keys"},
        {"Xiaomi", "2210132C", "Xiaomi", "zeus", "zeus_global", "qcom",
         "Xiaomi/zeus_global/zeus:13/TKQ1.220829.002/V14.0.4.0.TLBCNXM:user/release-keys"},
        {"Redmi", "22021211RC", "Redmi", "munch", "munch_global", "qcom",
         "POCO/munch_global/munch:13/RKQ1.211001.001/V14.0.2.0.TLMMIXM:user/release-keys"},
        {"Redmi", "23076RN82C", "Redmi", "sapphire", "sapphire_global", "mtk",
         "Redmi/sapphire_global/sapphire:13/TKQ1.221114.001/V14.0.4.0.TMGCNXM:user/release-keys"},
        {"OnePlus", "CPH2451", "OnePlus", "OP5913L1", "CPH2451", "qcom",
         "OnePlus/CPH2451/OP5913L1:14/UKQ1.230924.001/1711900909658:user/release-keys"},
        {"OnePlus", "CPH2581", "OnePlus", "OP594BL1", "CPH2581", "qcom",
         "OnePlus/CPH2581/OP594BL1:14/UKQ1.230924.001/1721900100000:user/release-keys"},
        {"samsung", "SM-S918B", "samsung", "dm3x", "dm3xxxx", "exynos2300",
         "samsung/dm3xxxx/dm3x:14/UP1A.231005.007/S918BXXS3CXD1:user/release-keys"},
        {"samsung", "SM-S921B", "samsung", "e3x", "e3xxxx", "exynos2400",
         "samsung/e3xxxx/e3x:14/UP1A.231005.007/S921BXXU1AXAB:user/release-keys"},
        {"samsung", "SM-A546B", "samsung", "a54x", "a54xx", "exynos1380",
         "samsung/a54xx/a54x:14/UP1A.231005.007/A546BXXS6CXI3:user/release-keys"},
        {"samsung", "SM-F946B", "samsung", "q6q", "q6qxxx", "sd8gen2",
         "samsung/q6qxxx/q6q:14/UP1A.231005.007/F946BXXU1AWJ3:user/release-keys"},
        {"Google", "Pixel 8 Pro", "Google", "husky", "husky", "raven",
         "google/husky/husky:14/AP1A.240305.019.A1/11445693:user/release-keys"},
        {"Google", "Pixel 8", "Google", "shiba", "shiba", "raven",
         "google/shiba/shiba:14/AP1A.240305.019.A1/11445693:user/release-keys"},
        {"Google", "Pixel 7 Pro", "Google", "cheetah", "cheetah", "raven",
         "google/cheetah/cheetah:14/AP1A.240305.019.A1/11445693:user/release-keys"},
        {"Google", "Pixel 9 Pro", "Google", "komodo", "komodo", "raven",
         "google/komodo/komodo:14/AP1A.241003.001/12181150:user/release-keys"},
        {"HUAWEI", "ALN-AL00", "HUAWEI", "HWALN", "ALN-AL00", "kirin9010",
         "HUAWEI/ALN-AL00/HWALN:12/HUAWEIALN-AL00/4.2.0.170C00:user/release-keys"},
        {"HUAWEI", "MNA-AL00", "HUAWEI", "HWMNA", "MNA-AL00", "kirin9000s",
         "HUAWEI/MNA-AL00/HWMNA:12/HUAWEIMNA-AL00/4.2.0.165C00:user/release-keys"},
        {"HONOR", "PGT-AN00", "HONOR", "PGT-AN00", "PGT-AN00", "kirin9000",
         "HONOR/PGT-AN00/PGT-AN00:12/HONORPGT-AN00/7.0.0.129:user/release-keys"},
        {"OPPO", "PHZ110", "OPPO", "OP5943L1", "PHZ110", "qcom",
         "OPPO/PHZ110/OP5943L1:13/SP1A.210812.016/Q.1263870185:user/release-keys"},
        {"OPPO", "CPH2553", "OPPO", "OP5969L1", "CPH2553", "mtk",
         "OPPO/CPH2553/OP5969L1:14/UKQ1.231108.001/1716700300000:user/release-keys"},
        {"vivo", "V2324A", "vivo", "PD2324", "PD2324", "qcom",
         "vivo/PD2324/PD2324:13/TP1A.220624.014/compiler02232125:user/release-keys"},
        {"vivo", "V2339A", "vivo", "PD2339", "PD2339", "mtk",
         "vivo/PD2339/PD2339:14/UKQ1.230804.001/compiler04150021:user/release-keys"},
        {"iQOO", "I2301", "iQOO", "I2301", "I2301", "qcom",
         "iQOO/I2301/I2301:14/UKQ1.230804.001/compiler03120011:user/release-keys"},
        {"realme", "RMX3700", "realme", "RE58B2L1", "RMX3700", "qcom",
         "realme/RMX3700/RE58B2L1:13/SP1A.210812.016/1710825900:user/release-keys"},
        {"motorola", "XT2301-1", "motorola", "corfur", "corfur_g", "qcom",
         "motorola/corfur_g/corfur:13/T2SN33.55-20-2-3/2d31c4:user/release-keys"},
        {"motorola", "XT2409-1", "motorola", "penang", "penang_g", "qcom",
         "motorola/penang_g/penang:14/UP1A.231005.007/1720300100:user/release-keys"},
        {"Sony", "XQ-DQ72", "Sony", "pdx225", "XQ-DQ72", "qcom",
         "Sony/pdx225/pdx225:13/61.2.A.0.399/03990399:user/release-keys"},
        {"Sony", "XQ-EC54", "Sony", "pdx237", "XQ-EC54", "qcom",
         "Sony/pdx237/pdx237:14/64.0.A.2.113/011300113:user/release-keys"},
        {"Nothing", "A065", "Nothing", "Spacewar", "Spacewar", "qcom",
         "Nothing/Spacewar/Spacewar:14/UKQ1.230924.001/2.5.5:user/release-keys"},
        {"ASUS", "AI2401", "ASUS", "AI2401", "WW_AI2401", "qcom",
         "asus/WW_AI2401/AI2401:14/UKQ1.230804.001/34.0208.2401.0:user/release-keys"},
    };

    // 运营商信息池（20+）
    private static final String[][] CARRIER_PROFILES = {
        // {MCCMNC, OPERATOR_NAME, COUNTRY_ISO}
        {"46001", "China Mobile", "cn"},
        {"46006", "China Unicom", "cn"},
        {"46003", "China Telecom", "cn"},
        {"46011", "China Broadnet", "cn"},
        {"310260", "T-Mobile", "us"},
        {"310410", "AT&T", "us"},
        {"311480", "Verizon", "us"},
        {"310120", "Sprint", "us"},
        {"44010", "docomo", "jp"},
        {"44020", "SoftBank", "jp"},
        {"44051", "au", "jp"},
        {"45005", "SKTelecom", "kr"},
        {"45008", "KT", "kr"},
        {"45006", "LG U+", "kr"},
        {"23410", "O2", "gb"},
        {"23415", "Vodafone", "gb"},
        {"23430", "EE", "gb"},
        {"20801", "Orange", "fr"},
        {"20810", "SFR", "fr"},
        {"26201", "Telekom", "de"},
        {"26202", "Vodafone", "de"},
        {"22201", "TIM", "it"},
        {"21401", "Vodafone", "es"},
        {"50501", "Telstra", "au"},
        {"302220", "Rogers", "ca"},
        {"302610", "Bell", "ca"},
        {"72402", "TIM", "br"},
        {"25001", "MTS", "ru"},
        {"40401", "Jio", "in"},
        {"52001", "AIS", "th"},
    };

    // 真实传感器型号池：{VENDOR, NAME, VERSION}
    private static final String[][] SENSOR_PROFILES = {
        {"Bosch", "LSM6DSL Accelerometer", "1"},
        {"Bosch", "BMI260 Accelerometer", "1"},
        {"Bosch", "BMM150 Magnetometer", "1"},
        {"STMicroelectronics", "LIS2DH Accelerometer", "1"},
        {"STMicroelectronics", "LSM6DSO Accelerometer", "1"},
        {"STMicroelectronics", "LIS2MDL Magnetometer", "1"},
        {"InvenSense", "ICM42688 Accelerometer", "1"},
        {"InvenSense", "ICM20690 Accelerometer", "1"},
        {"InvenSense", "MPU-6500 Accelerometer", "1"},
        {"Google", "Goldfish Accelerometer", "1"},
        {"AOSP", "Android Rotation Vector Sensor", "1"},
    };

    public static Identity generateRandom() {
        Identity id = new Identity();

        // Android ID: 16位hex
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        id.androidId = sb.toString();

        // 广告ID: UUID
        id.advertisingId = UUID.randomUUID().toString();

        // AppSet ID: UUID
        id.appSetId = UUID.randomUUID().toString();

        // GSF ID: 16位hex (Android ID格式类似)
        byte[] gsfBytes = new byte[8];
        RANDOM.nextBytes(gsfBytes);
        StringBuilder gsfSb = new StringBuilder();
        for (byte b : gsfBytes) gsfSb.append(String.format("%02x", b));
        id.gsfId = gsfSb.toString();

        // IMEI: 15位，符合Luhn校验
        id.imei = generateValidImei();

        // MEID: 14位hex
        byte[] meidBytes = new byte[7];
        RANDOM.nextBytes(meidBytes);
        StringBuilder meidSb = new StringBuilder();
        for (byte b : meidBytes) meidSb.append(String.format("%02X", b));
        id.meid = meidSb.toString();

        // Serial: 随机字母数字
        id.serial = generateRandomSerial();

        // MAC地址
        id.macAddress = generateRandomMac();

        // 蓝牙 MAC（独立生成，与 WiFi MAC 不同）
        id.bluetoothMac = generateRandomMac();

        // 设备型号信息
        String[] profile = DEVICE_PROFILES[RANDOM.nextInt(DEVICE_PROFILES.length)];
        id.brand = profile[0];
        id.model = profile[1];
        id.manufacturer = profile[2];
        id.device = profile[3];
        id.product = profile[4];
        id.hardware = profile[5];
        id.fingerprint = profile[6];

        // Bootloader
        id.bootloader = "bootloader-" + Integer.toHexString(RANDOM.nextInt(0xFFFFFF));

        // Radio版本
        id.radioVersion = "radio-" + Integer.toHexString(RANDOM.nextInt(0xFFFFFF));

        // Build ID
        id.buildId = "AP1A." + (202400000 + RANDOM.nextInt(100000)) + ".00" + RANDOM.nextInt(10);

        // Build 扩展字段（与 release 正式版自洽）
        id.buildTags = "release-keys";
        id.buildType = "user";
        id.buildUser = "android-build";
        id.buildHost = "android-build";
        // 真实设备 DISPLAY 通常等于 buildId，保持一致
        id.buildDisplay = id.buildId;

        // Build时间（随机过去一年内的时间戳）
        long now = System.currentTimeMillis();
        long oneYear = 365L * 24 * 60 * 60 * 1000;
        id.buildTime = String.valueOf(now - Math.abs(RANDOM.nextLong()) % oneYear);

        // Widevine DRM 设备唯一 ID：32 字节随机数，hex 存储（hook 时还原为 byte[]）
        byte[] drmBytes = new byte[32];
        RANDOM.nextBytes(drmBytes);
        StringBuilder drmSb = new StringBuilder();
        for (byte b : drmBytes) drmSb.append(String.format("%02x", b));
        id.drmId = drmSb.toString();

        // 传感器指纹：从真实传感器型号池随机选一套
        String[] sensor = SENSOR_PROFILES[RANDOM.nextInt(SENSOR_PROFILES.length)];
        id.sensorVendor = sensor[0];
        id.sensorName = sensor[1];
        id.sensorVersion = sensor[2];

        // 运营商信息
        String[] carrier = CARRIER_PROFILES[RANDOM.nextInt(CARRIER_PROFILES.length)];
        id.networkOperator = carrier[0];
        id.networkOperatorName = carrier[1];
        id.simOperator = carrier[0];
        id.simOperatorName = carrier[1];
        id.simCountryIso = carrier[2];
        id.networkCountryIso = carrier[2];

        /*
         * IMSI：MCCMNC(即simOperator) + 9位随机序号。
         * 预生成一次并固定，避免每次调用 getSubscriberId 都变化、
         * 被App检测到不一致；使用 SecureRandom。
         */
        id.imsi = carrier[0]
                + String.format(
                        "%09d",
                        RANDOM.nextInt(1000000000)
                );

        /*
         * ICCID(SIM卡序列号)：20位数字。
         * 同样预生成固定值，避免每次调用都变。
         */
        StringBuilder iccid =
                new StringBuilder(
                        "8986"
                );

        for (int i = 0; i < 16; i++) {

            iccid.append(
                    RANDOM.nextInt(10)
            );
        }

        id.iccid = iccid.toString();

        return id;
    }

    private static String generateValidImei() {
        // TAC (8位) + SNR (6位) + Luhn校验位 (1位)
        // 用一些真实的TAC前缀
        String[] tacs = {
            "35209900", "35693803", "35925005", "86706904",
            "35307306", "86960804", "35497810", "86429805",
            "35713905", "86749803"
        };
        String tac = tacs[RANDOM.nextInt(tacs.length)];
        StringBuilder snr = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            snr.append(RANDOM.nextInt(10));
        }
        String base = tac + snr;
        // Luhn校验
        int sum = 0;
        for (int i = 0; i < 14; i++) {
            int digit = base.charAt(i) - '0';
            if (i % 2 == 1) {
                digit *= 2;
                if (digit > 9) digit -= 9;
            }
            sum += digit;
        }
        int check = (10 - (sum % 10)) % 10;
        return base + check;
    }

    private static String generateRandomSerial() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private static String generateRandomMac() {
        // 第一个字节的第二位设为1（本地管理地址），避免和真实厂商冲突
        byte[] mac = new byte[6];
        RANDOM.nextBytes(mac);
        mac[0] = (byte) (mac[0] | 0x02);  // 本地管理位
        mac[0] = (byte) (mac[0] & 0xFE);  // 单播
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            sb.append(String.format("%02X", mac[i]));
            if (i < 5) sb.append(":");
        }
        return sb.toString();
    }

    /**
     * 补齐缺失字段：旧版本身份文件可能缺少 IMSI/ICCID 等字段（导致编辑页默认空）。
     * 只补 null 的字段，保留用户已填写的值。
     */
    public static void fillMissing(Identity id) {
        if (id == null) return;
        Identity fresh = generateRandom();
        if (id.androidId == null) id.androidId = fresh.androidId;
        if (id.advertisingId == null) id.advertisingId = fresh.advertisingId;
        if (id.appSetId == null) id.appSetId = fresh.appSetId;
        if (id.gsfId == null) id.gsfId = fresh.gsfId;
        if (id.imei == null) id.imei = fresh.imei;
        if (id.meid == null) id.meid = fresh.meid;
        if (id.serial == null) id.serial = fresh.serial;
        if (id.macAddress == null) id.macAddress = fresh.macAddress;
        if (id.brand == null) id.brand = fresh.brand;
        if (id.model == null) id.model = fresh.model;
        if (id.manufacturer == null) id.manufacturer = fresh.manufacturer;
        if (id.device == null) id.device = fresh.device;
        if (id.product == null) id.product = fresh.product;
        if (id.hardware == null) id.hardware = fresh.hardware;
        if (id.fingerprint == null) id.fingerprint = fresh.fingerprint;
        if (id.bootloader == null) id.bootloader = fresh.bootloader;
        if (id.radioVersion == null) id.radioVersion = fresh.radioVersion;
        if (id.buildId == null) id.buildId = fresh.buildId;
        if (id.buildTime == null) id.buildTime = fresh.buildTime;
        if (id.buildTags == null) id.buildTags = fresh.buildTags;
        if (id.buildType == null) id.buildType = fresh.buildType;
        if (id.buildUser == null) id.buildUser = fresh.buildUser;
        if (id.buildHost == null) id.buildHost = fresh.buildHost;
        if (id.buildDisplay == null) id.buildDisplay = fresh.buildDisplay;
        if (id.drmId == null) id.drmId = fresh.drmId;
        if (id.bluetoothMac == null) id.bluetoothMac = fresh.bluetoothMac;
        if (id.sensorVendor == null) id.sensorVendor = fresh.sensorVendor;
        if (id.sensorName == null) id.sensorName = fresh.sensorName;
        if (id.sensorVersion == null) id.sensorVersion = fresh.sensorVersion;
        if (id.networkOperator == null) id.networkOperator = fresh.networkOperator;
        if (id.networkOperatorName == null) id.networkOperatorName = fresh.networkOperatorName;
        if (id.simOperator == null) id.simOperator = fresh.simOperator;
        if (id.simOperatorName == null) id.simOperatorName = fresh.simOperatorName;
        if (id.simCountryIso == null) id.simCountryIso = fresh.simCountryIso;
        if (id.networkCountryIso == null) id.networkCountryIso = fresh.networkCountryIso;
        if (id.imsi == null) id.imsi = fresh.imsi;
        if (id.iccid == null) id.iccid = fresh.iccid;
    }
}
