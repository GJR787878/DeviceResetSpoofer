package io.github.gjr787878.devicereset.xposed;

import org.json.JSONObject;

/**
 * 设备身份数据模型，包含所有需要伪装的设备识别码。
 */
public class Identity {
    public String androidId;
    public String advertisingId;
    public String imei;
    public String meid;
    public String serial;
    public String macAddress;
    public String gsfId;
    public String appSetId;

    // Build 信息
    public String brand;
    public String model;
    public String manufacturer;
    public String device;
    public String product;
    public String hardware;
    public String fingerprint;
    public String bootloader;
    public String radioVersion;
    public String buildId;
    public String buildTime;

    // 网络信息
    public String networkOperator;
    public String networkOperatorName;
    public String simOperator;
    public String simOperatorName;
    public String simCountryIso;
    public String networkCountryIso;

    public Identity() {
    }

    public String toJson() {
        try {
            JSONObject json = new JSONObject();
            json.put("androidId", androidId);
            json.put("advertisingId", advertisingId);
            json.put("imei", imei);
            json.put("meid", meid);
            json.put("serial", serial);
            json.put("macAddress", macAddress);
            json.put("gsfId", gsfId);
            json.put("appSetId", appSetId);
            json.put("brand", brand);
            json.put("model", model);
            json.put("manufacturer", manufacturer);
            json.put("device", device);
            json.put("product", product);
            json.put("hardware", hardware);
            json.put("fingerprint", fingerprint);
            json.put("bootloader", bootloader);
            json.put("radioVersion", radioVersion);
            json.put("buildId", buildId);
            json.put("buildTime", buildTime);
            json.put("networkOperator", networkOperator);
            json.put("networkOperatorName", networkOperatorName);
            json.put("simOperator", simOperator);
            json.put("simOperatorName", simOperatorName);
            json.put("simCountryIso", simCountryIso);
            json.put("networkCountryIso", networkCountryIso);
            return json.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public static Identity fromJson(String jsonStr) {
        try {
            JSONObject json = new JSONObject(jsonStr);
            Identity id = new Identity();
            id.androidId = json.optString("androidId", null);
            id.advertisingId = json.optString("advertisingId", null);
            id.imei = json.optString("imei", null);
            id.meid = json.optString("meid", null);
            id.serial = json.optString("serial", null);
            id.macAddress = json.optString("macAddress", null);
            id.gsfId = json.optString("gsfId", null);
            id.appSetId = json.optString("appSetId", null);
            id.brand = json.optString("brand", null);
            id.model = json.optString("model", null);
            id.manufacturer = json.optString("manufacturer", null);
            id.device = json.optString("device", null);
            id.product = json.optString("product", null);
            id.hardware = json.optString("hardware", null);
            id.fingerprint = json.optString("fingerprint", null);
            id.bootloader = json.optString("bootloader", null);
            id.radioVersion = json.optString("radioVersion", null);
            id.buildId = json.optString("buildId", null);
            id.buildTime = json.optString("buildTime", null);
            id.networkOperator = json.optString("networkOperator", null);
            id.networkOperatorName = json.optString("networkOperatorName", null);
            id.simOperator = json.optString("simOperator", null);
            id.simOperatorName = json.optString("simOperatorName", null);
            id.simCountryIso = json.optString("simCountryIso", null);
            id.networkCountryIso = json.optString("networkCountryIso", null);
            return id;
        } catch (Exception e) {
            return null;
        }
    }
}
