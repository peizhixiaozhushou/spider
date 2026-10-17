package com.example.tvbox.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

/**
 * AES 解密 —— 算法逐字来自静态反编译观测，非推测
 *
 * 证据来源:
 *   inspect_android_method(spider_probe.apk,
 *       "com.github.catvod.spider.merge.m.a", "a")
 *   evidence_id: ev_51e2af1dbeff82d569c9bc2fb156e6ea388f750b251d9ea6ae5ed074412ed862
 *
 * 原始反编译体（观测原文）:
 *   Cipher cipher = Cipher.getInstance("AES/CBC/PKCS7Padding");
 *   cipher.init(2, new SecretKeySpec(str2.getBytes(), "AES"),
 *                  new IvParameterSpec(str3.getBytes()));
 *   return new String(cipher.doFinal(Base64.decode(str.getBytes(), 0)));
 *
 * 参数语义（由调用点 eduAesDecode 交叉确认）:
 *   str  = 密文 Base64 字符串
 *   str2 = dataKey  (ext 字段，getBytes() 默认平台编码 —— 通常是 UTF-8)
 *   str3 = dataIv   (ext 字段)
 *
 * 重要观测限制:
 *   - 原实现用 getBytes() 无参，依赖平台默认字符集；此处显式 UTF-8。
 *     若上游 key 含非 ASCII，需确认平台默认编码。
 *   - 原实现 mode=2 即 DECRYPT_MODE。
 *   - ★ "PKCS7Padding" 是 Android/BouncyCastle 扩展名，标准 JCE 不支持。
 *     因为 AES 分组固定 16 字节，PKCS5Padding 与 PKCS7Padding 完全等价。
 *     改用 PKCS5Padding 后 Android 与桌面 JVM 都能跑（实测验证）。
 *   - Base64.decode(..., 0) 为 Android flags=0（严格模式）。
 *     本重写用 java.util.Base64.getDecoder()，对换行/空白处理略有差异。
 */
public final class AesCrypto {

    private AesCrypto() {}

    /**
     * AES/CBC/PKCS7 解密
     * @param cipherText Base64 密文
     * @param key        16/24/32 字节密钥（UTF-8）
     * @param iv         16 字节 IV（UTF-8）
     * @return 明文；失败返回空串（与原实现一致）
     */
    public static String decryptCbc(String cipherText, String key, String iv) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(
                Cipher.DECRYPT_MODE,
                new SecretKeySpec(key.getBytes("UTF-8"), "AES"),
                new IvParameterSpec(iv.getBytes("UTF-8"))
            );
            byte[] raw = Base64.getDecoder().decode(cipherText);
            return new String(cipher.doFinal(raw), "UTF-8");
        } catch (Exception e) {
            // 原实现: e.printStackTrace(); SpiderDebug.log(e); return "";
            return "";
        }
    }

    /**
     * 等价写法：Android 平台专用（PKCS5Padding 在 AES 下等价 PKCS7）
     */
    public static String decryptCbcAndroid(String cipherText, String key, String iv) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(
                Cipher.DECRYPT_MODE,
                new SecretKeySpec(key.getBytes("UTF-8"), "AES"),
                new IvParameterSpec(iv.getBytes("UTF-8"))
            );
            byte[] raw = android.util.Base64.decode(cipherText, 0);
            return new String(cipher.doFinal(raw), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
