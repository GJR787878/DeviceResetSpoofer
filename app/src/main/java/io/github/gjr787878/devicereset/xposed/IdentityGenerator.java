package io.github.gjr787878.devicereset.xposed;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * 随机设备身份生成器。
 * 每次生成一套完整、自洽的设备信息（型号、品牌、指纹等保持一致）。
 */
public class IdentityGenerator {
    private static final SecureRandom RANDOM = new SecureRandom();

    // 真实设备型号池，随机选一套保持一致性
    private static final String[][] DEVICE_PROFILES = {
        // {BRAND, MODEL, MANUFACTURER, DEVICE, PRODUCT, HARDWARE, FINGERPRINT}
        {"Xiaomi", "23049PCD8G", "Xiaomi", "socrates", "socrates_global", "qcom",
         "Xiaomi/socrates_global/socrates:14/UKQ1.230804.001/V816.0.7.0.UMXMIXM:user/release-keys"},
        {"OnePlus", "CPH2451", "OnePlus", "OP5913L1", "CPH2451", "qcom",
         "OnePlus/CPH2451/OP5913L1:14/UKQ1.230924.001/1711900909658:user/release-keys"},
        {"samsung", "SM-S918B", "samsung", "dm3x", "dm3xxxx", "exynos2300",
         "samsung/dm3xxxx/dm3x:14/UP1A.231005.007/S918BXXS3CXD1:user/release-keys"},
        {"Google", "Pixel 8 Pro", "Google", "husky", "husky", "raven",
         "google/husky/husky:14/AP1A.240305.019.A1/11445693:user/release-keys"},
        {"HUAWEI", "ALN-AL00", "HUAWEI", "HWALN", "ALN-AL00", "kirin9010",
         "HUAWEI/ALN-AL00/HWALN:12/HUAWEIALN-AL00/4.2.0.170C00:user/release-keys"},
        {"OPPO", "PHZ110", "OPPO", "OP5943L1", "PHZ110", "qcom",
         "OPPO/PHZ110/OP5943L1:13/SP1A.210812.016/Q.1263870185:user/release-keys"},
        {"vivo", "V2324A", "vivo", "PD2324", "PD2324", "qcom",
         "vivo/PD2324/PD2324:13/TP1A.220624.014/compiler02232125:user/release-keys"},
        {"realme", "RMX3700", "realme", "RE58B2L1", "RMX3700", "qcom",
         "realme/RMX3700/RE58B2L1:13/SP1A.210812.016/1710825900:user/release-keys"},
        {"motorola", "XT2301-1", "motorola", "corfur", "corfur_g", "qcom",
         "motorola/corfur_g/corfur:13/T2SN33.55-20-2-3/2d31c4:user/release-keys"},
        {"Sony", "XQ-DQ72", "Sony", "pdx225", "XQ-DQ72", "qcom",
         "Sony/pdx225/pdx225:13/61.2.A.0.399/03990399:user/release-keys"},
    };

    // 运营商信息池
    private static final String[][] CARRIER_PROFILES = {
        // {MCCMNC, OPERATOR_NAME, COUNTRY_ISO}
        {"46001", "China Mobile", "cn"},
        {"46006", "China Unicom", "cn"},
        {"46003", "China Telecom", "cn"},
        {"46011", "China Broadnet", "cn"},
        {"310260", "T-Mobile", "us"},
        {"310410", "AT&T", "us"},
        {"311480", "Verizon", "us"},
        {"44010", "docomo", "jp"},
        {"44020", "SoftBank", "jp"},
        {"45005", "SKTelecom", "kr"},
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

        // Build时间（随机过去一年内的时间戳）
        long now = System.currentTimeMillis();
        long oneYear = 365L * 24 * 60 * 60 * 1000;
        id.buildTime = String.valueOf(now - Math.abs(RANDOM.nextLong()) % oneYear);

        // 运营商信息
        String[] carrier = CARRIER_PROFILES[RANDOM.nextInt(CARRIER_PROFILES.length)];
        id.networkOperator = carrier[0];
        id.networkOperatorName = carrier[1];
        id.simOperator = carrier[0];
        id.simOperatorName = carrier[1];
        id.simCountryIso = carrier[2];
        id.networkCountryIso = carrier[2];

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
}
