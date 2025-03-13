package com.zafng.hello_java;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.*;

public class LinodeStorageUploader_new {

    // 配置信息
    private static final String ACCESS_KEY = "your ak";
    private static final String SECRET_KEY = "your sk";
    private static final String REGION = "us-sea-9"; // FIXED: Use Linode's default region
    private static final String HOST_NAME = "your-bucket-name." + REGION + ".linodeobjects.com"; // FIXED: Corrected hostname
    private static final String UPLOAD_PATH = "path/in/bucket/file.md";
    private static final String FILE_PATH = "/local/path/to/file.md";

    // HMAC SHA256 加密
    private static byte[] hmacSha256(byte[] key, String data) throws Exception { // FIXED: Key should be byte[], not String
        Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKey = new SecretKeySpec(key, "HmacSHA256"); // FIXED: Use byte array directly
        sha256_HMAC.init(secretKey);
        return sha256_HMAC.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    // SHA256 哈希
    private static byte[] hashSha256(String data) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest(data.getBytes(StandardCharsets.UTF_8));
    }

    // 将字节数组转换为十六进制字符串
    private static String toHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    // 上传文件到 Linode 对象存储
    public static int uploadFile(String filePath) throws Exception {
        // 读取文件内容
        File file = new File(filePath);
        byte[] fileContent = new byte[(int) file.length()];
        try (FileInputStream fis = new FileInputStream(file)) {
            fis.read(fileContent);
        }

        // 计算文件 SHA256 哈希
        byte[] fileHash = hashSha256(new String(fileContent, StandardCharsets.UTF_8)); // FIXED: Compute actual file hash
        String fileHashHex = toHex(fileHash); // FIXED: Replace "UNSIGNED-PAYLOAD" with real hash

        // 获取当前日期和时间
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd");
        SimpleDateFormat dateTimeFormat = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        dateTimeFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        String date = dateFormat.format(new Date());
        String datetime = dateTimeFormat.format(new Date());

        // 创建规范请求
        String canonicalRequest = String.format(
                "PUT\n/%s\n\nhost:%s\nx-amz-content-sha256:%s\nx-amz-date:%s\n\nhost;x-amz-content-sha256;x-amz-date\n%s",
                UPLOAD_PATH, HOST_NAME, fileHashHex, datetime, fileHashHex); // FIXED: Use computed file hash

        // 哈希规范请求
        byte[] canonicalRequestHash = hashSha256(canonicalRequest);
        String canonicalRequestHashHex = toHex(canonicalRequestHash);

        // 创建待签名字符串
        String stringToSign = String.format(
                "AWS4-HMAC-SHA256\n%s\n%s/%s/s3/aws4_request\n%s",
                datetime, date, REGION, canonicalRequestHashHex); // FIXED: Corrected region usage

        // 计算签名密钥
        byte[] kDate = hmacSha256(("AWS4" + SECRET_KEY).getBytes(StandardCharsets.UTF_8), date); // FIXED: Use byte[]
        byte[] kRegion = hmacSha256(kDate, REGION);
        byte[] kService = hmacSha256(kRegion, "s3");
        byte[] kSigning = hmacSha256(kService, "aws4_request");

        // 签名字符串
        byte[] signature = hmacSha256(kSigning, stringToSign);
        String signatureHex = toHex(signature);

        // 创建授权头
        String authorizationHeader = String.format(
                "AWS4-HMAC-SHA256 Credential=%s/%s/%s/s3/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=%s",
                ACCESS_KEY, date, REGION, signatureHex); // FIXED: Ensure region is correct

        // 创建 x-amz-date 头
        String xAmzDateHeader = "x-amz-date: " + datetime;

        // 上传文件
        URL url = new URL("https://" + HOST_NAME + "/" + UPLOAD_PATH);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("PUT");
        connection.setDoOutput(true);
        connection.setRequestProperty("x-amz-content-sha256", fileHashHex); // FIXED: Use actual file hash
        connection.setRequestProperty("x-amz-date", datetime);
        connection.setRequestProperty("Authorization", authorizationHeader);

        try (OutputStream os = connection.getOutputStream()) {
            os.write(fileContent);
        }

        // 获取响应状态码
        int responseCode = connection.getResponseCode();

        // 打印响应头
        System.out.println("===== Response Headers =====");
        for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
            System.out.println(header.getKey() + ": " + header.getValue());
        }

        // 打印响应体
        System.out.println("===== Response Body =====");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                responseCode == HttpURLConnection.HTTP_OK ? connection.getInputStream() : connection.getErrorStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
        }

        connection.disconnect();
        return responseCode;
    }

    public static void main(String[] args) {
        try {
            if (uploadFile(FILE_PATH) == HttpURLConnection.HTTP_OK) {
                System.out.println("Upload completed!");
            } else {
                System.out.println("Upload failed.");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
