package com;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SignatureException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Date;
import java.util.TimeZone;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import okhttp3.*;

public class Uploadfiles {

    // for version 2
    private static final String BUCKET_NAME = "your bucket name";
    private static final String REGION = "us-sea";//us-sea 
    private static final String ACCESS_KEY = "your access key";
    private static final String SECRET_KEY = "your security key";
    private static final String ENDPOINT = "https://us-sea-1.linodeobjects.com"; 

    /**
     * Uploads a file to Linode Object Storage.
     *
     * @param filePath the local file path to upload
     * @param objectKey the object key (file name in the bucket)
     * @throws IOException if an error occurs during the upload
     */
    public static void uploadFile(String filePath, String objectKey) throws IOException {
        File file = new File(filePath);

        if (!file.exists()) {
            throw new IOException("File not found: " + filePath);
        }

        OkHttpClient client = new OkHttpClient();

        // Create the request body with the file
        RequestBody fileBody = RequestBody.create(file, MediaType.parse("application/octet-stream"));

        // Construct the URL for the PUT request
        String url = ENDPOINT + "/" + BUCKET_NAME + "/" + objectKey;

        // Build the request with authentication headers
        Request request = new Request.Builder()
                .url(url)
                .put(fileBody)
                .addHeader("Authorization", "AWS " + ACCESS_KEY + ":" + generateSignature(filePath, objectKey))
                .addHeader("x-amz-content-sha256", "UNSIGNED-PAYLOAD")
                .addHeader("x-amz-date", getCurrentTimestamp())
                .build();

        // Execute the request
        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                System.out.println("File uploaded successfully.");
            } else {
                System.err.println("File upload failed: " + response.message());
            }
        }
    }

    /**
     * Generates a signature for the request (placeholder).
     *
     * Replace this with actual AWS Signature v4 generation logic.
     * 
     * In step 1, "s3" string is deleted from origin AWS host to adapt linode object storage
     */
    private static String generateSignature(String filePath, String objectKey) throws IOException {
        try {
            // Define constants
            String service = "s3";
            String region = REGION;
            String algorithm = "AWS4-HMAC-SHA256";
            String amzDate = getCurrentTimestamp();
            String dateStamp = amzDate.substring(0, 8);
    
            // Step 1: Create canonical request
            String canonicalUri = "/" + BUCKET_NAME + "/" + objectKey;
            String canonicalQueryString = ""; 
            String canonicalHeaders = "host:" + BUCKET_NAME + "." + REGION + ".linodeobjects.com\n";// "s3" string is deleted from origin AWS host
            String signedHeaders = "host";
            String payloadHash = hash("UNSIGNED-PAYLOAD");
    
            String canonicalRequest = "PUT\n" +
                    canonicalUri + "\n" +
                    canonicalQueryString + "\n" +
                    canonicalHeaders + "\n" +
                    signedHeaders + "\n" +
                    payloadHash;
            System.out.println("Canonical Request:\n" + canonicalRequest);
            // Step 2: Create string to sign
            String credentialScope = dateStamp + "/" + region + "/" + service + "/aws4_request";
            String stringToSign = algorithm + "\n" +
                    amzDate + "\n" +
                    credentialScope + "\n" +
                    hash(canonicalRequest);
                    System.out.println("String to Sign:\n" + stringToSign);
            // Step 3: Calculate signature
            byte[] signingKey = getSignatureKey(SECRET_KEY, dateStamp, region, service);
            byte[] signature = hmacSHA256(stringToSign, signingKey);
            System.out.println("Signature:\n" + signature);
            return Base64.getEncoder().encodeToString(signature);
        } catch (Exception e) {
            throw new IOException("Error generating signature", e);
        }
    }
    
    private static String hash(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] encodedHash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        return bytesToHex(encodedHash);
    }
    
    private static byte[] hmacSHA256(String data, byte[] key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(key, "HmacSHA256");
        mac.init(secretKeySpec);
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }
    
    private static byte[] getSignatureKey(String key, String dateStamp, String region, String service) throws Exception {
        byte[] kSecret = ("AWS4" + key).getBytes(StandardCharsets.UTF_8);
        byte[] kDate = hmacSHA256(dateStamp, kSecret);
        byte[] kRegion = hmacSHA256(region, kDate);
        byte[] kService = hmacSHA256(service, kRegion);
        return hmacSHA256("aws4_request", kService);
    }
    
    private static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    /**
     * Returns the current timestamp in the required format (placeholder).
     *
     * Replace this with actual timestamp logic as per AWS Signature v4.
     */
    private static String getCurrentTimestamp() {
        
        ZonedDateTime now = ZonedDateTime.now(TimeZone.getTimeZone("UTC").toZoneId());
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
        return now.format(formatter);
    }
}
