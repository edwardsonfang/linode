### **Introduction to `Uploadfiles` Class**

The `Uploadfiles` class is a Java implementation designed to facilitate the secure and efficient upload of files to Linode Object Storage. It uses **OkHttp**, a high-performance HTTP client, to handle network requests, and implements custom authentication based on AWS Signature Version 4, adapted for Linode's S3-compatible storage.

---

### **Key Features**
1. **File Upload to Object Storage:**
   - The `uploadFile` method allows users to upload local files to a specified bucket in Linode Object Storage. It constructs a PUT request with appropriate headers for authentication and file metadata.

2. **Custom Authentication:**
   - The class implements AWS Signature Version 4 to sign requests, ensuring secure access to object storage.
   - Modifications are included to adapt to Linode's S3-compatible API, such as adjusting the canonical headers.

3. **Resource Usage Monitoring:**
   - The response from the server includes rate-limit headers (`x-ratelimit-limit`, `x-ratelimit-remaining`, `x-ratelimit-reset`), which are extracted and logged to provide insights into API usage and limits.

4. **Reusable Utility Functions:**
   - The class includes utility methods for:
     - Generating secure hash signatures using HMAC-SHA256.
     - Formatting timestamps in ISO 8601 format.
     - Converting byte arrays to hexadecimal strings.

5. **Scalability and Flexibility:**
   - The `BUCKET_NAME`, `REGION`, `ACCESS_KEY`, and `SECRET_KEY` are configurable, making the class reusable across different environments and projects.

---

### **How It Works**
1. **Initialization:**
   - The user provides the file path and object key (file name in the bucket).
   - The class verifies the file's existence before proceeding.

2. **Request Construction:**
   - A PUT request is created using the OkHttp client, embedding headers such as `Authorization`, `x-amz-content-sha256`, and `x-amz-date`.

3. **Request Signing:**
   - The `generateSignature` method constructs the canonical request and signs it using AWS Signature Version 4, ensuring secure communication.

4. **Response Handling:**
   - The response from Linode Object Storage is evaluated for success or failure.
   - Rate-limit information from the headers is logged for monitoring and optimization.

---

### **Use Case**
The `Uploadfiles` class is ideal for applications that need to interact with Linode Object Storage programmatically, providing a robust solution for file uploads while maintaining security and performance. It can be extended to include features like batch uploads, error retry mechanisms, or additional request types (e.g., GET, DELETE).
