#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <openssl/hmac.h>
#include <openssl/sha.h>
#include <curl/curl.h>

// Debug callback function
/* static int my_debug_callback(CURL *handle, curl_infotype type, char *data, size_t size, void *userptr) {
    const char *text;
    switch (type) {
        case CURLINFO_TEXT:
            text = "== Info:";
            break;
        case CURLINFO_HEADER_IN:
            text = "< Header In:";
            break;
        case CURLINFO_HEADER_OUT:
            text = "> Header Out:";
            break;
        case CURLINFO_DATA_IN:
            text = "< Data In:";
            break;
        case CURLINFO_DATA_OUT:
            text = "> Data Out:";
            break;
        default:
            return 0;
    }

    fprintf(stderr, "%s %.*s\n", text, (int)size, data);
    return 0;
} */

#if 0
#define ACCESS_KEY "your access key"
#define SECRET_KEY "your secret key"
#define HOST_NAME "your-bucket-name.us-iad-10.linodeobjects.com"
#else
#define REGION "us-sea-9"
#define ACCESS_KEY "your access key"
#define SECRET_KEY "your secret key"
#define HOST_NAME "your-bucket-name." REGION ".linodeobjects.com"
#endif
#define UPLOAD_PATH "path/in/bucket/filename"
#define FILE_PATH "/path/in/local/filename"

void hmac_sha256(const char *key, const char *data, unsigned char *output, unsigned int *output_len) {
    HMAC(EVP_sha256(), key, strlen(key), (unsigned char *)data, strlen(data), output, output_len);
}

void hash_sha256(const char *data, unsigned char *output) {
    SHA256((unsigned char *)data, strlen(data), output);
}

void to_hex(const unsigned char *input, int length, char *output) {
    for (int i = 0; i < length; i++) {
        sprintf(output + (i * 2), "%02x", input[i]);
    }
    output[length * 2] = '\0';
}

int upload_file(const char *file_path) {
    FILE *file = fopen(file_path, "rb");
    if (!file) {
        perror("Failed to open file");
        return -1;
    }

    fseek(file, 0, SEEK_END);
    size_t file_size = ftell(file);
    fseek(file, 0, SEEK_SET);

    unsigned char *file_content = (unsigned char *)malloc(file_size);
    if (!file_content) {
        perror("Failed to allocate memory");
        fclose(file);
        return -1;
    }

    fread(file_content, 1, file_size, file);
    fclose(file);

    // Get current date and time
    char date[9], datetime[17];
    time_t now = time(NULL);
    struct tm *gmt = gmtime(&now);
    strftime(date, sizeof(date), "%Y%m%d", gmt);
    strftime(datetime, sizeof(datetime), "%Y%m%dT%H%M%SZ", gmt);

    // Create canonical request
    char canonical_request[2048];
    snprintf(canonical_request, sizeof(canonical_request),
             "PUT\n/%s\n\nhost:%s\nx-amz-content-sha256:UNSIGNED-PAYLOAD\nx-amz-date:%s\n\nhost;x-amz-content-sha256;x-amz-date\nUNSIGNED-PAYLOAD",
             UPLOAD_PATH, HOST_NAME, datetime);

    // Hash the canonical request
    unsigned char canonical_request_hash[SHA256_DIGEST_LENGTH];
    hash_sha256(canonical_request, canonical_request_hash);

    char canonical_request_hash_hex[65];
    to_hex(canonical_request_hash, SHA256_DIGEST_LENGTH, canonical_request_hash_hex);

    // Create string to sign
    char string_to_sign[2048];
    snprintf(string_to_sign, sizeof(string_to_sign),
             "AWS4-HMAC-SHA256\n%s\n%s/%s/s3/aws4_request\n%s",
             //  datetime, date, "us-east-1", canonical_request_hash_hex);
             datetime, date, REGION, canonical_request_hash_hex);

    // Derive signing key
    unsigned char k_date[SHA256_DIGEST_LENGTH], k_region[SHA256_DIGEST_LENGTH], k_service[SHA256_DIGEST_LENGTH], signing_key[SHA256_DIGEST_LENGTH];
    unsigned int len;
    char key[256];

    snprintf(key, sizeof(key), "AWS4%s", SECRET_KEY);
    hmac_sha256(key, date, k_date, &len);
    // hmac_sha256((char *)k_date, "us-east-1", k_region, &len);
    hmac_sha256((char *)k_date, REGION, k_region, &len);
    hmac_sha256((char *)k_region, "s3", k_service, &len);
    hmac_sha256((char *)k_service, "aws4_request", signing_key, &len);

    // Sign the string to sign
    unsigned char signature[SHA256_DIGEST_LENGTH];
    hmac_sha256((char *)signing_key, string_to_sign, signature, &len);

    char signature_hex[65];
    to_hex(signature, SHA256_DIGEST_LENGTH, signature_hex);

    // Create authorization header
    char authorization_header[512];
    snprintf(
        authorization_header, sizeof(authorization_header),
        //  "Authorization:AWS4-HMAC-SHA256
        //  Credential=%s/%s/us-east-1/s3/aws4_request,
        //  SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=%s",
        "Authorization:AWS4-HMAC-SHA256 Credential=%s/%s/" REGION
        "/s3/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date, "
        "Signature=%s",
        ACCESS_KEY, date, signature_hex);

    // Prepare x-amz-date header
    char x_amz_date_header[128];
    snprintf(x_amz_date_header, sizeof(x_amz_date_header), "x-amz-date: %s", datetime);

    // Upload file using libcurl
    CURL *curl = curl_easy_init();
    if (!curl) {
        fprintf(stderr, "Failed to initialize CURL\n");
        free(file_content);
        return -1;
    }

    struct curl_slist *headers = NULL;
    headers = curl_slist_append(headers, "x-amz-content-sha256: UNSIGNED-PAYLOAD");
    headers = curl_slist_append(headers, x_amz_date_header);
    headers = curl_slist_append(headers, authorization_header);

    char url[512];
    snprintf(url, sizeof(url), "https://%s/%s", HOST_NAME, UPLOAD_PATH);

    curl_easy_setopt(curl, CURLOPT_URL, url);
    curl_easy_setopt(curl, CURLOPT_CUSTOMREQUEST, "PUT");
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, file_content);
    curl_easy_setopt(curl, CURLOPT_POSTFIELDSIZE, file_size);

        // 禁用 SSL 证书验证（不推荐用于生产环境）
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYPEER, 0L);
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYHOST, 0L);

    // 启用详细调试模式，打印发送的头和其他调试信息
    curl_easy_setopt(curl, CURLOPT_VERBOSE, 1L);
    // curl_easy_setopt(curl, CURLOPT_DEBUGFUNCTION, my_debug_callback);

    CURLcode res = curl_easy_perform(curl);
    long http_code = 0;
    auto curl_code =
        curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &http_code);
    if (http_code == 200 && res != CURLE_OK) {
      printf("File uploaded successfully\n");
    } else {
      fprintf(stderr, "http_code: %ld, CURL error: %s\n", http_code,
              curl_easy_strerror(res));
    }

    curl_slist_free_all(headers);
    curl_easy_cleanup(curl);
    free(file_content);

    return res == CURLE_OK ? 0 : -1;
}

int main() {
    if (upload_file(FILE_PATH) == 0) {
        printf("Upload completed!\n");
    } else {
        printf("Upload failed.\n");
    }
    return 0;
}
